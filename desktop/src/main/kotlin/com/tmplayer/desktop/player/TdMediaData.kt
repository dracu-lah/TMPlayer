package com.tmplayer.desktop.player

import com.tmplayer.platform.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.openani.mediamp.io.SeekableInput
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.SeekableInputMediaData
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * A Telegram file (or anything else behind [StreamBytes]) as mediamp's seekable media.
 *
 * The desktop twin of Android's `TdDataSource`: mpv's demuxer thread calls [StreamInput.read],
 * which blocks until TDLib has the bytes, and a seek simply moves the read position so the next
 * read points TDLib there. Owns [bytes] and closes them once, however many times mediamp calls
 * [close] (it calls it twice).
 *
 * @param prefetchTail fetch the last few megabytes early, for formats that keep their seek index
 *   there (Matroska's Cues), so the first seek does not pay a second trip across the file.
 * @param onClose runs once, after the bytes are closed: where the caller stops the download.
 */
class TdMediaData(
    private val bytes: StreamBytes,
    override val uri: String,
    private val prefetchTail: Boolean,
    private val onClose: () -> Unit = {},
) : SeekableInputMediaData {

    override val extraFiles: MediaExtraFiles = MediaExtraFiles()
    override val options: List<String> = emptyList()

    private val closed = AtomicBoolean(false)

    override fun fileLength(): Long = bytes.size

    override suspend fun createInput(coroutineContext: CoroutineContext): SeekableInput {
        if (closed.get()) throw IOException("Media closed")
        return StreamInput(bytes, coroutineContext[Job], prefetchTail)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { bytes.close() }
        runCatching { onClose() }
    }
}

/**
 * Where the tail fetch starts, or null when the file is too small for it to be worth a trip.
 *
 * Aligned down to a megabyte, the size of the parts Telegram hands out, so the request does not
 * pay for a part it only half wants.
 */
object TailPrefetch {
    const val TAIL_BYTES = 3L * 1024 * 1024
    const val ALIGN_BYTES = 1024L * 1024
    const val MIN_FILE_BYTES = 16L * 1024 * 1024

    fun startFor(size: Long): Long? {
        if (size < MIN_FILE_BYTES) return null
        val raw = size - TAIL_BYTES
        return raw - raw % ALIGN_BYTES
    }

    /** Matroska and WebM keep their Cues at the end; MP4 moves its index to the front or is read at open anyway. */
    fun wanted(fileName: String, mimeType: String): Boolean {
        val name = fileName.lowercase()
        val mime = mimeType.lowercase()
        return name.endsWith(".mkv") || name.endsWith(".webm") || name.endsWith(".mka") ||
            "matroska" in mime || "webm" in mime
    }
}

/**
 * One open read of the file, positioned by [seekTo] and read by mpv's demuxer thread.
 *
 * A read that has to wait blocks that thread, which is what mpv expects of a stream. The wait is a
 * child of the session job mediamp hands [TdMediaData.createInput], and of this input's own job,
 * so stopping the playback or closing the input fails a stuck read at once with an IOException
 * instead of leaving mpv's teardown waiting for TDLib.
 */
class StreamInput(
    private val bytes: StreamBytes,
    sessionJob: Job?,
    prefetchTail: Boolean,
) : SeekableInput {

    private val inputJob = SupervisorJob(sessionJob)

    @Volatile
    private var pos = 0L

    @Volatile
    private var closed = false

    /** The furthest byte read so far, which decides when the head has landed. */
    @Volatile
    private var furthest = 0L

    private class Tail(val start: Long, val data: ByteArray) {
        val end: Long get() = start + data.size
    }

    @Volatile
    private var tail: Tail? = null

    /** Up while the tail is being fetched: slow reads wait on it so they do not drag TDLib away. */
    @Volatile
    private var gate: CompletableDeferred<Unit>? = null

    /** Set when the tail fetch finished, for the tests and the harness log. */
    val tailReady = CompletableDeferred<Boolean>()

    init {
        val start = if (prefetchTail) TailPrefetch.startFor(bytes.size) else null
        if (start == null) {
            tailReady.complete(false)
        } else {
            CoroutineScope(Dispatchers.IO + inputJob).launch { fetchTail(start) }
        }
    }

    override val position: Long get() = pos
    override val size: Long get() = bytes.size
    override val bytesRemaining: Long get() = (bytes.size - pos).coerceAtLeast(0)

    override fun seekTo(position: Long) {
        if (position < 0) throw IOException("Negative seek $position")
        if (closed) throw IOException("Input closed")
        pos = position
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (closed) throw IOException("Input closed")
        if (length == 0) return 0
        repeat(SHORT_READ_RETRIES) {
            val at = pos
            if (at >= bytes.size) return -1

            tail?.let { t ->
                if (at >= t.start && at < t.end) {
                    val n = minOf(length.toLong(), t.end - at).toInt()
                    System.arraycopy(t.data, (at - t.start).toInt(), buffer, offset, n)
                    advance(at + n)
                    return n
                }
            }

            // Fast path: no coroutine and no TDLib round trip, which is nearly every read.
            var available = bytes.cachedAvailable(at)
            if (available == null) {
                val pending = gate
                if (pending != null) {
                    // The tail is on its way; once it lands this position may be in memory.
                    blocking { pending.await() }
                    return@repeat
                }
                available = blocking { bytes.awaitBytesAt(at) }
            }
            val wanted = minOf(length.toLong(), available, bytes.size - at).toInt()
            try {
                val read = bytes.read(at, buffer, offset, wanted)
                advance(at + read)
                return read
            } catch (e: EOFException) {
                // The file on disk was shorter than TDLib said: the window was dropped, so the
                // next turn asks TDLib afresh instead of ending the stream for mpv.
                Logger.w(TAG, "Short read at $at, asking again", e)
            }
        }
        throw IOException("Could not read at $pos")
    }

    override fun close() {
        if (closed) return
        closed = true
        inputJob.cancel()
    }

    private fun advance(to: Long) {
        pos = to
        if (to > furthest) furthest = to
    }

    private suspend fun fetchTail(start: Long) {
        // Let the head land first: the first frame matters more than the index, and mpv reads the
        // header before anything else.
        withTimeoutOrNull(HEAD_WAIT_MS) { while (furthest < HEAD_BYTES) delay(50) }
        val pending = CompletableDeferred<Unit>()
        gate = pending
        var ok = false
        try {
            val length = (bytes.size - start).toInt()
            val data = withTimeoutOrNull(TAIL_TIMEOUT_MS) { bytes.fetch(start, length) }
            if (data != null) {
                tail = Tail(start, data)
                ok = true
            }
            Logger.i(TAG, "Tail prefetch at $start: ${if (ok) "${data!!.size} bytes" else "skipped"}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Tail prefetch failed", e)
        } finally {
            gate = null
            pending.complete(Unit)
            tailReady.complete(ok)
        }
    }

    /**
     * Runs [block] on this thread until it returns, failing with an IOException when the session
     * or this input is cancelled, or when the wait outlives the read timeout.
     */
    private fun <T> blocking(block: suspend () -> T): T {
        val job = Job(inputJob)
        try {
            return runBlocking(job) { withTimeout(READ_TIMEOUT_MS) { block() } }
        } catch (e: TimeoutCancellationException) {
            throw IOException("Telegram did not respond in time", e)
        } catch (e: CancellationException) {
            throw IOException("Read cancelled", e)
        } finally {
            job.complete()
        }
    }

    companion object {
        private const val TAG = "StreamInput"

        /** A little above [com.tmplayer.player.TdByteWindow.STALL_TIMEOUT_MS], which decides first. */
        const val READ_TIMEOUT_MS = 70_000L

        const val HEAD_BYTES = 1024L * 1024
        const val HEAD_WAIT_MS = 4_000L
        const val TAIL_TIMEOUT_MS = 15_000L
        private const val SHORT_READ_RETRIES = 3
    }
}
