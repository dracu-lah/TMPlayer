package com.tmplayer.cast

import com.tmplayer.platform.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One transcode's input: the video, what it is, the route's target, and where in the video to start. */
class TranscodeJob(
    val source: CastSource,
    val probe: MediaProbe,
    val route: CastRoute.Transcode,
    val startMs: Long,
    val name: String,
)

/**
 * Makes a transcode's stream: a platform's own ([EngineTranscoder] around Android's Transformer,
 * the desktop's ffmpeg process). [write] blocks on the cast server's thread for the request.
 */
interface Transcoder {
    /**
     * Writes [job]'s stream to [out] until its end. Throws [IOException] when it cannot go on (no
     * encoder, a decoder refusing the file, the receiver hanging up, [cancel]). [made] hears how
     * much of the video is made, in milliseconds from the job's start.
     */
    fun write(job: TranscodeJob, out: OutputStream, made: (Long) -> Unit)

    /** Stops a [write] in progress, from any thread; it returns soon after, its process or codecs let go. */
    fun cancel()
}

/**
 * The platform's pipeline, started and left to run on its own threads: Android's Transformer
 * (MediaCodec decoders, GL, the hardware H.264 and AAC encoders, a live fragmented MP4 muxer on
 * [start]'s stream), or a fake in the tests. MediaCodec stays behind this, so [EngineTranscoder]'s
 * waiting, cancelling and failure handling are tested without a device.
 */
interface TranscodeEngine {
    /**
     * Starts [job], muxed into [out]; returns at once. [made] hears the media time written, in
     * microseconds from the job's start; [done] is called once, with null at the end of the video
     * or with what went wrong.
     */
    fun start(job: TranscodeJob, out: OutputStream, made: (Long) -> Unit, done: (Throwable?) -> Unit)

    /** Stops what [start] began, from any thread; [done] still comes. */
    fun cancel()
}

/** A [Transcoder] that drives a [TranscodeEngine], one engine per [write]. */
class EngineTranscoder(
    private val engineFactory: () -> TranscodeEngine,
    /** How long a cancelled engine is given to say it is done before [write] returns anyway. */
    private val cancelWaitMs: Long = 5_000,
) : Transcoder {

    private val engine = AtomicReference<TranscodeEngine?>(null)
    private val cancelled = AtomicBoolean(false)

    override fun write(job: TranscodeJob, out: OutputStream, made: (Long) -> Unit) {
        if (cancelled.get()) throw IOException("Cancelled")
        val latch = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val e = engineFactory()
        engine.set(e)
        try {
            e.start(job, out, { us -> made(us / 1000) }) { error ->
                failure.compareAndSet(null, error)
                latch.countDown()
            }
            while (!latch.await(POLL_MS, TimeUnit.MILLISECONDS)) {
                if (cancelled.get()) {
                    e.cancel()
                    if (!latch.await(cancelWaitMs, TimeUnit.MILLISECONDS)) Logger.w(TAG, "Engine did not stop in $cancelWaitMs ms")
                    throw IOException("Cancelled")
                }
            }
            if (cancelled.get()) throw IOException("Cancelled")
            failure.get()?.let { throw it as? IOException ?: IOException(it.message ?: it::class.simpleName, it) }
        } catch (i: InterruptedException) {
            e.cancel()
            Thread.currentThread().interrupt()
            throw IOException("Interrupted", i)
        } finally {
            engine.set(null)
        }
    }

    override fun cancel() {
        cancelled.set(true)
        engine.get()?.cancel()
    }

    private companion object {
        const val TAG = "EngineTranscoder"
        const val POLL_MS = 200L
    }
}

/**
 * One transcode at a time, whatever asks: a new stream (a seek, a reload, another soundtrack)
 * stops the one before it. Keeps [progress] for the screen, and stops a transcode past the battery
 * and heat limits ([TranscodeGuard]) with the reason said.
 */
