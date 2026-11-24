package com.tmplayer.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * When the sync file may be read and written ([ResumeSync]), apart from how: the few-minutes cap,
 * the extra write on leaving the player, nothing in the background on a weak device, and the
 * manual and first runs that wait for nobody. Pure of Telegram and of the stores, so the rules are
 * tested with a fake [job] and a virtual clock.
 *
 * The rules:
 * - A run asked for by the viewer ([Why.Manual], [Why.TurnedOn]) goes at once, whatever else holds.
 * - Every other run goes at most once in [MIN_GAP_MS] after the last run, and after
 *   the [Why] 's own settle time ([Why.settleMs]), whichever comes later. Asks made while one
 *   waits replace it, so a burst of saves is one write.
 * - Leaving the player ([Why.LeftPlayer]) has a shorter gap, [LEAVE_GAP_MS]: the place just saved
 *   goes out soon, still never more than twice a minute.
 * - On a low end device ([lowEnd]) nothing runs while the app is in the background ([foreground]
 *   false): the run waits, and [foregrounded] picks it up when the app is back.
 */
class SyncScheduler(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val foreground: () -> Boolean,
    private val lowEnd: Boolean,
    /** When this device last ran a sync; 0 before. Survives a restart where the caller keeps it. */
    private val lastRunAt: () -> Long,
    /** Runs a sync, which writes the file when something changed; false when it did nothing at all (sync is off). Never throws. */
    private val job: suspend (Why) -> Boolean,
    private val onRan: (Long) -> Unit = {},
    /**
     * True when runs nobody asked for are to be left alone (the last three runs failed, see
     * [SyncStreak]). A run the viewer asked for ([Why.Manual], [Why.TurnedOn]) still goes. Never throws.
     */
    private val stopped: suspend () -> Boolean = { false },
) {
    enum class Why(val settleMs: Long) {
        /** The app started, or Telegram connected. */
        Start(START_DELAY_MS),

        /** The player saved a place, or a list changed: let the saves settle first. */
        Settled(PUSH_SETTLE_MS),

        /** The player is closing: the place is final. */
        LeftPlayer(LEAVE_SETTLE_MS),

        /** The viewer turned it on, or asked for Sync now. */
        TurnedOn(0L),
        Manual(0L),
    }

    private var pending: Job? = null
    private var held: Why? = null

    /** How many runs [job] has been given: for tests and logs. */
    var runs: Int = 0
        private set

    /** Asks for a sync. A newer ask replaces one still waiting. */
    fun ask(why: Why) {
        pending?.cancel()
        held = null
        pending = scope.launch {
            val viewerAsked = why == Why.TurnedOn || why == Why.Manual
            delay(why.settleMs)
            // Time passed: the clock and the app's state may have changed, so look again.
            while (true) {
                if (lowEnd && !foreground() && !viewerAsked) {
                    held = why
                    return@launch
                }
                val more = if (viewerAsked) 0L else gapLeft(why)
                if (more <= 0L) break
                delay(more)
            }
            // Looked at last, after the waits, so a run that failed meanwhile stops the ones queued behind it.
            if (!viewerAsked && stopped()) return@launch
            // Only the wait is cancelled by a newer ask: a sync under way finishes.
            withContext(NonCancellable) {
                runs++
                // A run that was cancelled (a switch) did not run: its pause does not start.
                val ran = try {
                    job(why)
                } catch (e: CancellationException) {
                    false
                } catch (e: Exception) {
                    true
                }
                if (ran) onRan(clock())
            }
        }
    }

    /** The app came back to the front: a run held in the background goes on. */
    fun foregrounded() {
        val why = held ?: return
        ask(why)
    }

    /** How much of the pause after the last write a run for [why] still has to wait. */
    private fun gapLeft(why: Why): Long {
        val now = clock()
        val gap = if (why == Why.LeftPlayer) LEAVE_GAP_MS else MIN_GAP_MS
        val last = lastRunAt()
        return if (last in 1..now) (gap - (now - last)).coerceAtLeast(0L) else 0L
    }

    companion object {
        const val START_DELAY_MS = 8_000L
        const val PUSH_SETTLE_MS = 20_000L
        const val LEAVE_SETTLE_MS = 2_000L

        /** Writes come at least this far apart on their own: a few minutes. */
        const val MIN_GAP_MS = 3 * 60 * 1000L

        /** On leaving the player: twice a minute at most. */
        const val LEAVE_GAP_MS = 30 * 1000L
    }
}

/**
 * [call], with a FLOOD_WAIT waited out by this call alone, up to [maxWaitS] seconds and [tries]
 * times; any other answer, or a longer wait, is handed back as it came. [floodSeconds] reads the
 * wait from an answer (null when it is not one). [pause] is `delay`, replaced in tests.
 */
suspend fun <T> waitingOutFlood(
    tries: Int,
    maxWaitS: Int,
    floodSeconds: (T) -> Int?,
    pause: suspend (Long) -> Unit = { delay(it) },
    call: suspend () -> T,
): T {
    repeat(tries - 1) {
        val result = call()
        val wait = floodSeconds(result) ?: return result
        if (wait > maxWaitS) return result
        pause(wait * 1000L + 250L)
    }
    return call()
}
