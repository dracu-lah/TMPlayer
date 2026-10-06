package com.tmplayer.online

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Spaces calls at least [minGapMs] apart: 200 ms is OpenSubtitles' 5 requests a second, 1000 ms
 * its one sign in a second. Callers queue in order rather than being refused.
 */
class RateLimiter(
    private val minGapMs: Long,
    private val now: () -> Long,
    private val sleep: suspend (Long) -> Unit,
) {
    private val lock = Mutex()
    private var last: Long? = null

    /** Waits until a request may go, and books the slot. */
    suspend fun acquire() {
        lock.withLock {
            val previous = last
            if (previous != null) {
                val wait = previous + minGapMs - now()
                if (wait > 0) sleep(wait)
            }
            last = now()
        }
    }
}
