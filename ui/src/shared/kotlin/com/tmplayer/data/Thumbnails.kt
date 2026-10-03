package com.tmplayer.data

import androidx.compose.ui.graphics.ImageBitmap
import dev.g000sha256.tdl.TdlResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
        val bytes = (memoryClassMb * 1024 * 1024 / CACHE_HEAP_FRACTION)
            .coerceIn(MIN_CACHE_BYTES, MAX_CACHE_BYTES)
        cache = newCache(bytes)
    }

    /** Hands the pictures back: all of them, or half when the system is only asking politely. */
    fun trim(everything: Boolean) {
        if (everything) cache.evictAll() else cache.trimToSize(cache.size() / 2)
    }

    fun mini(data: ByteArray?): ImageBitmap? {
        if (data == null || data.isEmpty()) return null
        val key = "mini:${data.size}:${data.contentHashCode()}"
        cache.get(key)?.let { return it }
        return decodeImage(data)?.also { cache.put(key, it) }
    }

    /** Downloads the real thumbnail if needed. Returns null when there isn't one. */
    suspend fun full(fileId: Int): ImageBitmap? {
        if (fileId <= 0) return null
        val key = "file:$fileId"
        cache.get(key)?.let { return it }

        val path = withContext(Dispatchers.IO) { downloadThumbnail(fileId) } ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { decodeImageFile(path, MAX_THUMBNAIL_WIDTH) }.getOrNull()?.also { cache.put(key, it) }
        }
    }

    private suspend fun downloadThumbnail(fileId: Int): String? {
        val td = Td.awaitAuthorizedSession().client
        val file = td.getFile(fileId).valueOrNull ?: return null
        if (file.local.isDownloadingCompleted && !file.local.path.isNullOrEmpty()) {
            return file.local.path
        }
        val online = connectivity?.canTryInternet() ?: true
        if (!online && !Td.connected.value) return null

        // Thumbnails are small and plentiful; low priority keeps them behind playback.
        val started = td.downloadFile(
            fileId = fileId,
            priority = THUMBNAIL_PRIORITY,
            offset = 0,
            limit = 0,
            synchronous = false,
        )
        if (started is TdlResult.Failure) return null

        // Cancelling the waiting coroutine does not stop TDLib fetching, which on a long scroll
        // leaves a queue of downloads for cards nobody can see competing with the video for the
        // same connection. So withdraw the request on the way out too.
        val done = try {
            withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
                td.fileUpdates
                    .filter { it.file.id == fileId && it.file.local.isDownloadingCompleted }
                    .first()
            }
        } catch (cancellation: CancellationException) {
            Td.cancelDownloadInBackground(fileId)
            throw cancellation
        }
        if (done == null) Td.cancelDownloadInBackground(fileId)
        val path = done?.file?.local?.path ?: td.getFile(fileId).valueOrNull?.local?.path
        return path?.takeIf { it.isNotEmpty() }
    }

    private const val CACHE_BYTES = 12 * 1024 * 1024
    private const val CACHE_HEAP_FRACTION = 8
    private const val MIN_CACHE_BYTES = 4 * 1024 * 1024
    private const val MAX_CACHE_BYTES = 32 * 1024 * 1024

    /** Card art is never larger than ~400 px wide; decoding full size would waste heap. */
    private const val MAX_THUMBNAIL_WIDTH = 400
    private const val THUMBNAIL_PRIORITY = 4
    private const val DOWNLOAD_TIMEOUT_MS = 20_000L
}

/**
 * A least recently used map bounded by the total of [sizeOf] over its values, as Android's
 * `LruCache` is, written out because that class is Android's alone.
 */
internal class SizedLru<K : Any, V : Any>(
    private val maxSize: Int,
    private val sizeOf: (V) -> Int,
) {
    private val map = LinkedHashMap<K, V>(0, 0.75f, true)
    private var size = 0

    @Synchronized
    fun get(key: K): V? = map[key]

    @Synchronized
    fun put(key: K, value: V) {
        map.put(key, value)?.let { size -= sizeOf(it) }
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
