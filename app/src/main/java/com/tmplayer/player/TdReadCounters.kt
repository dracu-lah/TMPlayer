package com.tmplayer.player

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * What the data source spent on TDLib since the last reading (reliability plan L1): milliseconds
 * blocked waiting for bytes, and opens (a reopen or a seek makes one). The recorder takes both
 * every few seconds, so a slow line (long waits, underruns) reads differently from a slow decoder
 * (no waits, dropped frames).
 *
 * One process-wide object because Media3 makes its data sources itself and several can be alive at
 * once; the counters are atomics, the loading thread adds with one `addAndGet` and the recorder's
 * [take] swaps them back to zero. Nothing here holds a title, a file id or a path.
 */
object TdReadCounters {

    /** The two figures [take] hands over. */
    data class Reading(val waitMs: Long, val opens: Int) {
        companion object {
            val NONE = Reading(0L, 0)
        }
    }

    /** One wait is capped here, so a clock that jumped cannot fill a sample. */
    const val MAX_ONE_WAIT_MS = 3_600_000L

    private val waited = AtomicLong()
    private val opened = AtomicInteger()

    /** A read or an open that blocked for [ms] waiting on TDLib. A negative or absurd value is held to range. */
    fun waited(ms: Long) {
        if (ms > 0) waited.addAndGet(ms.coerceAtMost(MAX_ONE_WAIT_MS))
    }

    /** The source was opened: the first time, a reopen after a close, or a seek. */
    fun opened() {
        opened.incrementAndGet()
    }

    /** What happened since the last [take], and starts the next count from zero. */
    fun take(): Reading = Reading(waited.getAndSet(0L), opened.getAndSet(0))

    /** Forgets what was counted: a new playback starts from nothing. */
    fun reset() {
        waited.set(0L)
        opened.set(0)
    }
}
