package com.tmplayer.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/**
 * Smart downloads, the runner (WP-83): one pass over the shows with "Keep the next episodes" on,
 * each planned by [KeepNextPlanner] and queued through the app's own download path ([Host.queue],
 * which plans against the disk again and gives the sentence), for the profile open when the pass
 * started and no other.
 *
 * When it runs: only when asked ([pass]): a video marked watched, the connection changing, the
 * switch turned on. Nothing runs by itself, nothing polls, and a pass asked for while one runs is
 * folded into it ([pending]), so a burst of triggers costs one pass.
 *
 * Bounds, so it never loops and never floods Telegram:
 * - At most [MAX_SHOWS_PER_PASS] shows and [MAX_QUEUED_PER_PASS] episodes queued a pass.
 * - The episodes come from what this device already listed ([Host.episodes]: the chat's kept
 *   listing); a show whose chat is not listed here is skipped, never fetched for.
 * - A FLOOD_WAIT is waited out in the call that got it, once a pass and at most
 *   [MAX_FLOOD_WAIT_MS]; a longer one, or a second, ends the pass ([Stop.FloodWait]).
 * - A profile switch ends the pass at the next show ([Stop.Switched]): nothing is queued for the
 *   profile that opened since. The app runs the pass on [Accounts.profileScope], which the switch
 *   cancels too.
 * - Any other failure of one show is logged and the next show goes on: outside data never stops
 *   the rest.
 *
 * What each show's plan said is kept in [reports] for the Downloads row (WP-84), for the open
 * profile only: a switch empties it.
 */
class KeepNextRunner(private val host: Host) {

    /** What the runner reads and does; each call may fail, none is trusted to be quick. */
    interface Host {
        /** The open profile now. */
        val stamp: AccountStamp

        /** The shows with the switch on, for the open profile. */
        suspend fun shows(): Set<KeepNextShow>

        /** [show]'s episodes as this device last listed them, with the show's title; null when its chat is not listed here. */
        suspend fun episodes(show: KeepNextShow): Listed?

        /** The free space downloads go to, and whether the connection is metered. */
        suspend fun conditions(): Conditions

        /** Queues [items] for [show] (named [title] in Downloads) through the app's download path; the number it took. */
        suspend fun queue(show: KeepNextShow, title: String, items: List<MediaItem>): Int

        /** Waits [ms]; a test passes time by hand. */
        suspend fun sleep(ms: Long)

        fun log(line: String) = Unit
    }

    data class Listed(val title: String, val episodes: List<KeepNextPlanner.Episode>)

    data class Conditions(val freeBytes: Long, val metered: Boolean)

    /** Why a pass ended before its last show. */
    enum class Stop { Switched, FloodWait, Budget }

    /** One show's outcome, for the Downloads row: what the planner said and how many were queued. */
    data class Report(val show: KeepNextShow, val title: String, val plan: KeepNextPlanner.Plan, val queued: Int)

    data class Summary(val shows: Int = 0, val queued: Int = 0, val stopped: Stop? = null)

    private val lock = Mutex()

    @kotlin.concurrent.Volatile
    private var pending = false

    private val _reports = MutableStateFlow<Map<String, Report>>(emptyMap())

    /** The last pass's outcome per show id ([KeepNextShow.id]), for the open profile. */
    val reports: StateFlow<Map<String, Report>> = _reports.asStateFlow()

    private val _running = MutableStateFlow(false)

    /** A pass runs now: the Downloads row's status line says so (WP-84, the work status line of WP-91). */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** The profile switched: what was said about the last one goes. */
    fun forget() {
        _reports.value = emptyMap()
    }

    /**
     * One pass for the profile open now. A call while a pass runs marks one more to follow and
     * returns at once; the running pass then goes round once more, so the last trigger is never
     * lost and two never run side by side.
     */
    suspend fun pass(): Summary? {
        if (!lock.tryLock()) {
            pending = true
            return null
        }
        _running.value = true
        try {
            var summary: Summary
            var rounds = 0
            do {
                pending = false
                summary = once()
                rounds++
            } while (pending && summary.stopped == null && rounds < MAX_ROUNDS)
            return summary
        } finally {
            _running.value = false
            lock.unlock()
        }
    }

