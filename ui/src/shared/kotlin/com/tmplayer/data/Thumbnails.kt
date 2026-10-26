package com.tmplayer.data

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Telegram thumbnail data for media previews.
 *
 * Telegram ships a few-hundred-byte blurred JPEG inside every message, so a card can show
 * something the instant it scrolls into view, then swap in the real thumbnail once TDLib has
 * fetched it. No image library involved: these are tiny bitmaps and a 1 GB stick appreciates
 * the missing dependency.
 *
 * The cache and the fetching are shared; turning bytes into an [ImageBitmap] is each platform's
 * (`ThumbnailDecoding.kt`: `BitmapFactory` on Android, Skia on the desktop).
 */
object Thumbnails {

    /**
     * Sized from the device rather than fixed.
     *
     * A flat figure is a large share of the heap on a 1 GB stick, whose per-app limit is around
     * 96 MB, and a rounding error on a phone with 8 GB. An eighth of the heap is the figure image
     * libraries settle on for the same reason.
     */
    @Volatile
    private var cache = newCache(CACHE_BYTES)

    /**
     * Asked before a thumbnail is fetched over the network. Android installs its `NetworkMonitor`;
     * with nothing installed every fetch is allowed to try.
     */
    @Volatile
    var connectivity: Connectivity? = null

    private fun newCache(bytes: Int) = SizedLru<String, ImageBitmap>(bytes) { it.byteCount() }

    /** Called once at start-up, before anything has been cached. */
    fun sizeFor(memoryClassMb: Int) {
        memoryClass = memoryClassMb
        cache = newCache(budget())
    }

    @Volatile
    private var memoryClass = 0

    /**
     * The "Lighter pictures" setting (CP68): half the usual share of the heap for pictures, and
     * thumbnails, posters and stills decoded at [lighter] widths. Set from the stored choice; a
     * change empties the cache, since what is in it was decoded at the other size. Snapshot
     * state, so the lists already on screen take the new look-ahead and art at once.
     */
    var light: Boolean
        get() = lightState.value
        set(value) {
            if (lightState.value == value) return
            lightState.value = value
            cache = newCache(budget())
        }

    private val lightState = androidx.compose.runtime.mutableStateOf(false)

    private fun budget(): Int {
        if (fixedBudget > 0) return if (light) fixedBudget / 2 else fixedBudget
        if (memoryClass <= 0) return if (light) CACHE_BYTES / 2 else CACHE_BYTES
        val fraction = if (light) CACHE_HEAP_FRACTION * 2 else CACHE_HEAP_FRACTION
        return (memoryClass * 1024 * 1024 / fraction).coerceIn(MIN_CACHE_BYTES, MAX_CACHE_BYTES)
    }

    /** A decode width under [light]: three fifths, and never below what a small tile needs. */
    // A television always: its posters are drawn about 250 px wide, and each picture decoded
    // larger was a bigger upload to a TV stick's GPU as a row moved (measured on a Mi TV, 1 GB).
    fun lighter(maxWidth: Int): Int = if (light || com.tmplayer.ui.theme.Motion.television) (maxWidth * 3 / 5).coerceAtLeast(LIGHT_MIN_WIDTH).coerceAtMost(maxWidth) else maxWidth

    /** Hands the pictures back: all of them, or half when the system is only asking politely. */
    fun trim(everything: Boolean) {
        if (everything) cache.evictAll() else cache.trimToSize(cache.size() / 2)
    }

    /**
     * The inline preview, softened: Telegram's preview is a few dozen pixels across, and drawn
     * stretched over a card it showed as a blocky, low resolution copy of the picture until the
     * real one came. Blurred ([MiniBlur]) it reads as the picture coming into focus, as streaming
     * apps and Telegram itself show it. Every placeholder on every platform comes from here.
     */
    fun mini(data: ByteArray?): ImageBitmap? {
        if (data == null || data.isEmpty()) return null
        val key = "mini:${data.size}:${data.contentHashCode()}"
        cache.get(key)?.let { return it }
        val raw = decodeImage(data) ?: return null
        // Not on a television: the preview stretched with low filtering is soft already.
        if (com.tmplayer.ui.theme.Motion.television) return raw.also { cache.put(key, it) }
        return (runCatching { MiniBlur.soften(raw) }.getOrNull() ?: raw).also { cache.put(key, it) }
    }

