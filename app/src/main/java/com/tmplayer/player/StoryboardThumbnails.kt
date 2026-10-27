package com.tmplayer.player

import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import android.util.LruCache
import com.tmplayer.data.JpegSize
import com.tmplayer.data.StoryboardFiles
import com.tmplayer.data.StoryboardMap
import com.tmplayer.data.StoryboardMaps
import com.tmplayer.data.Thumbnails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Scrub pictures from Telegram's storyboard for the video (CP80): one small JPEG of tiles and its
 * map, fetched through TDLib as soon as the post says there is one. Each tile is cut out of the
 * JPEG with a region decoder, so the whole sprite is never in memory at once: this is what a
 * television, which decodes no frames of its own, gets, and what a phone shows first.
 *
 * The fetch is its own job, never a scrub's: it used to run inside the request, which every move
 * of the finger (every press of the remote) cancelled, and a cancelled thumbnail fetch withdraws
 * the TDLib download. The sprite never finished arriving, so no storyboard was ever seen.
 */
class StoryboardThumbnails(
    private val scope: CoroutineScope,
    private val files: StoryboardFiles,
    private val durationMs: () -> Long,
) : ScrubThumbnails {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.IO.limitedParallelism(1)
    private val cache = LruCache<Int, Bitmap>(CACHE_TILES)
    private val loading = Mutex()
    private var job: Job? = null

    /** The sprite's and the map's paths on the disk, on their way or arrived; null for neither. */
    private var fetch: Deferred<Pair<String?, String?>>? = null

    // Touched only on [worker], under [loading].
    private var decoder: BitmapRegionDecoder? = null
    private var map: StoryboardMap? = null
    private var failed = false

    init {
        fetchFiles()
    }

    /** Starts the two small downloads unless they are already on their way or here. */
    private fun fetchFiles(): Deferred<Pair<String?, String?>> {
        fetch?.let { running -> if (!running.isCompleted || running.getCompletedOrNull()?.first != null) return running }
        return scope.async(Dispatchers.IO) {
            val image = Thumbnails.file(files.imageFileId)
            val map = Thumbnails.file(files.mapFileId)
            image to map
        }.also { fetch = it }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun Deferred<Pair<String?, String?>>.getCompletedOrNull(): Pair<String?, String?>? =
        runCatching { getCompleted() }.getOrNull()

    override fun request(positionMs: Long, onFrame: (Bitmap?) -> Unit) {
        // The player answers only on its own thread, so its length is read here, before the worker.
        val totalMs = durationMs()
        // Asked again where the last try did not arrive (offline, or slow): a new scrub is a
        // new chance. Never more than one fetch at a time.
        val files = fetchFiles()
        job?.cancel()
        job = scope.launch {
            // Waiting on the fetch here does not cancel it when the next move cancels this job.
            val paths = files.await()
            val frame = withContext(worker) { tileFor(positionMs, totalMs, paths) }
            onFrame(frame)
        }
    }

    private suspend fun tileFor(positionMs: Long, totalMs: Long, paths: Pair<String?, String?>): Bitmap? {
        val (reader, tiles) = open(totalMs, paths) ?: return null
        val tile = tiles.tileAt(positionMs) ?: return null
        val index = tiles.tiles.indexOf(tile)
        cache.get(index)?.let { return it }
        val rect = Rect(tile.x, tile.y, tile.x + tile.width, tile.y + tile.height)
        if (rect.right > reader.width || rect.bottom > reader.height) return null
        return runCatching { reader.decodeRegion(rect, null) }.getOrNull()?.also { cache.put(index, it) }
    }

    private suspend fun open(totalMs: Long, paths: Pair<String?, String?>): Pair<BitmapRegionDecoder, StoryboardMap>? = loading.withLock {
        val ready = decoder
        val tiles = map
        if (ready != null && tiles != null) return ready to tiles
        if (failed) return null
        // A map spread over the video's length needs the length: not known yet, asked again later.
        if (totalMs <= 0) return null
        val (image, mapPath) = paths
        if (image == null) {
            // Not fetched this time (offline, or slow): asked again on the next drag.
            return null
        }
        val opened = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) BitmapRegionDecoder.newInstance(image) else {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(image, false)
            }
        }.getOrNull()
        if (opened == null) {
            failed = true
            return null
        }
        val size = runCatching { JpegSize.read(File(image).readBytes()) }.getOrNull() ?: (opened.width to opened.height)
        val text = mapPath?.let { runCatching { File(it).readText() }.getOrNull() }.orEmpty()
        // Telegram's map is an undocumented sidecar: one it cannot read means no frames (HX10).
        val parsed = runCatching { StoryboardMaps.parse(text, files.tileWidth, files.tileHeight, size.first, size.second, totalMs) }
            .getOrElse { StoryboardMap(emptyList()) }
        if (parsed.isEmpty) {
            opened.recycle()
            failed = true
            return null
        }
        decoder = opened
        map = parsed
        opened to parsed
    }

    override fun release() {
        job?.cancel()
        fetch?.cancel()
        fetch = null
        scope.launch(worker) {
            loading.withLock {
                runCatching { decoder?.recycle() }
                decoder = null
            }
        }
        cache.evictAll()
    }

    private companion object {
        const val CACHE_TILES = 48
    }
}

/**
 * Two sources of scrub pictures, the first asked first and the second only where the first has
 * nothing: the storyboard's tile (a small region of a sprite already here), else a frame read from
 * the part of the video already on the disk.
 */
class LayeredThumbnails(private val first: ScrubThumbnails, private val second: ScrubThumbnails) : ScrubThumbnails {
    private var asked = 0L

    override fun request(positionMs: Long, onFrame: (Bitmap?) -> Unit) {
        val ticket = ++asked
        first.request(positionMs) { frame ->
            if (ticket != asked) return@request
            if (frame != null) onFrame(frame) else second.request(positionMs) { tile -> if (ticket == asked) onFrame(tile) }
        }
    }

    override fun release() {
        first.release()
        second.release()
    }
}