class TranscodeSlot(
    private val factory: () -> Transcoder,
    private val health: () -> DeviceHealth = { DeviceHealth() },
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val checkEveryMs: Long = TranscodeGuard.CHECK_EVERY_MS,
) {
    private class Running(val transcoder: Transcoder) {
        @Volatile var stop: TranscodeStop? = null
    }

    private val _progress = MutableStateFlow<TranscodeProgress?>(null)

    /** The running or last transcode; null before any, or after [clear]. */
    val progress: StateFlow<TranscodeProgress?> = _progress.asStateFlow()

    @Volatile
    private var current: Running? = null

    val running: Boolean get() = current != null

    /**
     * Runs [job] into [out] until its end, a stop, or a newer job. Throws [IOException] when it
     * failed or was stopped; [progress] says which.
     */
    fun run(job: TranscodeJob, out: OutputStream) {
        val me = Running(factory())
        val before = synchronized(this) { current.also { current = me } }
        // Replaced, not stopped by the viewer: its end says nothing on screen.
        before?.transcoder?.cancel()
        // A guard limit already passed: not started at all.
        TranscodeGuard.check(health())?.let { stop ->
            publish(me, TranscodeProgress(job.startMs, job.startMs, job.probe.durationMs, 0f, stop = stop))
            synchronized(this) { if (current === me) current = null }
            throw IOException("Not started: $stop")
        }
        val started = clock()
        var lastCheck = started
        var slowSince: Long? = null
        val watched = WatchedOutput(out)
        publish(me, TranscodeProgress(job.startMs, job.startMs, job.probe.durationMs, 0f))
        try {
            me.transcoder.write(job, watched) { madeMs ->
                val now = clock()
                val wall = (now - started).coerceAtLeast(1)
                val made = madeMs.coerceAtLeast(0)
                val speed = made.toFloat() / wall
                if (now - lastCheck >= checkEveryMs) {
                    lastCheck = now
                    TranscodeGuard.check(health())?.let { stop ->
                        Logger.i(TAG, "Stopping the transcode: $stop")
                        me.stop = stop
                        me.transcoder.cancel()
                    }
                }
                // The first seconds are the pipeline filling up, not its speed.
                slowSince = if (wall >= WARM_UP_MS && speed < 1f) slowSince ?: now else null
                val slow = slowSince?.let { now - it >= TranscodeGuard.SLOW_AFTER_MS } == true
                publish(me, TranscodeProgress(job.startMs, job.startMs + made, job.probe.durationMs, speed, slow = slow))
            }
            val stop = me.stop
            update(me) { it.copy(done = stop == null, stop = stop) }
            if (stop != null) throw IOException("Stopped: $stop")
        } catch (e: IOException) {
            val stop = me.stop ?: if (watched.failed) TranscodeStop.ReceiverGone else TranscodeStop.Failed
            update(me) { it.copy(stop = stop, message = it.message ?: e.message) }
            throw e
        } catch (e: RuntimeException) {
            // A codec's IllegalStateException and the like: a failed transcode, not a dead server thread.
            update(me) { it.copy(stop = me.stop ?: TranscodeStop.Failed, message = e.message) }
            throw IOException(e.message ?: "Transcode failed", e)
        } finally {
            synchronized(this) { if (current === me) current = null }
        }
    }

    /** The viewer's Stop converting: the running transcode ends, said as cancelled. */
    fun cancel() {
        val r = current ?: return
        r.stop = TranscodeStop.Cancelled
        r.transcoder.cancel()
    }

    /** Cancels and forgets: the cast ended, nothing is shown any longer. */
    fun clear() {
        cancel()
        _progress.value = null
    }

    private fun publish(me: Running, p: TranscodeProgress) {
        if (current === me) _progress.value = p
    }

    private fun update(me: Running, change: (TranscodeProgress) -> TranscodeProgress) {
        if (current !== me) return
        _progress.value?.let { _progress.value = change(it) }
    }

    /** [out], remembering whether a write to it failed: the receiver hung up, rather than the pipeline. */
    private class WatchedOutput(private val out: OutputStream) : OutputStream() {
        @Volatile var failed = false

        override fun write(b: Int) = guard { out.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) = guard { out.write(b, off, len) }
        override fun flush() = guard { out.flush() }

        private inline fun guard(block: () -> Unit) {
            try {
                block()
            } catch (e: IOException) {
                failed = true
                throw e
            }
        }
    }

    private companion object {
        const val TAG = "TranscodeSlot"
        const val WARM_UP_MS = 5_000L
    }
}
