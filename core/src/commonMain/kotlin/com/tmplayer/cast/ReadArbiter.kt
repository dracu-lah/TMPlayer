package com.tmplayer.cast

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * One reader of [inner] at a time, however many readers are open on it (PR3).
 *
 * TDLib keeps ONE active download per file, and the last request wins: two readers of one
 * Telegram file at different places (VLC and MX Player read the index at the end of an MKV or
 * MP4 on one connection while the body comes on another) would point its download back and forth
 * and get neither. So every reader opened here shares a single reader of [inner]:
 *
 *  - Reads are serialised: one at a time goes to [inner], so TDLib sees one window.
 *  - Sequential reads coalesce: a read where the shared reader stands, or a short hop ahead of it
 *    (up to [skipLimit]), goes on with it instead of opening [inner] again.
 *  - Last request wins: the reader opened last (a new HTTP request is a new reader; a seek is a
 *    new request) owns the window. An older reader whose read would move the window waits while
 *    the owner is busy, that is while the owner read in the last [handoverMs] (counted from its
 *    open before its first read). So the index read at the end finishes in one go and the body
 *    then carries on, instead of the two taking turns 64 KB at a time. An owner that stops
 *    reading (a request the player left open and no longer reads) holds the others up by
 *    [handoverMs] once, then they go on while it stays quiet.
 *  - Nobody starves: an older reader that has waited [maxWaitMs] in one read takes the window
 *    for that read anyway, and when the owner closes, the newest reader still open owns it.
 *  - After [idleMs] with no read, [sweep] closes the shared reader so TDLib's download lets go
 *    during a long pause; the next read opens [inner] again at its own reader's place.
 *
 * A read of [inner] is never interrupted from another thread (a Media3 data source is not made
 * for that), so a new owner waits for the read in progress, one buffer at most, before it moves
 * the window. A stall or a FLOOD_WAIT is ridden out inside the read that met it (wrap [inner] in
 * a [RecoveringCastSource]); a read that fails drops the shared reader, so the next one opens
 * afresh, and the failure goes to the reader that made it alone.
 */
