package com.tmplayer.data

/**
 * Asking for support: the one switch that turns every donation link off, and the rule for the
 * small card that asks, now and then, whether TMPlayer is worth a coffee.
 *
 * The card is meant to be rare. It waits for real use, [MIN_DAYS] days since this install first
 * counted and [MIN_PLAYS] videos started, and once it has been shown it stays away for
 * [SNOOZE_DAYS] days, whatever was pressed. "Don't ask again" is for good. It is never shown during
 * playback; each app draws it over its chat list only.
 */
object SupportReminder {

    /**
     * Every donation link and the card, together. On in every build that exists today. The `play`
     * flavor planned for Google Play (CP35) turns it off at startup, since Play does not allow
     * donation links outside its own billing, and then the Settings row, the About group and the
     * card all go with it.
     */
    @Volatile
    var enabled: Boolean = true

    /**
     * Shows the card whatever the counters say, for screenshots and for checking it by eye: a
     * debug build's `--ez support_reminder true`, the promo fixture's same extra, or the desktop's
     * `-Dtmplayer.supportReminder=true`. Still nothing when [enabled] is off.
     */
    @Volatile
    var forced: Boolean = false

    const val MIN_DAYS = 14
    const val MIN_PLAYS = 10
    const val SNOOZE_DAYS = 60

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** What the store remembers. [firstSeenAt] is 0 until the first count. */
    data class Counters(
        val firstSeenAt: Long = 0L,
        val plays: Int = 0,
        val snoozedUntil: Long = 0L,
        val neverAgain: Boolean = false,
    )

    /** Whether the card should appear now. */
    fun due(counters: Counters, now: Long, enabled: Boolean = this.enabled, forced: Boolean = this.forced): Boolean {
        if (!enabled) return false
        if (forced) return true
        if (counters.neverAgain || counters.firstSeenAt <= 0L) return false
        return now - counters.firstSeenAt >= MIN_DAYS * DAY_MS &&
            counters.plays >= MIN_PLAYS &&
            now >= counters.snoozedUntil
    }

    /** When the card may come back after being shown, or dismissed with "Not now", at [now]. */
    fun snoozedUntil(now: Long): Long = now + SNOOZE_DAYS * DAY_MS

    /**
     * Whether to show the card now, and if so, the snooze starts at once: a card the viewer never
     * answered (the app closed under it) is still a card they saw, and it does not come back on
     * the next launch. A forced card leaves the counters alone.
     */
    suspend fun claim(store: SettingsStore, now: Long): Boolean {
        if (!due(store.supportCountersNow(), now)) return false
        if (!forced) store.snoozeSupport(now)
        return true
    }
}
