package com.tmplayer.ui.browse

import com.tmplayer.data.Failures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Home's TDLib work, run to a fixed budget (HX8): at most [parallel] fetches at once, each row
 * (by key) queued or running at most once, a failure retried a few times with a growing wait and
 * then left alone, and a flood wait waited out by that row alone while the others go on.
 *
 * Before this a refresh, a post in a followed chat or the line coming back queued every row again
 * behind the ones still waiting, so on an account with many video chats the queue only grew, and
 * Home's searches kept Telegram's flood waits going for as long as the app was open.
 *
 * [stalled] turns true when work is waiting and nothing has come back for [stallMs]: Home then
 * shows what it has and offers Refresh, and the rows that failed are asked again at once.
 */
internal class HomeFetches(
    private val scope: CoroutineScope,
    private val parallel: Int,
    /** Milliseconds on a clock that only goes forward; the tests' virtual one in the tests. */
    private val clock: () -> Long,
    private val stallMs: Long = STALL_MS,
) {
    /** What one fetch came to. */
    sealed class Outcome {
        data object Done : Outcome()

        /** Failed; [waitMs] is the flood wait Telegram named, when it named one. */
        data class Failed(val waitMs: Long = 0) : Outcome()

        companion object {
            /** [failure] as an outcome: a flood wait carries its seconds. */
            fun of(failure: Throwable): Failed =
                Failed((Failures.floodWaitSeconds(failure.message) ?: 0) * 1000L)
        }
    }

    private class Entry {
        var job: Job? = null
        var running = false
        /** Asked again while running: one more run follows, never more. */
        var again = false
        var answeredAt = -1L
        var tries = 0
        var failed = false
        var fetch: (suspend () -> Outcome)? = null
    }

    private val entries = HashMap<String, Entry>()
    private val gate = Semaphore(parallel)
    private var pending = 0
    private var lastProgress = 0L
    private var watchdog: Job? = null

    private val _stalled = MutableStateFlow(false)

    /** True while work waits and nothing has come back for a few seconds. */
    val stalled: StateFlow<Boolean> = _stalled.asStateFlow()

    private val _busy = MutableStateFlow(false)

    /** True while any fetch is queued or running. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Fetches started, for the tests and the log. */
    var started = 0
        private set

    /** Whether [key] has had an answer (or given up) and nothing for it is queued or running. */
    fun settled(key: String): Boolean = entries[key]?.let { it.job == null && (it.answeredAt >= 0 || it.failed) } == true

    fun isFailed(key: String): Boolean = entries[key]?.failed == true

    /** The keys whose last fetch failed for good (after its retries): [retryFailed] asks them again. */
    fun failedKeys(): List<String> = entries.filterValues { it.failed && it.job == null }.keys.toList()

    /**
     * Asks for [key]. A key queued or running is not queued again: with [fresh] one more run is
     * owed after the current one. Without [fresh] a key already answered is left as it is.
     * [minGapMs] keeps a fresh ask at least that long after the key's last answer: it waits for it.
     * [before] runs ahead of the fetch, outside the budget (waiting for playback to stop).
     */
    fun ask(
        key: String,
        fresh: Boolean = false,
        minGapMs: Long = 0,
        before: (suspend () -> Unit)? = null,
        fetch: suspend () -> Outcome,
    ) {
        val entry = entries.getOrPut(key) { Entry() }
        entry.fetch = fetch
        if (entry.job != null) {
            if (fresh && entry.running) entry.again = true
            return
        }
        if (!fresh && (entry.answeredAt >= 0 || entry.failed)) return
        if (fresh) {
            entry.tries = 0
            entry.failed = false
        }
        val wait = if (minGapMs > 0 && entry.answeredAt >= 0) (entry.answeredAt + minGapMs - clock()).coerceAtLeast(0) else 0
        launch(key, entry, wait, before)
    }

    /** Takes [key] as answered without asking: a row drawn from a cache written moments ago. */
    fun answered(key: String) {
        val entry = entries.getOrPut(key) { Entry() }
        if (entry.job == null) entry.answeredAt = clock()
    }

    /** Asks again every key that failed for good, at once: the line came back, or Home stalled. */
    fun retryFailed(): List<String> {
        val keys = failedKeys()
        keys.forEach { key -> entries[key]?.fetch?.let { ask(key, fresh = true, fetch = it) } }
        return keys
    }

    /** Forgets [key] (its row left Home), stopping what is queued for it. */
    fun forget(key: String) {
        val entry = entries.remove(key) ?: return
        if (!entry.running) entry.job?.let { it.cancel(); done(entry) }
    }

    /** Stops everything and forgets every key: for a sign out. */
    fun clear() {
        entries.values.forEach { it.job?.cancel() }
        entries.clear()
        pending = 0
        _busy.value = false
        _stalled.value = false
        watchdog?.cancel()
        watchdog = null
    }

    private fun launch(key: String, entry: Entry, waitMs: Long, before: (suspend () -> Unit)?) {
        pending++
        _busy.value = true
        // A row asked for the first time starts the stall clock afresh: a refetch that has been
        // waiting out its gap is no reason to call the new row slow at once.
        if (pending == 1 || (entry.answeredAt < 0 && !_stalled.value)) lastProgress = clock()
        startWatchdog()
        // Started only once it is recorded: on the main thread's immediate dispatcher a fetch
        // that never suspends would otherwise finish before its job was written down.
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            var outcome: Outcome = Outcome.Failed()
            try {
                if (waitMs > 0) delay(waitMs)
                before?.invoke()
                gate.withPermit {
                    entry.running = true
                    started++
                    outcome = try {
                        entry.fetch?.invoke() ?: Outcome.Done
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        Outcome.of(failure)
                    }
                }
            } finally {
                entry.running = false
            }
            settle(key, entry, outcome, before)
        }
        entry.job = job
        job.invokeOnCompletion { cause -> if (cause != null && entry.job === job) done(entry) }
        job.start()
    }

    private fun settle(key: String, entry: Entry, outcome: Outcome, before: (suspend () -> Unit)?) {
        done(entry)
        lastProgress = clock()
        _stalled.value = false
        if (entries[key] !== entry) return
        when (outcome) {
            Outcome.Done -> {
                entry.answeredAt = clock()
                entry.tries = 0
                entry.failed = false
            }
            is Outcome.Failed -> {
                entry.tries++
                if (entry.tries <= RETRY_WAITS_MS.size && !entry.again) {
                    // Retried by itself, after the flood wait Telegram named or a growing pause,
                    // without holding a place in the budget meanwhile.
                    val wait = maxOf(outcome.waitMs, RETRY_WAITS_MS[entry.tries - 1])
                    launch(key, entry, wait, before)
                    return
                }
                entry.failed = !entry.again
            }
        }
        if (entry.again) {
            entry.again = false
            launch(key, entry, 0, before)
        }
    }

    private fun done(entry: Entry) {
        if (entry.job == null) return
        entry.job = null
        pending = (pending - 1).coerceAtLeast(0)
        if (pending == 0) {
            _busy.value = false
            _stalled.value = false
        }
    }

    /** Watches for a stall while work is pending, and stops by itself once none is. */
    private fun startWatchdog() {
        if (watchdog?.isActive == true) return
        watchdog = scope.launch {
            while (pending > 0) {
                delay(WATCH_TICK_MS)
                // Only a row still missing counts: a refresh of rows on screen taking its time is
                // nobody's wait, and saying "slow" for it would cry wolf.
                val missing = entries.values.any { it.job != null && it.answeredAt < 0 }
                if (missing && clock() - lastProgress >= stallMs && !_stalled.value) {
                    _stalled.value = true
                    // "If it is stuck, after a few seconds show what is there and refresh"
                    // (user, 2026-10-26): what failed is asked again now rather than after its pause.
                    retryFailed()
                }
            }
        }
    }

    companion object {
        /** Nothing back for this long with work waiting: Home shows what it has. */
        const val STALL_MS = 4_000L

        private const val WATCH_TICK_MS = 500L

        /** The pauses before each retry of a failed fetch; past the last it stays failed until asked again. */
        val RETRY_WAITS_MS = longArrayOf(5_000, 20_000, 60_000)
    }
}
