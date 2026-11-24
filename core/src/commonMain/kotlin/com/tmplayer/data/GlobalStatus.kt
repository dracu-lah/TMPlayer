package com.tmplayer.data

/**
 * The one corner of the app that says what the line to Telegram is doing beyond offline and
 * connecting (WP-100): catching up after a standby, a wait Telegram asked for with its countdown,
 * and a resume sync that needs the viewer. Pure, so the phone, the television and the desktop
 * decide the same way and the timings are tested here once.
 *
 * Connection states (offline, reconnecting, connecting) win over everything here: the host asks
 * [GlobalStatusRules.decide] only when none of them is up. Derived from flows each time and never
 * saved: after process death the corner starts [Idle].
 */
sealed interface GlobalLine {

    /** Nothing to say: nothing is drawn and nothing ticks. */
    data object Idle : GlobalLine

    /** TDLib is catching up ("Updating…"), after more than [GlobalStatusRules.UPDATING_SHOWN_AFTER_MS]. */
    data object Updating : GlobalLine

    /** Catching up for [GlobalStatusRules.SLOW_AFTER_MS] or more: "Telegram is slow to answer", with Retry. */
    data object SlowAnswer : GlobalLine

    /** Telegram asked to wait [seconds] more (over [WorkStatus.QUIET_WAIT_SECONDS]); ticks down. */
    data class RateLimited(val seconds: Int) : GlobalLine

    /** The resume sync needs the viewer: [kind] is one of [GlobalStatusRules.NEEDS_YOU]. */
    data class SyncNeedsYou(val kind: SyncStatus.Kind) : GlobalLine
}

object GlobalStatusRules {

    /** "Updating…" shows only after this long, so a catch up that ends at once never flashes it. */
    const val UPDATING_SHOWN_AFTER_MS = 2_000L

    /** After this long catching up, the line becomes "Telegram is slow to answer" with Retry. */
    const val SLOW_AFTER_MS = 60_000L

    /**
     * The sync outcomes that wait for the viewer to do something. A plain failure is retried by
     * itself, three times ([SyncStreak]); then it is [GlobalLine.SyncNeedsYou] of [SyncStatus.Kind.Failed].
     */
    val NEEDS_YOU = setOf(SyncStatus.Kind.Unreadable, SyncStatus.Kind.TooNew, SyncStatus.Kind.OtherAccount)

    /**
     * What the corner says.
     *
     * [updatingForMs] is how long the link has been [TelegramLink.Updating] (0 when it is not),
     * [waitSeconds] what is left of Telegram's flood wait (0 for none), [sync] the last sync's kind
     * and [syncDismissed] whether the viewer already closed or saw its line today. Order: a wait
     * Telegram asked for, then catching up, then the sync that needs the viewer.
     */
    fun decide(
        link: TelegramLink,
        updatingForMs: Long,
        waitSeconds: Int,
        sync: SyncStatus.Kind?,
        syncDismissed: Boolean,
        /** The sync gave up after three failed tries in a row ([SyncStreak.stopped]). */
        syncStopped: Boolean = false,
    ): GlobalLine {
        WorkStatus.rateLimited(waitSeconds, now = 0L)?.let { return GlobalLine.RateLimited(waitSeconds) }
        if (link == TelegramLink.Updating) {
            if (updatingForMs >= SLOW_AFTER_MS) return GlobalLine.SlowAnswer
            if (updatingForMs >= UPDATING_SHOWN_AFTER_MS) return GlobalLine.Updating
        }
        if (sync != null && sync in NEEDS_YOU && !syncDismissed) return GlobalLine.SyncNeedsYou(sync)
        // Three failed tries: it stopped by itself, and the way out is Try again and Send logs.
        if (syncStopped && !syncDismissed) return GlobalLine.SyncNeedsYou(SyncStatus.Kind.Failed)
        return GlobalLine.Idle
    }

    /** Whether anything needs the next look a second from now: a count down or a catch up that is about to cross a threshold. */
    fun ticks(line: GlobalLine, link: TelegramLink): Boolean =
        line is GlobalLine.RateLimited || (link == TelegramLink.Updating && line != GlobalLine.SlowAnswer)

    /**
     * The hint id of the sync line for [kind] on the day [day] (yyyymmdd): one line per kind per
     * day, through the hint registry ([HintRegistry]), so no new stored key exists. Ids are
     * lower case letters, digits and underscores, at most [HintRegistry.MAX_ID] long.
     */
    /**
     * The kind the sync line is filed under for "once per day": [SyncStatus.Kind.Failed] once the
     * sync gave up ([SyncStreak.stopped]), whatever the last run said, else the last run's kind.
     */
    fun hintKind(status: SyncStatus?): SyncStatus.Kind? =
        if (SyncStreak.stopped(status)) SyncStatus.Kind.Failed else status?.kind

    fun syncHintId(kind: SyncStatus.Kind, day: String): String =
        ("sync_" + kind.code + "_" + day.filter { it.isDigit() }.take(8)).take(HintRegistry.MAX_ID)
}
