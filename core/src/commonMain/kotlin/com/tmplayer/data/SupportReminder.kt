package com.tmplayer.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Asking for support: the one switch that turns every donation link off, and the rules for the
 * Buy Me a Coffee window and the rare notification that points at it.
 *
 * Both wait for real use, never the first day, and both stop for good at "Don't show again" (or
 * the older "I already support", which also turns the About heading into a thank you):
 *
 * - The window, after [MIN_WATCHES] finished videos, or [MIN_DAYS] days of use with at least one
 *   finished video, then at most once every [GAP_DAYS] days. Only at a good moment: back on the
 *   chat list or Downloads after a video was watched to the end, or after a download finished.
 *   Never in the player, during the setup or sign in, while another window is up, or twice in one
 *   run of the app. "Maybe later" and closing it both count as asked.
 * - The notification, on a phone and the desktop only, at most once every [NUDGE_GAP_DAYS] days,
 *   never within [NUDGE_AFTER_ASK_DAYS] days of the window, and only while the "Sponsor
 *   reminders" switch is on (on from the start, CP76; the viewer can turn it off). Its first rung asks only
 *   for a star on GitHub ([NudgeRung.Star]), which costs nothing; the ones after it are "Don't
 *   forget to sponsor" ([NudgeRung.Coffee]). Once Star on GitHub has been pressed, here or on
 *   the window, no star is asked for again. Its buttons are Buy me a coffee, Star on GitHub (until
 *   it has been pressed) and Not now; pressing the notification itself opens the window.
 *
 * An install that saw the old three card ladder to its end was told it would not be asked again,
 * and that holds: [Counters.ladderDone] keeps it quiet.
 */
object SupportReminder {

    /**
     * Every donation link, the window and the notification, together. On in every build that
     * exists today. The `play` flavor planned for Google Play (CP35) turns it off at startup, since
     * Play does not allow donation links outside its own billing, and then the Settings row, the
     * About group, the window and the notification all go with it. This is the only gate.
     */
    @Volatile
    var enabled: Boolean = true

    /**
     * Shows the window whatever the counters say, for screenshots and for checking it by eye: a
     * debug build's `--ei support_rung N`, the promo fixture's same extra, or the desktop's
     * `-Dtmplayer.supportRung=N`. 0 is off, any other number is on (the old ladder's rungs are
     * gone). Still nothing when [enabled] is off, and a forced window leaves the counters alone.
     */
    @Volatile
    var forcedRung: Int = 0

    /** The older switch: `--ez support_reminder true` forces the window. */
    var forced: Boolean
        get() = forcedRung > 0
        set(value) {
            forcedRung = if (value) 1 else 0
        }

    const val MIN_WATCHES = 5
    const val MIN_DAYS = 3
    const val GAP_DAYS = 14
    const val NUDGE_GAP_DAYS = 30
    const val NUDGE_AFTER_ASK_DAYS = 7

    /** The old ladder's length: an install that was asked this often heard its last ask. */
    const val LADDER_ASKS = 3

    /** Which of the two wordings kept in en.json is shown ("a" or "b"). */
    const val COPY_VARIANT = "a"

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val HOUR_MS = 60L * 60 * 1000

    /** The most watch time one progress report may add: a report is a few seconds of playing. */
    private const val MAX_STEP_MS = 60_000L
    private const val RECENT_LIMIT = 16

    /** What the store remembers. [firstSeenAt] is 0 until the first count. */
    data class Counters(
        val firstSeenAt: Long = 0L,
        val completedWatches: Int = 0,
        val watchTimeMs: Long = 0L,
        /** How many cards the old ladder showed, 0 to [LADDER_ASKS]. */
        val asked: Int = 0,
        /** The next window may not appear before this. */
        val snoozedUntil: Long = 0L,
        /** "Don't show again", and the old "Don't ask again". Silent, with no thank you. */
        val neverAgain: Boolean = false,
        /** The old "I already support", or the last card's "Done": silent, and About says thanks. */
        val supporter: Boolean = false,
        /** When the window was last shown, or 0. */
        val askedAt: Long = 0L,
        /** How many windows have been shown. */
        val windows: Int = 0,
        /** When the last notification went out, or 0. */
        val nudgedAt: Long = 0L,
        /** When the notification's star rung went out, or 0. */
        val starAskedAt: Long = 0L,
        /** Star on GitHub was pressed: no star is asked for again. */
        val starred: Boolean = false,
    ) {
        /** The old ladder ran to its end before the window replaced it: "we will not ask again". */
        val ladderDone: Boolean get() = asked >= LADDER_ASKS

        /** Nothing may ask any more. */
        val finished: Boolean get() = neverAgain || supporter || ladderDone
    }

