package com.tmplayer.data

import com.tmplayer.i18n.L

/**
 * What the last sync of resume on other devices came to ([ResumeSync]), kept in the settings for
 * the Settings row: a kind, when, how many entries came in, how many a cap left out, and the
 * failure's own words. Stored as one line; a line this build cannot read is no status at all.
 */
data class SyncStatus(
    val kind: Kind,
    val at: Long,
    val received: Int = 0,
    val dropped: Int = 0,
    val detail: String = "",
    /**
     * How many runs in a row ended [Kind.Failed], this one included (WP-97, WP-100): the count
     * behind "three failed tries and it stops trying by itself" ([SyncStreak]). 0 for any other kind.
     */
    val failures: Int = 0,
) {
    enum class Kind(val code: String) {
        Done("done"),
        NoConnection("offline"),
        WaitingForUnmetered("metered"),

        /** A new file was sent and Telegram has not confirmed it yet: not sent again until it does. */
        WaitingForFile("pending"),
        Failed("failed"),

        /** The file in Saved Messages could not be read: this device's lists kept, nothing written. */
        Unreadable("unreadable"),
        TooNew("newer"),
        OtherAccount("account"),
        ;

        companion object {
            fun ofCode(code: String?): Kind? = entries.firstOrNull { it.code == code }
        }
    }

    fun encode(): String = listOf(
        kind.code,
        at.toString(),
        received.toString(),
        dropped.toString(),
        detail.replace(SEP, ' ').replace('\n', ' ').take(DETAIL_MAX),
        // Last, so a build that does not know it still reads the five before it.
        failures.coerceIn(0, SyncStreak.COUNT_MAX).toString(),
    ).joinToString(SEP.toString())

    companion object {
        private const val SEP = '\u001F'
        private const val DETAIL_MAX = 200

        fun decode(text: String?): SyncStatus? {
            if (text.isNullOrEmpty()) return null
            val parts = text.split(SEP)
            if (parts.size < 4) return null
            return SyncStatus(
                kind = Kind.ofCode(parts[0]) ?: return null,
                at = parts[1].toLongOrNull() ?: return null,
                received = parts[2].toIntOrNull()?.coerceAtLeast(0) ?: 0,
                dropped = parts[3].toIntOrNull()?.coerceAtLeast(0) ?: 0,
                detail = parts.getOrNull(4).orEmpty().take(DETAIL_MAX),
                failures = parts.getOrNull(5)?.toIntOrNull()?.coerceIn(0, SyncStreak.COUNT_MAX) ?: 0,
            )
        }
    }
}

/** The Settings row's words for resume on other devices, shared by every platform. */
object SyncWords {

    /**
     * One line under "Sync now": how the last try went, and when the last one that went through
     * was, when the two differ.
     */
    fun status(status: SyncStatus?, lastSyncedAt: Long, syncing: Boolean): String {
        if (syncing) return L.settingsResumeSyncSyncing
        val f = L.messages.formatter
        val last = if (lastSyncedAt > 0) L.settingsResumeSyncLast(f.dateTime(lastSyncedAt)) else L.settingsResumeSyncNever
        if (status == null) return last
        val line = when (status.kind) {
            SyncStatus.Kind.Done -> {
                val done = L.settingsResumeSyncDone(f.dateTime(status.at), status.received)
                if (status.dropped > 0) done + " " + L.settingsResumeSyncDropped(status.dropped) else done
            }
            SyncStatus.Kind.NoConnection -> L.settingsResumeSyncOffline
            SyncStatus.Kind.WaitingForUnmetered -> L.settingsResumeSyncMetered
            SyncStatus.Kind.WaitingForFile -> L.settingsResumeSyncPending
            SyncStatus.Kind.Failed ->
                if (SyncStreak.stopped(status)) stopped(status) else L.settingsResumeSyncFailed(status.detail.ifBlank { "?" })
            SyncStatus.Kind.Unreadable -> L.settingsResumeSyncUnreadable
            SyncStatus.Kind.TooNew -> L.settingsResumeSyncTooNew
            SyncStatus.Kind.OtherAccount -> L.settingsResumeSyncOtherAccount
        }
        return if (status.kind == SyncStatus.Kind.Done || lastSyncedAt <= 0) line else "$line $last"
    }

    /** "Sync stopped after three failed tries." and the last reason, when there is one. */
    fun stopped(status: SyncStatus): String =
        L.settingsResumeSyncStopped + status.detail.trim().takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()

    /** "Sync now", or "Replace the sync file" when the file there could not be read. */
    fun action(status: SyncStatus?): String =
        if (status?.kind == SyncStatus.Kind.Unreadable) L.settingsResumeSyncReplace else L.settingsResumeSyncNow
}

/**
 * The three failures rule (WP-97, WP-100): a sync that failed [STOP_AFTER] runs in a row stops
 * being tried by itself. Only the viewer's own press (Sync now, Try again, turning it on) runs it
 * again, and a run that goes through puts the count back to nothing. So a broken sync file, a
 * channel that refuses posts or a Telegram that keeps saying no costs three tries, not a loop.
 *
 * Runs that did not try ([SyncStatus.Kind.NoConnection], waiting for an unmetered line or for a
 * file) neither add to the count nor clear it: the failures before them still stand.
 */
object SyncStreak {

    /** Failed runs in a row after which the background runs stop. */
    const val STOP_AFTER = 3

    /** The count is kept to this, so a stored line stays short and a bad one cannot be huge. */
    const val COUNT_MAX = 99

    /** The count to store with [now], the status of the run that just ended, given the status before it. */
    fun next(previous: SyncStatus?, now: SyncStatus): Int = when (now.kind) {
        SyncStatus.Kind.Failed -> ((previous?.failures ?: 0) + 1).coerceAtMost(COUNT_MAX)
        SyncStatus.Kind.Done -> 0
        SyncStatus.Kind.NoConnection, SyncStatus.Kind.WaitingForUnmetered, SyncStatus.Kind.WaitingForFile -> previous?.failures ?: 0
        // A file that needs the viewer is its own line, and its own way out.
        SyncStatus.Kind.Unreadable, SyncStatus.Kind.TooNew, SyncStatus.Kind.OtherAccount -> 0
    }

    /**
     * True when the sync gave up trying by itself. Whatever the last run said (a try by the viewer
     * that found no connection keeps the count), only a run that goes through clears it.
     */
    fun stopped(status: SyncStatus?): Boolean = status != null && status.failures >= STOP_AFTER
}