    /**
     * The thumbnail's own file, downloaded first when TDLib does not have it yet: for a caller that
     * wants the picture itself rather than a decoded bitmap (the TV home screen's rows, which hand
     * a copy to the launcher). Null when there is none, or it did not arrive in time.
     */
    suspend fun path(fileId: Int): String? {
        if (fileId <= 0) return null
        return withContext(Dispatchers.IO) { downloadThumbnail(fileId) }
    }

    /** Downloads the real thumbnail if needed. Returns null when there isn't one. */
    suspend fun full(fileId: Int): ImageBitmap? {
        if (fileId <= 0) return null
        val key = "file:$fileId"
        cache.get(key)?.let { return it }

        val path = withContext(Dispatchers.IO) { downloadThumbnail(fileId) } ?: return null
        // Another tile with the same video (Home's rows and the carousel share many) may have
        // decoded it while this one waited for the file.
        cache.get(key)?.let { return it }
        return withContext(decoding) {
            runCatching { decodeTdImage(fileId, path, lighter(MAX_THUMBNAIL_WIDTH)) }.getOrNull()?.also { cache.put(key, it) }
        }
    }

    /**
     * The full thumbnail's file on the disk, fetched first when it is not there yet; null when the
     * video has none or it could not be fetched. For pictures handed to another app, which needs
     * the file rather than the decoded bitmap.
     */
    suspend fun file(fileId: Int): String? =
        if (fileId <= 0) null else withContext(Dispatchers.IO) { runCatching { downloadThumbnail(fileId) }.getOrNull() }

    /** A thumbnail already decoded, for the first frame, so a tile scrolled back to does not fade in again. */
    fun peekFull(fileId: Int): ImageBitmap? = if (fileId <= 0) null else cache.get("file:$fileId")

    /**
     * A soft colour field for behind a hero or a page: the full thumbnail (or, until there is
     * one, the inline preview in [mini]) shrunk to [BACKDROP_WIDTH] pixels across, to be drawn
     * stretched over the whole frame with bilinear filtering.
     *
     * That is the backdrop's blur, made by scaling down rather than by a shader: one tiny bitmap,
     * no `blur()`, nothing a television stick on Android 9 cannot draw. Kept under
     * `backdrop:<fileId>` once it comes from the full thumbnail; one made from the inline preview
     * is kept under the preview's own key, so the full one still replaces it when it arrives.
     */
    suspend fun backdrop(fileId: Int, mini: ByteArray?): ImageBitmap? {
        val key = "backdrop:$fileId"
        if (fileId > 0) cache.get(key)?.let { return it }
        full(fileId)?.let { source ->
            return withContext(Dispatchers.Default) { shrink(source, BACKDROP_WIDTH) }.also { cache.put(key, it) }
        }
        if (mini == null || mini.isEmpty()) return null
        val miniKey = "backdrop:mini:${mini.size}:${mini.contentHashCode()}"
        cache.get(miniKey)?.let { return it }
        return withContext(Dispatchers.Default) { mini(mini)?.let { shrink(it, BACKDROP_WIDTH) } }
            ?.also { cache.put(miniKey, it) }
    }

    /** A poster already decoded, for the first frame. See [online]. */
    fun peekOnline(url: String, maxWidth: Int): ImageBitmap? = cache.get(onlineKey(url, lighter(maxWidth)))

    /**
     * A poster from the online metadata providers: [fetch] puts it on the disk (or finds it there),
     * and it is decoded here at [maxWidth] and kept beside the thumbnails, under the same budget.
     */
    suspend fun online(url: String, wanted: Int, alpha: Boolean = false, fetch: suspend () -> java.io.File?): ImageBitmap? {
        val maxWidth = lighter(wanted)
        val key = onlineKey(url, maxWidth)
        cache.get(key)?.let { return it }
        val file = withContext(Dispatchers.IO) { runCatching { fetch() }.getOrNull() } ?: return null
        cache.get(key)?.let { return it }
        return withContext(decoding) {
            runCatching { decodeImageFile(file.path, maxWidth, alpha) }.getOrNull()?.also { cache.put(key, it) }
        }
    }

