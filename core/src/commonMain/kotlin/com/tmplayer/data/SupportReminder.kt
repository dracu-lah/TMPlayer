package com.tmplayer.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Asking for support: the one switch that turns every donation link off, and the rules for the
 * small card that asks whether TMPlayer has earned a coffee.
 *
 * The card is rare and it is polite. It asks at most [MAX_ASKS] times for good, in a ladder:
 *
 * 1. Money, after [MIN_DAYS] days and [MIN_WATCHES] finished videos.
 * 2. Something cheaper, [SECOND_GAP_DAYS] days after the first: a star on GitHub, or a friend told.
 * 3. A last, short note, [THIRD_GAP_DAYS] days after the second.
 *
 * "I already support" and the last ask's "Done" are permanent and turn the About heading into a
 * thank you. Ignoring a card counts as having been asked, so nothing nags.
 *
 * It only ever appears at a good moment: back on the chat list or Downloads after a video was
 * watched to the end, or after a download finished. Never in the player, never twice in one run of
 * the app, and each shell holds it back behind the update popup and the language notice.
 */
object SupportReminder {

    /**
     * Every donation link and the card, together. On in every build that exists today. The `play`
     * flavor planned for Google Play (CP35) turns it off at startup, since Play does not allow
     * donation links outside its own billing, and then the Settings row, the About group and the
     * card all go with it. This is the only gate.
     */
    @Volatile
    var enabled: Boolean = true

    /**
     * Shows the card of this rung (1 to 3) whatever the counters say, for screenshots and for
     * checking it by eye: a debug build's `--ei support_rung N`, the promo fixture's same extra, or
     * the desktop's `-Dtmplayer.supportRung=N`. 0 is off. Still nothing when [enabled] is off, and
     * a forced card leaves the counters alone.
     */
    @Volatile
    var forcedRung: Int = 0

    /** The older switch: `--ez support_reminder true` forces the first rung's card. */
    var forced: Boolean
        get() = forcedRung > 0
        set(value) {
            forcedRung = if (value) 1 else 0
        }

    const val MIN_DAYS = 7
    const val MIN_WATCHES = 5
    const val SECOND_GAP_DAYS = 30
    const val THIRD_GAP_DAYS = 90
    const val MAX_ASKS = 3

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
        /** How many cards have been shown, 0 to [MAX_ASKS]. */
        val asked: Int = 0,
        /** The next card may not appear before this. */
        val snoozedUntil: Long = 0L,
        /** The old "Don't ask again". Silent, with no thank you. */
        val neverAgain: Boolean = false,
        /** "I already support", or the last ask's "Done": silent, and About says thanks. */
        val supporter: Boolean = false,
    )

    /** The rung that would be shown next, 1 to 3, or 0 when the ladder is finished. */
    fun nextRung(counters: Counters): Int =
        if (counters.neverAgain || counters.supporter || counters.asked >= MAX_ASKS) 0 else counters.asked + 1

    /** When the card after the one shown at [now] (rung [shown]) may first appear. */
    fun snoozedUntil(now: Long, shown: Int): Long = now + when (shown) {
        1 -> SECOND_GAP_DAYS
        else -> THIRD_GAP_DAYS
    } * DAY_MS

    /**
     * The rung to show at a good moment at [now], or 0 for none. [sessionShown] is whether a card
     * already appeared in this run of the app.
     */
    fun due(
        counters: Counters,
        now: Long,
        enabled: Boolean = this.enabled,
        forcedRung: Int = this.forcedRung,
        sessionShown: Boolean = this.sessionShown,
    ): Int {
        if (!enabled) return 0
        if (forcedRung > 0) return forcedRung.coerceAtMost(MAX_ASKS)
        val rung = nextRung(counters)
        if (rung == 0 || sessionShown || counters.firstSeenAt <= 0L) return 0
        if (now - counters.firstSeenAt < MIN_DAYS * DAY_MS) return 0
        return when {
            rung == 1 -> if (counters.completedWatches >= MIN_WATCHES) 1 else 0
            now >= counters.snoozedUntil -> rung
            else -> 0
        }
    }

    /**
     * Whether to show a card now, and which. If so it counts as asked at once: a card the viewer
     * never answered (the app closed under it) is still a card they saw. A forced card leaves the
     * counters alone.
     */
    suspend fun claim(store: SettingsStore, now: Long): Int {
        val rung = due(store.supportCountersNow(), now)
        if (rung == 0) return 0
        if (forcedRung == 0) {
            store.recordSupportAsk(now, rung)
            sessionShown = true
        }
        return rung
    }

    // ---- positive moments -----------------------------------------------------------------------

    /** True once a card has appeared in this run of the app. */
    @Volatile
    var sessionShown: Boolean = false

    /** Forgets that a card appeared and any pending moment, for tests. */
    fun resetSession() {
        sessionShown = false
        pending.value = 0L
    }

    /** One video watched to the end, as the player saw it. */
    data class Finished(val key: String, val at: Long)

    private val finishedEvents = MutableSharedFlow<Finished>(extraBufferCapacity = 16)

    /** Every video watched to the end, for the shell that counts them. */
    val finished: SharedFlow<Finished> get() = finishedEvents

    private val pending = MutableStateFlow(0L)

    /**
     * When the last positive moment happened, or 0. The shell's chat list and Downloads screen show
     * a card when this is set and consume it with [takeMoment].
     */
    val moment: StateFlow<Long> get() = pending

    /** A moment older than this is stale: the card would not feel like a thank you any more. */
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

    /** The hours shown in the first card, never below one. */
    fun hoursWatched(counters: Counters): Int =
        ((counters.watchTimeMs + HOUR_MS / 2) / HOUR_MS).toInt().coerceAtLeast(1)
}
