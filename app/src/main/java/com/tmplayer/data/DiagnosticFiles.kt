package com.tmplayer.data

import android.content.Context
import com.tmplayer.player.PlaybackMarks
import com.tmplayer.player.TraceFile
import java.io.File

/**
 * Every file this app keeps to help with a bug report (reliability plan L1 to L6), in one list so
 * sign-out and "Delete your data" cannot forget one: the playback trace, the stutter marks, the
 * last Playback test, the last video's codec, the app log and the report waiting to be shared.
 *
 * All of them are local, hold what a viewer watched, and leave the device only through an export
 * the viewer starts ([LogBundle]).
 */
object DiagnosticFiles {

    /** The last Playback test's result, for Send logs (L6). */
    const val PLAYBACK_TEST = "playback-test.txt"

    /** The codec of the last video that played, so a report lists the decoders for it (L5). */
    const val LAST_CODEC = "last-video-codec.txt"

    /** Under `filesDir`: each name and the half-written `.part` a rewrite may have left. */
    val FILES_NAMES = listOf(TraceFile.NAME, PlaybackMarks.NAME, PLAYBACK_TEST, LAST_CODEC)

    /** Folders under `filesDir` (the app log) and under `cacheDir` (the report handed to the share sheet). */
    const val APP_LOG_DIR = "logs"
    const val SHARE_DIR = "logs"

    /** Removes all of it; one that is missing or cannot be removed is skipped, never an error. */
    fun forgetIn(filesDir: File, cacheDir: File) {
        for (name in FILES_NAMES) {
            runCatching { File(filesDir, name).delete() }
            runCatching { File(filesDir, "$name.part").delete() }
        }
        runCatching { File(filesDir, APP_LOG_DIR).deleteRecursively() }
        runCatching { File(cacheDir, SHARE_DIR).deleteRecursively() }
    }

    fun forget(context: Context) {
        val app = context.applicationContext
        // The app log's own lock first, so a write in flight does not recreate a file after the delete.
        AppLog.forget(app)
        forgetIn(app.filesDir, app.cacheDir)
    }

    fun codecFile(context: Context): File = File(context.applicationContext.filesDir, LAST_CODEC)
}