    /**
     * The best already decoded copy of the provider's picture [url] for drawing [pixels] wide: the
     * larger copy [sharp] would fetch, else the one named. For the first frame.
     */
    fun peekSharp(url: String, pixels: Int, maxWidth: Int, sharper: (String, Int) -> String = ::sharperUrl): ImageBitmap? {
        val larger = sharper(url, lighter(pixels))
        val width = lighter(maxWidth)
        return (if (larger != url) cache.get(onlineKey(larger, width)) else null)
            // The copy [sharp] last decoded for [url], whatever width asked for it: a tile composed
            // again has not been measured yet, so [pixels] is its default and names a smaller copy
            // than the one its measured width fetched. Missing that, a poster scrolled back to
            // drew nothing on its first frame and faded in again although it was in memory.
            ?: sharpOf.get(url)?.let { cache.get(onlineKey(it, width)) }
            ?: peekOnline(url, maxWidth)
    }

    /** Which larger copy [sharp] last decoded for a named picture: the named address to the one fetched. */
    private val sharpOf = SizedLru<String, String>(SHARP_INDEX_ENTRIES) { 1 }

    /**
     * [online], at the size the picture is drawn: where the platform decodes at full size (the
     * desktop), the provider's copy at least [pixels] wide ([sharperUrl]) is fetched in place of
     * the one [url] names, which is only fetched when there is no larger one or it cannot be had.
     * [maxWidth] is the decode width Android samples down to, as for [online]. [sharper] picks the
     * larger copy; a backdrop passes the provider's ladder on every platform.
     */
    suspend fun sharp(
        url: String,
        pixels: Int,
        maxWidth: Int,
        sharper: (String, Int) -> String = ::sharperUrl,
        fetch: suspend (String) -> java.io.File?,
    ): ImageBitmap? {
        val larger = sharper(url, lighter(pixels))
        if (larger != url) {
            online(larger, maxWidth) { fetch(larger) }?.let {
                sharpOf.put(url, larger)
                return it
            }
        }
        return online(url, maxWidth) { fetch(url) }
    }

    /** Where the platform decodes at full size one entry serves every width, so the width is not in the key. */
    private fun onlineKey(url: String, maxWidth: Int): String = if (decodesFullSize) "online:$url" else "online:$maxWidth:$url"

    /** How a picture is sampled when drawn at another size than its own: see the platform's `artFilter`. */
    val drawFilter: FilterQuality get() = artFilter

    /**
     * Gives the picture cache [bytes] (half under [light]), for a platform that sizes it from its
     * own heap rather than [sizeFor]'s memory class: the desktop, whose carousel holds pictures
     * the width of a 4K screen, and the browser, which decodes at full size too and without it
     * kept [CACHE_BYTES], a dozen posters, so a row scrolled away and back fetched them again.
     */
    fun budget(bytes: Int) {
        fixedBudget = bytes
        cache = newCache(budget())
    }

    @Volatile
    private var fixedBudget = 0

    /**
     * Where pictures are decoded: a few at a time, off the I/O pool. Decoding is work for the
     * processor, and a fast scroll used to start one for every tile at once on the I/O pool's
     * sixty-four threads, which on a four core stick left the frames waiting behind them. Queued
     * here, the decode of a tile already scrolled away is dropped before it starts, since
     * [withContext] checks for cancellation as it is dispatched.
     *
     * Two at a time on a television: a stick has four weak cores, and three decoders left the UI
     * and render threads one between them. Made on first use, after the app has said whether it
     * is a television ([Motion.television]).
     */
    private val decoding by lazy {
        Dispatchers.Default.limitedParallelism(
            if (com.tmplayer.ui.theme.Motion.television) DECODE_PARALLELISM_TV else DECODE_PARALLELISM,
        )
    }

