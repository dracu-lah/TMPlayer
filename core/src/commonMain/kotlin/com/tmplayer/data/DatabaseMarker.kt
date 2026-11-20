package com.tmplayer.data

import java.io.File
import kotlin.random.Random

/**
 * Which TDLib database a file id belongs to (HX88). TDLib's file ids are per database, so a paused
 * download or a stored download queue taken from one database and resumed on another would fetch
 * another file under the old video's row. A random marker is kept in the profile's database
 * folder; whatever stores file ids for later stores the marker with them, and anything whose
 * marker is not the current database's is dropped, never resumed.
 *
 * The marker changes with the database: a folder deleted (Sign in again, a removed profile, Remove
 * all accounts) loses it and the next read makes a new one; a sign out and a session Telegram
 * ended ask for a new one ([renew]) since TDLib keeps the folder. A database from before markers
 * existed gets one the first time it is read, and what was stored before then (with no marker)
 * is taken as this database's, once.
 */
object DatabaseMarker {
    const val FILE_NAME = "tmplayer-db-id"
    private val VALID = Regex("[a-z0-9]{8,32}")

    /** The marker of the database in [dir], made now when it has none; null when [dir] is not there. */
    fun ensure(dir: File): String? = runCatching {
        if (!dir.isDirectory) return@runCatching null
        read(dir) ?: write(dir)
    }.getOrNull()

    /** A new marker for [dir]: the database there is a new one (a sign out, an ended session). */
    fun renew(dir: File): String? = runCatching { if (dir.isDirectory) write(dir) else null }.getOrNull()

    /**
     * Whether what was stored with [stored] may be used on the database marked [current]: the same
     * marker, or one stored before markers existed (null), or no database to compare with.
     */
    fun matches(stored: String?, current: String?): Boolean = stored == null || current == null || stored == current

    private fun read(dir: File): String? =
        File(dir, FILE_NAME).takeIf { it.isFile && it.length() in 1..64 }?.readText()?.trim()?.takeIf { VALID.matches(it) }

    private fun write(dir: File): String {
        val marker = (1..16).map { "abcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(36)] }.joinToString("")
        val temp = File(dir, "$FILE_NAME.tmp")
        temp.writeText(marker)
        val target = File(dir, FILE_NAME)
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
        return marker
    }
}
