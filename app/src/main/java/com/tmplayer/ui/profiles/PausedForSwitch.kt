package com.tmplayer.ui.profiles

import android.content.Context

/**
 * The downloads a profile switch paused, kept per profile on the device (HX88), so they go on
 * again when that profile is open next: after a Cancel, after the viewer comes back to it, or
 * after the process died while the switch waited. Only the ids; a row that is gone by then is
 * passed over.
 */
internal object PausedForSwitch {
    private const val PREFS = "profile_switch_paused"

    private fun key(folder: String?) = "paused_" + (folder ?: "")

    /** One paused download: its file id and the video it is, so a new database's id is never taken for it. */
    data class Paused(val fileId: Int, val chatId: Long, val messageId: Long)

    /**
     * Adds [paused] to what is kept for [folder]: never overwritten, so a retry's wait, which finds
     * nothing moving any more, does not lose what the first wait paused.
     */
    fun record(context: Context, folder: String?, paused: List<Paused>, database: String? = com.tmplayer.data.Td.databaseMarker()) {
        if (paused.isEmpty()) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Merged only with a set of the same database; one of another database is dropped.
        val kept = if (com.tmplayer.data.DatabaseMarker.matches(prefs.getString(dbKey(folder), null), database)) read(prefs, folder) else emptyList()
        val merged = (kept + paused).distinctBy { it.fileId }
        prefs.edit()
            .putString(key(folder), merged.joinToString(";") { "${it.fileId},${it.chatId},${it.messageId}" })
            .apply { if (database != null) putString(dbKey(folder), database) else remove(dbKey(folder)) }
            .apply()
    }

    private fun dbKey(folder: String?) = "db_" + (folder ?: "")

    private fun read(prefs: android.content.SharedPreferences, folder: String?): List<Paused> =
        prefs.getString(key(folder), null)?.split(';')?.mapNotNull { entry ->
            val parts = entry.split(',')
            val id = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return@mapNotNull null
            val chat = parts.getOrNull(1)?.trim()?.toLongOrNull() ?: return@mapNotNull null
            val message = parts.getOrNull(2)?.trim()?.toLongOrNull() ?: return@mapNotNull null
            Paused(id, chat, message)
        }.orEmpty()

    /**
     * What was paused for [folder]'s switch, taken: asked again, there is nothing. Nothing when it
     * was paused in another database than [database] (its ids are other files now).
     */
    fun take(context: Context, folder: String?, database: String? = com.tmplayer.data.Td.databaseMarker()): List<Paused> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val same = com.tmplayer.data.DatabaseMarker.matches(prefs.getString(dbKey(folder), null), database)
        val paused = if (same) read(prefs, folder) else emptyList()
        prefs.edit().remove(key(folder)).remove(dbKey(folder)).apply()
        return paused
    }

    /**
     * The ids of [paused] that are still the same video in [rows] (file id to chat and message):
     * a row of another video under the same id (a new TDLib database after a sign out and a sign
     * in) is never resumed.
     */
    fun stillTheSame(paused: List<Paused>, rows: Map<Int, Pair<Long, Long>>): List<Int> =
        paused.filter { rows[it.fileId] == (it.chatId to it.messageId) }.map { it.fileId }
}
