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
 * ended ask for a new one ([renew]) since TDLib keeps the folder. A new or wiped folder gets its
 * marker as it is made ([AccountDirs.make]). A database from before markers (a folder with TDLib's
 * files and no marker) gets one the first time it is read, noted as such ([fromBeforeMarkers]):
 * only then is what was stored with no marker taken as this database's, once. Renewed or remade,
 * it is a new database and an unmarked queue is another's.
 */
object DatabaseMarker {
    const val FILE_NAME = "tmplayer-db-id"

    /** Beside the marker when the database was there before markers: the marker it was given then. */
    const val BEFORE_MARKERS_NAME = "tmplayer-db-before-markers"
    private val VALID = Regex("[a-z0-9]{8,32}")

    /** The marker of the database in [dir], made now when it has none; null when [dir] is not there. */
    fun ensure(dir: File): String? = synchronized(this) {
        runCatching {
            if (!dir.isDirectory) return@runCatching null
            read(dir) ?: run {
                // TDLib's files with no marker: a database from before markers.
                val before = dir.list().orEmpty().any { it != FILE_NAME && it != "$FILE_NAME.tmp" && it != BEFORE_MARKERS_NAME }
                write(dir)
                // What is on the disk, never what was meant to be: a write cut short reads null.
                val marker = read(dir)
                val note = File(dir, BEFORE_MARKERS_NAME)
                if (before && marker != null) note.writeText(marker) else note.delete()
                marker
            }
        }.getOrNull()
    }

    /** A new marker for [dir]: the database there is a new one (a sign out, an ended session). */
    fun renew(dir: File): String? = synchronized(this) {
        runCatching {
            if (!dir.isDirectory) return@runCatching null
            File(dir, BEFORE_MARKERS_NAME).delete()
            write(dir)
            read(dir)
        }.getOrNull()
    }

    /**
     * Whether the database in [dir] was there before markers and given [marker] then: what was
     * stored with no marker is its own. False once it was renewed or remade.
     */
    fun fromBeforeMarkers(dir: File, marker: String): Boolean = synchronized(this) {
        runCatching { File(dir, BEFORE_MARKERS_NAME).takeIf { it.isFile && it.length() in 1..64 }?.readText()?.trim() == marker }.getOrDefault(false)
    }

    /**
     * Whether what was stored with [stored] may be used on the database marked [current]: the same
     * marker, or none stored when [current] is a database from before markers ([fromBeforeMarkers]).
     */
    fun matches(stored: String?, current: String, beforeMarkers: Boolean): Boolean =
        stored == current || (stored == null && beforeMarkers)

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