class ReadArbiter(
    private val inner: CastSource,
    val handoverMs: Long = HANDOVER_MS,
    val maxWaitMs: Long = MAX_WAIT_MS,
    val idleMs: Long = IDLE_MS,
    val skipLimit: Long = SKIP_LIMIT,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : CastSource {

    override val length: Long get() = inner.length

    override fun askAgain(position: Long) = inner.askAgain(position)

    private val lock = Object()

    /** The readers open now, oldest first; the last one owns the window. */
    private val open = ArrayList<Reader>()

    private var shared: CastReader? = null
    private var sharedAt = 0L

    /** The reader whose read is with [inner] now, or null. */
    private var busy: Reader? = null

    /**
     * The reader that goes next when [busy] finishes: the first that was free to go but found
     * [inner] busy (the owner, or an older reader whose wait ran out), so neither can be beaten
     * to the lock for ever by a reader that just finished and reads again at once.
     */
    private var next: Reader? = null

    private var lastReadMs = clock()

    @Volatile
    private var closed = false

    /** How many times [inner] was opened: a window moved or reopened, for the tests and the log. */
    val innerOpens = AtomicInteger()

    /** Whether a reader of [inner] is open now. */
    val holdsInner: Boolean get() = synchronized(lock) { shared != null }

    override fun open(position: Long): CastReader {
        if (position < 0) throw IOException("Negative position $position")
        synchronized(lock) {
            if (closed) throw IOException("Stream closed")
            val reader = Reader(position, clock())
            open += reader
            lock.notifyAll()
            return reader
        }
    }

    /**
     * Closes the shared reader once nothing has read for [idleMs]; true when it did. Run now and
     * then by whoever owns this ([LoopbackStream]'s timer).
     */
    fun sweep(): Boolean {
        val idle = synchronized(lock) {
            if (busy != null || shared == null || clock() - lastReadMs < idleMs) return false
            shared.also { shared = null }
        }
        runCatching { idle?.close() }
        return true
    }

    /** Closes every reader; reads after this fail. */
    fun close() {
        val drop = synchronized(lock) {
            closed = true
            open.forEach { it.closed = true }
            open.clear()
            next = null
            lock.notifyAll()
            // A read in progress closes the shared reader itself when it comes back.
            if (busy == null) shared.also { shared = null } else null
        }
        runCatching { drop?.close() }
    }

    private fun owner(): Reader? = open.lastOrNull()

    /** Whether [reader] may take [inner] now; called with the lock held and nobody [busy]. */
    private fun mayTake(reader: Reader, waitedFrom: Long): Boolean {
        val owner = owner()
        if (owner == null || owner === reader) return true
        if (shared != null && sharedAt == reader.at) return true
        if (clock() - owner.lastReadMs >= handoverMs) return true
        return clock() - waitedFrom >= maxWaitMs
    }

    inner class Reader internal constructor(start: Long, opened: Long) : CastReader {

        /** Where this reader's next byte comes from. */
        @Volatile
        var at: Long = start
            internal set

        @Volatile
        internal var lastReadMs: Long = opened

        @Volatile
        internal var closed = false

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (at >= inner.length) return -1
            take()
            var failed = false
            try {
                val r = positioned()
                val n = r.read(buffer, offset, length)
                if (n > 0) {
                    at += n
                    sharedAt += n
                }
                return n
            } catch (e: IOException) {
                failed = true
                throw e
            } catch (e: RuntimeException) {
                failed = true
                throw IOException("Read at $at failed: ${e.message}", e)
            } finally {
                release(failed)
            }
        }

        /** Waits for [inner] to be this reader's, by the rules in [ReadArbiter]. */
        private fun take() {
            val waitedFrom = clock()
            synchronized(lock) {
                while (true) {
                    if (closed || this@ReadArbiter.closed) {
                        if (next === this) next = null
                        throw ReaderClosedException()
                    }
                    val free = mayTake(this, waitedFrom)
                    val turn = next == null || next === this
                    if (busy == null && free && turn) break
                    if (free && next == null) next = this
                    try {
                        lock.wait(WAIT_STEP_MS)
                    } catch (_: InterruptedException) {
                        if (next === this) next = null
                        Thread.currentThread().interrupt()
                        throw InterruptedIOException("Cancelled")
                    }
                }
                if (next === this) next = null
                busy = this
            }
        }

        private fun release(failed: Boolean) {
            val drop = synchronized(lock) {
                busy = null
                val now = clock()
                lastReadMs = now
                this@ReadArbiter.lastReadMs = now
                lock.notifyAll()
                if (failed || this@ReadArbiter.closed) shared.also { shared = null } else null
            }
            runCatching { drop?.close() }
        }

        /** The shared reader standing at [at], moved or opened there if need be; only while [busy]. */
        private fun positioned(): CastReader {
            shared?.let { s ->
                if (sharedAt == at) return s
                val gap = at - sharedAt
                if (gap in 1..skipLimit && skip(s, gap)) return s
                shared = null
                runCatching { s.close() }
            }
            val fresh = inner.open(at)
            innerOpens.incrementAndGet()
            shared = fresh
            sharedAt = at
            return fresh
        }

        /** Reads and drops [gap] bytes of [s]; false at an early end of the file. */
        private fun skip(s: CastReader, gap: Long): Boolean {
            val scratch = ByteArray(minOf(gap, SKIP_BUFFER.toLong()).toInt())
            var left = gap
            while (left > 0) {
                val n = s.read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
                if (n < 0) return false
                sharedAt += n
                left -= n
            }
            return true
        }

        override fun close() {
            synchronized(lock) {
                closed = true
                open.remove(this)
                if (next === this) next = null
                lock.notifyAll()
            }
        }
    }

    /** A read of a reader (or an arbiter) already closed: the request is over, not a failure. */
    class ReaderClosedException : IOException("Closed")

    companion object {
        const val HANDOVER_MS = 500L
        const val MAX_WAIT_MS = 10_000L
        const val IDLE_MS = 30_000L
        const val SKIP_LIMIT = 512L * 1024
        private const val SKIP_BUFFER = 64 * 1024
        private const val WAIT_STEP_MS = 50L
    }
}