    private suspend fun once(): Summary {
        val owner = host.stamp
        val shows = runCatching { host.shows() }.getOrElse {
            if (it is CancellationException) throw it
            host.log("Keep next: the shows could not be read (${it::class.simpleName})")
            return Summary()
        }
        if (shows.isEmpty()) {
            _reports.value = emptyMap()
            return Summary()
        }
        val reports = LinkedHashMap<String, Report>()
        var queued = 0
        var waited = false
        var done = 0
        var stopped: Stop? = null
        for (show in shows) {
            if (host.stamp != owner) {
                stopped = Stop.Switched
                break
            }
            if (done >= MAX_SHOWS_PER_PASS || queued >= MAX_QUEUED_PER_PASS) {
                stopped = Stop.Budget
                break
            }
            done++
            val listed = runCatching { host.episodes(show) }.getOrElse {
                if (it is CancellationException) throw it
                host.log("Keep next: show ${show.id.hashCode()} could not be read (${it::class.simpleName})")
                null
            } ?: continue
            val conditions = runCatching { host.conditions() }.getOrElse {
                if (it is CancellationException) throw it
                Conditions(freeBytes = 0L, metered = true)
            }
            val plan = KeepNextPlanner.plan(
                KeepNextPlanner.Input(
                    episodes = listed.episodes,
                    count = KeepNextPlanner.DEFAULT_COUNT,
                    freeBytes = conditions.freeBytes,
                    metered = conditions.metered,
                    wifiOnly = true,
                    // Remove after watching already takes a watched download away, kept for you or not.
                    removeAfterWatching = false,
                ),
            )
            val wanted = plan.queue.take(MAX_QUEUED_PER_PASS - queued)
            var took = 0
            if (wanted.isNotEmpty()) {
                var tries = 0
                while (true) {
                    if (host.stamp != owner) {
                        stopped = Stop.Switched
                        break
                    }
                    val result = runCatching { host.queue(show, listed.title, wanted) }
                    val error = result.exceptionOrNull()
                    if (error is CancellationException) throw error
                    if (error == null) {
                        took = result.getOrDefault(0).coerceIn(0, wanted.size)
                        break
                    }
                    val seconds = Failures.floodWaitSeconds(error.message)
                    if (seconds == null) {
                        host.log("Keep next: queueing failed (${error::class.simpleName})")
                        break
                    }
                    val ms = seconds * 1_000L
                    if (waited || tries > 0 || ms > MAX_FLOOD_WAIT_MS) {
                        host.log("Keep next: Telegram asked to wait $seconds s, the pass ends")
                        stopped = Stop.FloodWait
                        break
                    }
                    waited = true
                    tries++
                    host.log("Keep next: waiting out a flood wait of $seconds s")
                    host.sleep(ms)
                }
            }
            queued += took
            reports[show.id] = Report(show, listed.title, plan, took)
            if (stopped != null) break
        }
        // A pass for a profile that is no longer open says nothing about the one that is.
        if (host.stamp == owner) _reports.value = reports
        if (queued > 0) host.log("Keep next: queued $queued for ${reports.size} shows")
        return Summary(shows = done, queued = queued, stopped = stopped)
    }

    companion object {
        /**
         * The Downloads row's line for [report] (WP-84): why nothing more is kept, with the numbers,
         * or that the next episodes are here or coming.
         */
        fun detail(report: Report, bytes: (Long) -> String): String {
            val skip = report.plan.headline
            return when (skip?.reason) {
                null -> com.tmplayer.i18n.L.downloadsKeptReady(count = report.plan.kept.size + report.plan.queue.size)
                KeepNextPlanner.Reason.WaitingForWifi -> com.tmplayer.i18n.L.downloadsKeptWaitingWifi
                KeepNextPlanner.Reason.NoSpace -> com.tmplayer.i18n.L.downloadsKeptNoSpace(needed = bytes(skip.neededBytes), free = bytes(skip.freeBytes))
                KeepNextPlanner.Reason.Restricted -> com.tmplayer.i18n.L.downloadsKeptRestricted
                KeepNextPlanner.Reason.SizeUnknown -> com.tmplayer.i18n.L.downloadsKeptSizeUnknown
                KeepNextPlanner.Reason.NothingNext -> com.tmplayer.i18n.L.downloadsKeptNothingNext
            }
        }

        const val MAX_SHOWS_PER_PASS = 20
        const val MAX_QUEUED_PER_PASS = 10
        const val MAX_FLOOD_WAIT_MS = 5 * 60_000L

        /** A pass asked for during a pass runs once more, at most this many times in a row. */
        private const val MAX_ROUNDS = 3
    }
}