    /**
     * Whether the viewer has really used the app: past its first day, and either [MIN_WATCHES]
     * videos watched to the end, or [MIN_DAYS] days since the first launch with one of them.
     */
    fun realUse(counters: Counters, now: Long): Boolean {
        if (counters.firstSeenAt <= 0L) return false
        val age = now - counters.firstSeenAt
        if (age < DAY_MS) return false
        return counters.completedWatches >= MIN_WATCHES ||
            (age >= MIN_DAYS * DAY_MS && counters.completedWatches >= 1)
    }

    /** When the window after the one shown at [now] may first appear. */
    fun snoozedUntil(now: Long): Long = now + GAP_DAYS * DAY_MS

    /**
     * Whether the window may appear at a good moment at [now]. [sessionShown] is whether one
     * already appeared in this run of the app.
     */
    fun due(
        counters: Counters,
        now: Long,
        enabled: Boolean = this.enabled,
        forcedRung: Int = this.forcedRung,
        sessionShown: Boolean = this.sessionShown,
    ): Boolean {
        if (!enabled) return false
        if (forcedRung > 0) return true
        if (counters.finished || sessionShown) return false
        return realUse(counters, now) && now >= counters.snoozedUntil
    }

    /**
     * Whether to show the window now. If so it counts as asked at once: a window the viewer never
     * answered (the app closed under it) is still one they saw. A forced one leaves the counters
     * alone.
     */
    suspend fun claim(store: SettingsStore, now: Long): Boolean {
        if (!due(store.supportCountersNow(), now)) return false
        if (forcedRung == 0) {
            store.recordSupportAsk(now)
            sessionShown = true
        }
        return true
    }

    /**
     * Whether the "Don't forget to sponsor" notification may go out at [now]. [switchOn] is the
     * "Sponsor reminders" setting. A TV never asks: its notifications are mostly out of sight.
     */
    fun nudgeDue(
        counters: Counters,
        now: Long,
        switchOn: Boolean,
        enabled: Boolean = this.enabled,
    ): Boolean {
        if (!enabled || !switchOn || counters.finished) return false
        if (!realUse(counters, now)) return false
        if (counters.nudgedAt > 0L && now - counters.nudgedAt < NUDGE_GAP_DAYS * DAY_MS) return false
        if (counters.askedAt > 0L && now - counters.askedAt < NUDGE_AFTER_ASK_DAYS * DAY_MS) return false
        return true
    }

    /** Which notification goes out: a star first, on its own, and money only after it. */
    enum class NudgeRung {
        /** "Enjoying TMPlayer?": Star on GitHub and Not now. */
        Star,

        /** "Don't forget to sponsor TMPlayer": Buy me a coffee, Star on GitHub, Not now. */
        Coffee,
    }

    /**
     * The rung the notification would take: [NudgeRung.Star] until a star has been asked for once
     * or given, [NudgeRung.Coffee] after.
     */
    fun nudgeRung(counters: Counters): NudgeRung =
        if (!counters.starred && counters.starAskedAt <= 0L) NudgeRung.Star else NudgeRung.Coffee

    /** Whether a notification of [rung] offers Star on GitHub: never once it has been pressed. */
    fun offersStar(counters: Counters, rung: NudgeRung): Boolean = rung == NudgeRung.Star || !counters.starred

    /**
     * The notification, if due, and which rung it is: recorded as sent at once, so a second check
     * cannot repeat it. Null when none is due.
     */
    suspend fun claimNudgeRung(store: SettingsStore, now: Long): NudgeRung? {
        val counters = store.supportCountersNow()
        if (!nudgeDue(counters, now, store.sponsorRemindersNow())) return null
        val rung = nudgeRung(counters)
        store.recordSupportNudge(now, star = rung == NudgeRung.Star)
        return rung
    }

    /** The notification, if due: recorded as sent at once, so a second check cannot repeat it. */
    suspend fun claimNudge(store: SettingsStore, now: Long): Boolean = claimNudgeRung(store, now) != null

    /**
     * A press on the notification: the shell opens the window as soon as it is free to (in the
     * shell, nothing else up, not in the player), and takes the request with [takeOpenRequest].
     */
    private val openRequest = MutableStateFlow(false)

    val openRequested: StateFlow<Boolean> get() = openRequest

