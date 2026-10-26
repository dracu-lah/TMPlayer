package com.tmplayer.cast

import com.tmplayer.data.Td
import com.tmplayer.platform.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicLong

/**
 * How a cast read gets over a Telegram hiccup ([RecoveringCastSource]): how long it keeps trying
 * after the first failure, how long it waits between tries, and what it does to get the bytes
 * moving again.
 *
 * The phone's own player has the same recovery behind its Try again ([Td.retryConnecting] and
 * the stream opened afresh); a TV asking for a segment has nobody to press it, so the server
 * does it for the receiver.
 */
class CastRecovery(
    /** How long after a read first fails it is tried again, before the request fails with it. */
    val budgetMs: Long = BUDGET_MS,
    /** The first wait between tries; each one after it doubles, up to [maxBackoffMs]. */
    val firstBackoffMs: Long = FIRST_BACKOFF_MS,
    val maxBackoffMs: Long = MAX_BACKOFF_MS,
    /** Reopens the connections to Telegram; called once for each stall, not on every try. */
    private val reconnect: () -> Unit = ::reconnectTelegram,
    /** Milliseconds on a clock that only goes forward. */
    val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {

    /**
     * Reopens the connections, at most once every [RECONNECT_GAP_MS]: the picture's segment and
     * a soundtrack's stall together on one hiccup, and one reconnect serves both.
     */
    fun reconnectOnce() {
        val now = clock()
        val last = lastReconnect.get()
        if (last != NEVER && now - last < RECONNECT_GAP_MS) return
        if (!lastReconnect.compareAndSet(last, now)) return
        runCatching(reconnect).onFailure { Logger.w(TAG, "Reconnect failed", it) }
    }

    private val lastReconnect = AtomicLong(NEVER)

    companion object {
        const val BUDGET_MS = 20_000L
        const val FIRST_BACKOFF_MS = 500L
        const val MAX_BACKOFF_MS = 4_000L
        const val RECONNECT_GAP_MS = 15_000L
        private const val NEVER = Long.MIN_VALUE
        private const val TAG = "CastRecovery"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.tmplayer.platform.JobFailures.handler(TAG))

        /** The same reopening of Telegram's connections the player's Try again does. */
        fun reconnectTelegram() {
            Logger.i(TAG, "Reopening the connections to Telegram for a stalled cast read")
            scope.launch { runCatching { Td.retryConnecting() } }
        }
    }
}

/**
 * A [CastSource] whose reads ride out a Telegram stall. A read that fails ("Telegram stopped
 * sending file 2370 at byte 328527945", "did not respond in time") is not handed to the server
 * as it is: the reader is closed, Telegram's connections are reopened, the source is asked for
 * the bytes from that place again ([CastSource.askAgain]), and after a short wait the read
 * starts afresh where it stopped. The waits double from [CastRecovery.firstBackoffMs], and once
 * [CastRecovery.budgetMs] has gone by since the first failure the last error goes up to the
 * server, which fails that request as before. Bytes arriving start the count again, so a slow
 * but moving stream is never cut short.
 *
 * Reopening is what a Media3 data source needs to ask TDLib again (its open requests the
 * download from the place opened at), and what a short read after a Low storage roll needs too.
 * The end of the file, a reader closed by the server (a hang up, a stream taken down) and an
 * interrupted thread are not stalls and go up at once.
 */
class RecoveringCastSource(
    val inner: CastSource,
    private val recovery: CastRecovery = CastRecovery(),
) : CastSource by inner {

    // Opened on the first read, so a stall while opening is ridden out like one while reading.
    override fun open(position: Long): CastReader = Reader(position)

    private inner class Reader(start: Long) : CastReader {
        private var at = start
        private var reader: CastReader? = null

        @Volatile
        private var closed = false

        /** When the stall being ridden out began, or null while bytes are flowing. */
        private var stallSince: Long? = null
        private var backoff = recovery.firstBackoffMs

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            while (true) {
                if (closed) throw IOException("Closed")
                try {
                    val r = reader ?: inner.open(at).also { reader = it }
                    val n = r.read(buffer, offset, length)
                    if (n > 0) {
                        at += n
                        if (stallSince != null) Logger.i(TAG, "Bytes at $at again; the stall is over")
                        stallSince = null
                        backoff = recovery.firstBackoffMs
                    }
                    return n
                } catch (e: IOException) {
                    if (closed || e is EOFException || e is InterruptedIOException) throw e
                    recover(e)
                }
            }
        }

        /** Waits out one failed try, or throws [e] when the budget is spent. */
        private fun recover(e: IOException) {
            val now = recovery.clock()
            val since = stallSince ?: now.also { stallSince = it }
            val spent = now - since
            if (spent >= recovery.budgetMs) {
                Logger.w(TAG, "Giving up on the read at $at after ${spent}ms: ${e.message}")
                throw e
            }
            Logger.i(TAG, "Read at $at failed (${e.message}); trying again in ${backoff}ms")
            reader?.let { runCatching { it.close() } }
            reader = null
            // Once a stall: the connections take a few seconds to come back, and reopening them
            // on every try would only start that again.
            if (spent == 0L) recovery.reconnectOnce()
            runCatching { inner.askAgain(at) }.onFailure { Logger.w(TAG, "Asking again at $at failed", it) }
            val wait = minOf(backoff, (recovery.budgetMs - spent).coerceAtLeast(1))
            try {
                recovery.sleep(wait)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Cancelled")
            }
            backoff = minOf(backoff * 2, recovery.maxBackoffMs)
        }

        override fun close() {
            closed = true
            reader?.let { runCatching { it.close() } }
            reader = null
        }
    }

    private companion object {
        const val TAG = "CastRecovery"
    }
}
