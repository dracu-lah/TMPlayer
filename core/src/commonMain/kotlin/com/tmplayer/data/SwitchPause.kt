package com.tmplayer.data

/**
 * The downloads a profile switch's wait paused (HX88), kept in memory only, for one thing: a
 * Cancel of that switch puts them back. A switch that goes ahead leaves them paused, as a restart
 * does: the profile's queue comes back as paused rows when it is open next ([OfflineDownloads.restore])
 * and the viewer resumes them. Nothing is resumed by itself across a switch, a sign out, an ended
 * session or a restart, so a file id is never taken into a database it does not belong to.
 */
object SwitchPause {
    /** One paused download: its file id and the video it is, so another video under the same id is never resumed. */
    data class Paused(val fileId: Int, val chatId: Long, val messageId: Long)

    /** What a wait paused: the profile, the set of rows ([OfflineDownloads.rowsGeneration]) and the ids. */
    data class Held(val stamp: AccountStamp, val rows: Long, val paused: List<Paused>)

    /** [before] with [now] added when it is the same profile and rows (a retry's wait finds nothing moving), else [now] alone. */
    fun hold(before: Held?, stamp: AccountStamp, rows: Long, now: List<Paused>): Held {
        val kept = before?.takeIf { it.stamp == stamp && it.rows == rows }?.paused.orEmpty()
        return Held(stamp, rows, (kept + now).distinctBy { it.fileId })
    }

    /**
     * The ids of [held] to resume now: none unless the same profile is open on the same set of
     * rows (no switch, sign out or ended session since), and of those only the rows still the same
     * video ([rows]: file id to chat and message).
     */
    fun toResume(held: Held?, stamp: AccountStamp, rowsGeneration: Long, rows: Map<Int, Pair<Long, Long>>): List<Int> {
        if (held == null || held.stamp != stamp || held.rows != rowsGeneration) return emptyList()
        return held.paused.filter { rows[it.fileId] == (it.chatId to it.messageId) }.map { it.fileId }
    }
}