    private suspend fun downloadThumbnail(fileId: Int): String? {
        val td = Td.awaitAuthorizedSession().client
        val file = td.getFile(fileId).valueOrNull ?: return null
        if (file.local.isDownloadingCompleted && !file.local.path.isNullOrEmpty()) {
            return file.local.path
        }
        val online = connectivity?.canTryInternet() ?: true
        if (!online && !Td.connected.value) return null

        // Cancelling the waiting coroutine does not stop TDLib fetching, which on a long scroll
        // leaves a queue of downloads for cards nobody can see competing with the video for the
        // same connection. So withdraw the request on the way out too.
        //
        // The updates are listened to before the download is asked for, and the file the request
        // answers with is looked at first: a small picture often lands, or was already there, in
        // the moment between the two, and an update missed there used to sit out the whole
        // [DOWNLOAD_TIMEOUT_MS] with the card blank.
        var failed = false
        val done = try {
            withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
                coroutineScope {
                    val completion = async(start = CoroutineStart.UNDISPATCHED) {
                        td.fileUpdates
                            .filter { it.file.id == fileId && it.file.local.isDownloadingCompleted }
                            .first()
                            .file
                    }
                    // Thumbnails are small and plentiful; low priority keeps them behind playback.
                    val started = td.downloadFile(
                        fileId = fileId,
                        priority = THUMBNAIL_PRIORITY,
                        offset = 0,
                        limit = 0,
                        synchronous = false,
                    ).valueOrNull
                    when {
                        started == null -> {
                            failed = true
                            completion.cancel()
                            null
                        }
                        started.local.isDownloadingCompleted -> {
                            completion.cancel()
                            started
                        }
                        else -> completion.await()
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            Td.cancelDownloadInBackground(fileId)
            throw cancellation
        }
        if (failed) return null
        if (done == null) Td.cancelDownloadInBackground(fileId)
        val path = done?.local?.path?.takeIf { it.isNotEmpty() } ?: td.getFile(fileId).valueOrNull?.local?.path
        return path?.takeIf { it.isNotEmpty() }
    }

    /** How many named pictures [peekSharp] remembers the larger copy of: far more than the cache holds. */
    private const val SHARP_INDEX_ENTRIES = 2048

    private const val CACHE_BYTES = 12 * 1024 * 1024
    private const val CACHE_HEAP_FRACTION = 8
    private const val MIN_CACHE_BYTES = 4 * 1024 * 1024
    private const val MAX_CACHE_BYTES = 32 * 1024 * 1024

    /**
     * How wide [backdrop]'s field is. Narrow enough that stretched over a screen it is a wash of
     * the picture's colours with no detail left to look blocky; wide enough to keep where they are.
     */
    const val BACKDROP_WIDTH = 32

    /** Card art is never larger than ~400 px wide; decoding full size would waste heap. */
    private const val MAX_THUMBNAIL_WIDTH = 400

    /** The narrowest a picture is decoded at under [light]. */
    private const val LIGHT_MIN_WIDTH = 160
    private const val THUMBNAIL_PRIORITY = 4
    private const val DECODE_PARALLELISM = 3
    private const val DECODE_PARALLELISM_TV = 2
    private const val DOWNLOAD_TIMEOUT_MS = 20_000L
}

/**
 * [source] scaled down to [width] pixels across, its shape kept, through Compose's own canvas so
 * both platforms share it. Halved in steps, each a bilinear draw, rather than in one jump: a
 * single bilinear draw from 400 pixels to 32 samples one pixel in twelve and keeps a speck
 * rather than the colour around it.
 */
internal fun shrink(source: ImageBitmap, width: Int): ImageBitmap {
    var current = source
    while (current.width > width) {
        val nextWidth = maxOf(width, current.width / 2)
        val nextHeight = maxOf(1, (current.height.toLong() * nextWidth / current.width).toInt())
        val next = ImageBitmap(nextWidth, nextHeight)
        Canvas(next).drawImageRect(
            image = current,
            srcSize = IntSize(current.width, current.height),
            dstSize = IntSize(nextWidth, nextHeight),
            paint = Paint().apply { filterQuality = FilterQuality.Low },
        )
        current = next
    }
    return current
}

/**
 * A least recently used map bounded by the total of [sizeOf] over its values, as Android's
 * `LruCache` is, written out because that class is Android's alone.
 */
internal class SizedLru<K : Any, V : Any>(
    private val maxSize: Int,
    private val sizeOf: (V) -> Int,
) {
    // Insertion ordered, and a read moves the entry to the end: the order a LinkedHashMap in
    // access order keeps, which only the JVM's has.
    private val map = LinkedHashMap<K, V>()
    private var size = 0

    @Synchronized
    fun get(key: K): V? {
        val value = map.remove(key) ?: return null
        map[key] = value
        return value
    }

    @Synchronized
    fun put(key: K, value: V) {
        map.remove(key)?.let { size -= sizeOf(it) }
        map[key] = value
        size += sizeOf(value)
        trimToSize(maxSize)
    }

    @Synchronized
    fun size(): Int = size

    @Synchronized
    fun evictAll() = trimToSize(-1)

    @Synchronized
    fun trimToSize(target: Int) {
        val entries = map.entries.iterator()
        while (size > target && entries.hasNext()) {
            val eldest = entries.next()
            size -= sizeOf(eldest.value)
            entries.remove()
        }
    }
}
