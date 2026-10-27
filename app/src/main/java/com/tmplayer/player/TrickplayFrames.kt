package com.tmplayer.player

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import com.tmplayer.data.Trickplay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/** Pictures for the scrub bar. See [Trickplay] for which positions get one. */
interface ScrubThumbnails {
    /**
     * Asks for the frame at [positionMs]. [onFrame] runs on the main thread, with null when that
     * part of the video is not on the disk or could not be read.
     */
    fun request(positionMs: Long, onFrame: (Bitmap?) -> Unit)

    fun release()
}

/**
 * Frames read with Android's own `MediaMetadataRetriever`, from the file TDLib is writing.
 *
 * Nothing here reaches the network. The retriever reads the local file, a finished one directly
 * and a partial one through [PartialFile], which answers every byte that is not on the disk with
 * zeros; [Trickplay.covered] makes sure no frame is asked for there, so the zeros are only what an
 * extractor sees while it sizes the file up. A container whose index sits at the end (an MP4
 * without "fast start") cannot be opened until that end is here, and simply gets no thumbnails.
 *
 * One retriever, on one background thread, so a scrub never decodes two frames at once beside the
 * film that is already decoding, and only the newest request is worth finishing. A phone's only:
 * a television decodes nothing beside the film (see `PlayerActivity.startTrickplay`).
 */
class TrickplayFrames(
    private val scope: CoroutineScope,
    private val path: () -> String?,
    private val spans: () -> List<Trickplay.Span>,
    private val complete: () -> Boolean,
    private val sizeBytes: () -> Long,
    private val durationMs: () -> Long,
) : ScrubThumbnails {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.IO.limitedParallelism(1)

    private val cache = LruCache<Long, Bitmap>(CACHE_FRAMES)
    private var job: Job? = null

    // Touched only on [worker].
    private var retriever: MediaMetadataRetriever? = null
    private var openedFor: Pair<String, Boolean>? = null
    private var failedFor: Pair<String, Boolean>? = null

    /**
     * When the file last failed to open, and how much of it was on the disk then. A file being
     * streamed often cannot be opened at first (an MKV's index, an MP4's moov at the end, is not
     * here yet), and used to be given up on for the rest of the session: no scrub pictures at all
     * while streaming. It is tried again once [RETRY_BYTES] more has arrived, or [RETRY_MS] on.
     */
    private var failedAt = 0L
    private var failedBytes = 0L

    override fun request(positionMs: Long, onFrame: (Bitmap?) -> Unit) {
        val duration = durationMs()
        val file = path()
        val done = complete()
        if (file == null || !Trickplay.covered(positionMs, duration, sizeBytes(), spans(), done)) {
            job?.cancel()
            // The nearest picture already made, when it is close enough to say where this is.
            onFrame(nearest(positionMs))
            return
        }
        val slot = Trickplay.bucketMs(positionMs)
        cache.get(slot)?.let {
            job?.cancel()
            onFrame(it)
            return
        }
        // The newest position is the only one still on screen when its frame lands.
        job?.cancel()
        job = scope.launch {
            // Kept on the worker as soon as it is made: a decode cannot be stopped part way, and
            // one finished after the finger moved on used to be thrown away, so a drag that never
            // rested made frame after frame and kept none.
            val frame = withContext(worker) {
                cache.get(slot) ?: frameAt(file, done, slot)?.also { cache.put(slot, it) }
            }
            onFrame(frame ?: nearest(positionMs))
        }
    }

    /**
     * Lets go of the pictures made for the video before: a next episode played in the same player
     * must not be shown the last one's frames. The retriever opens the new file by itself.
     */
    fun forget() {
        job?.cancel()
        cache.evictAll()
    }

    override fun release() {
        job?.cancel()
        scope.launch(worker) {
            runCatching { retriever?.release() }
            retriever = null
        }
        cache.evictAll()
    }

    /** The cached picture nearest [positionMs], within [NEAREST_MS], or null. */
    private fun nearest(positionMs: Long): Bitmap? = cache.snapshot().entries
        .filter { kotlin.math.abs(it.key - positionMs) <= NEAREST_MS }
        .minByOrNull { kotlin.math.abs(it.key - positionMs) }
        ?.value

    private fun onDisk(): Long = spans().sumOf { it.end - it.start }

    private fun frameAt(file: String, done: Boolean, slotMs: Long): Bitmap? {
        val key = file to done
        if (failedFor == key) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (onDisk() - failedBytes < RETRY_BYTES && now - failedAt < RETRY_MS) return null
            failedFor = null
        }
        val reader = if (openedFor == key) retriever else open(file, done, key)
        reader ?: return null
        val us = slotMs * 1_000
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                reader.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, WIDTH_PX, HEIGHT_PX)
            } else {
                reader.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { full ->
                    val scale = minOf(WIDTH_PX.toFloat() / full.width, HEIGHT_PX.toFloat() / full.height)
                    Bitmap.createScaledBitmap(full, (full.width * scale).toInt(), (full.height * scale).toInt(), true)
                        .also { if (it !== full) full.recycle() }
                }
            }
        }.getOrNull()
    }

    private fun open(file: String, done: Boolean, key: Pair<String, Boolean>): MediaMetadataRetriever? {
        runCatching { retriever?.release() }
        retriever = null
        openedFor = null
        val reader = MediaMetadataRetriever()
        val opened = runCatching {
            if (done) reader.setDataSource(file) else reader.setDataSource(PartialFile(File(file), sizeBytes()))
        }.isSuccess
        if (!opened) {
            runCatching { reader.release() }
            failedFor = key
            failedAt = android.os.SystemClock.elapsedRealtime()
            failedBytes = onDisk()
            return null
        }
        retriever = reader
        openedFor = key
        return reader
    }

    /**
     * A file still being written, presented at its full length. Bytes past what is on the disk
     * read as zeros rather than as the end of the file, which an extractor would take to mean the
     * video is shorter than it is.
     */
    private class PartialFile(file: File, private val fullSize: Long) : MediaDataSource() {
        private val access = RandomAccessFile(file, "r")

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= fullSize) return -1
            val wanted = minOf(size.toLong(), fullSize - position).toInt()
            val onDisk = access.length()
            var read = 0
            if (position < onDisk) {
                access.seek(position)
                read = access.read(buffer, offset, minOf(wanted.toLong(), onDisk - position).toInt()).coerceAtLeast(0)
            }
            if (read < wanted) buffer.fill(0, offset + read, offset + wanted)
            return wanted
        }

        override fun getSize(): Long = fullSize

        override fun close() {
            access.close()
        }
    }

    private companion object {
        /** About two minutes of bar at the 10 second step, a few megabytes at this size. */
        const val CACHE_FRAMES = 24

        /** How far from the scrub point a picture already made may stand in for the right one. */
        const val NEAREST_MS = 45_000L

        /** A file that would not open is tried again after this much more of it, or this long. */
        const val RETRY_BYTES = 8L * 1024 * 1024
        const val RETRY_MS = 15_000L

        /** The picture's bounds; the retriever keeps the video's shape inside them. */
        const val WIDTH_PX = 320
        const val HEIGHT_PX = 180
    }
}