    fun requestOpen() {
        openRequest.value = true
    }

    fun takeOpenRequest(): Boolean {
        val wanted = openRequest.value
        openRequest.value = false
        return wanted
    }

    // ---- positive moments -----------------------------------------------------------------------

    /** True once the window has appeared in this run of the app. */
    @Volatile
    var sessionShown: Boolean = false

    /** Forgets that a window appeared, any pending moment and any open request, for tests. */
    fun resetSession() {
        sessionShown = false
        pending.value = 0L
        openRequest.value = false
    }

    /** One video watched to the end, as the player saw it. */
    data class Finished(val key: String, val at: Long)

    private val finishedEvents = MutableSharedFlow<Finished>(extraBufferCapacity = 16)

    /** Every video watched to the end, for the shell that counts them. */
    val finished: SharedFlow<Finished> get() = finishedEvents

    private val pending = MutableStateFlow(0L)

    /**
     * When the last positive moment happened, or 0. The shell's chat list and Downloads screen show
     * the window when this is set and consume it with [takeMoment].
     */
    val moment: StateFlow<Long> get() = pending

    /** A moment older than this is stale: the window would not feel like a thank you any more. */
    const val MOMENT_LIFETIME_MS = 30L * 60 * 1000

    /** The player saw [key] (see [SettingsStore.progressKey]) through to the end. */
    fun watchFinished(key: String, now: Long = System.currentTimeMillis()) {
        finishedEvents.tryEmit(Finished(key, now))
        pending.value = now
    }

    /** A download has landed in Downloads. */
    fun downloadFinished(now: Long = System.currentTimeMillis()) {
        pending.value = now
    }

    /** Takes the pending moment, true if it is still fresh. */
    fun takeMoment(now: Long): Boolean {
        val at = pending.value
        pending.value = 0L
        return at > 0L && now - at <= MOMENT_LIFETIME_MS
    }

    // ---- counting ----------------------------------------------------------------------------------

    /** A video seen recently: what stops a re-open within the hour from counting twice. */
    data class Recent(val key: String, val at: Long, val positionMs: Long, val completed: Boolean)

    private fun fresh(entry: Recent?, now: Long): Recent? =
        entry?.takeIf { now - it.at in 0 until HOUR_MS }

    /**
     * Watch time from one progress report: how far the position moved since the last report of the
     * same video, never more than the wall clock allows and never more than [MAX_STEP_MS]. A video
     * already finished within the hour adds nothing. Returns the new list and the milliseconds added.
     */
    fun stepTime(recent: List<Recent>, key: String, positionMs: Long, now: Long): Pair<List<Recent>, Long> {
        val before = fresh(recent.firstOrNull { it.key == key }, now)
        if (before?.completed == true) return recent to 0L
        val added = if (before != null && positionMs >= before.positionMs) {
            minOf(positionMs - before.positionMs, now - before.at, MAX_STEP_MS)
        } else {
            0L
        }
        return replace(recent, Recent(key, now, positionMs, completed = false)) to added
    }

    /**
     * A finished video. It counts once per hour: a re-open and a second finish within the hour of
     * the first adds nothing. Returns the new list and whether this one counts.
     */
    fun stepCompleted(recent: List<Recent>, key: String, now: Long): Pair<List<Recent>, Boolean> {
        val before = fresh(recent.firstOrNull { it.key == key }, now)
        if (before?.completed == true) return recent to false
        return replace(recent, Recent(key, now, before?.positionMs ?: 0L, completed = true)) to true
    }

    private fun replace(recent: List<Recent>, entry: Recent): List<Recent> =
        (listOf(entry) + recent.filter { it.key != entry.key }).take(RECENT_LIMIT)

    fun encodeRecent(recent: List<Recent>): String =
        recent.joinToString(";") { "${it.key}|${it.at}|${it.positionMs}|${if (it.completed) 1 else 0}" }

    fun decodeRecent(text: String?): List<Recent> =
        text.orEmpty().split(';').mapNotNull { part ->
            val f = part.split('|')
            if (f.size != 4) return@mapNotNull null
            Recent(
                f[0],
                f[1].toLongOrNull() ?: return@mapNotNull null,
                f[2].toLongOrNull() ?: return@mapNotNull null,
                f[3] == "1",
            )
        }

    /** The hours the window quotes, never below one. */
    fun hoursWatched(counters: Counters): Int =
        ((counters.watchTimeMs + HOUR_MS / 2) / HOUR_MS).toInt().coerceAtLeast(1)
}
