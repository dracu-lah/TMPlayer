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
 * map, fetched once through TDLib the first time the bar is dragged. Each tile is cut out of the
 * JPEG with a region decoder, so the whole sprite is never in memory at once: this is what a
 * 1 GB stick, which has no [TrickplayFrames], gets, and what any device shows before the part of
 * the video under the finger has been fetched.
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

    // Touched only on [worker], under [loading].
    private var decoder: BitmapRegionDecoder? = null
    private var map: StoryboardMap? = null
    private var failed = false

    override fun request(positionMs: Long, onFrame: (Bitmap?) -> Unit) {
        // The player answers only on its own thread, so its length is read here, before the worker.
        val totalMs = durationMs()
        job?.cancel()
        job = scope.launch {
            val frame = withContext(worker) { tileFor(positionMs, totalMs) }
            onFrame(frame)
        }
    }

    private suspend fun tileFor(positionMs: Long, totalMs: Long): Bitmap? {
        val (reader, tiles) = open(totalMs) ?: return null
        val tile = tiles.tileAt(positionMs) ?: return null
        val index = tiles.tiles.indexOf(tile)
        cache.get(index)?.let { return it }
        val rect = Rect(tile.x, tile.y, tile.x + tile.width, tile.y + tile.height)
        if (rect.right > reader.width || rect.bottom > reader.height) return null
        return runCatching { reader.decodeRegion(rect, null) }.getOrNull()?.also { cache.put(index, it) }
    }

    private suspend fun open(totalMs: Long): Pair<BitmapRegionDecoder, StoryboardMap>? = loading.withLock {
        val ready = decoder
        val tiles = map
        if (ready != null && tiles != null) return ready to tiles
        if (failed) return null
        val image = Thumbnails.file(files.imageFileId)
        val mapPath = Thumbnails.file(files.mapFileId)
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
 * Two sources of scrub pictures, the first asked first: the frame read from the video where that
 * part is on the disk, else the storyboard's tile.
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
