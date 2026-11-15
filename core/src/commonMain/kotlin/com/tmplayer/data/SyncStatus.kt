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
            SyncStatus.Kind.Failed -> L.settingsResumeSyncFailed(status.detail.ifBlank { "?" })
            SyncStatus.Kind.Unreadable -> L.settingsResumeSyncUnreadable
            SyncStatus.Kind.TooNew -> L.settingsResumeSyncTooNew
            SyncStatus.Kind.OtherAccount -> L.settingsResumeSyncOtherAccount
        }
        return if (status.kind == SyncStatus.Kind.Done || lastSyncedAt <= 0) line else "$line $last"
    }

    /** "Sync now", or "Replace the sync file" when the file there could not be read. */
    fun action(status: SyncStatus?): String =
        if (status?.kind == SyncStatus.Kind.Unreadable) L.settingsResumeSyncReplace else L.settingsResumeSyncNow
}
