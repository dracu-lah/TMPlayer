package com.tmplayer.ui.downloads

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The few seconds in which a cancelled download can be brought back (R2, 7.2). A cancel [begin]s a
 * window for that download and hands back its token; [undo] and [expire] each end it only if the
 * token is still the one on file, so an Undo and a second Cancel of the same download never finish
 * one another's window, and a window that was undone does nothing when its timer runs out.
 *
 * The expiry runs in a scope that outlives the page (see [schedule]) and the window itself is one
 * per process ([shared]), so rotating the screen or leaving the page inside the window neither
 * brings the card back nor leaves the part file behind.
 *
 * Read from the page's list: a download with a window open is [isPending], and its card is gone
 * from the page while the download itself waits, paused, with its part file.
 */
internal class CancelWindow {
    private val open = mutableStateMapOf<Int, Long>()
    private var counter = 0L

    /** Whether [fileId] was cancelled and its window is still open. */
    fun isPending(fileId: Int): Boolean = fileId in open

    /** The downloads with a window open, for the page to leave out of its list. */
    val pending: Set<Int> get() = open.keys.toSet()

    /** Opens a window for [fileId] and returns its token. A second one replaces the first. */
    fun begin(fileId: Int): Long {
        val token = ++counter
        open[fileId] = token
        return token
    }

    /** Whether [token] is still the window of [fileId] (not undone, not replaced). */
    fun isCurrent(fileId: Int, token: Long): Boolean = open[fileId] == token

    /** Ends the window of [fileId] with Undo: true if [token] was still its window, so the caller resumes it. */
    fun undo(fileId: Int, token: Long): Boolean {
        if (open[fileId] != token) return false
        open.remove(fileId)
        return true
    }

    /** Closes the window of [fileId] once its time is up and the part file is deleted: true if [token] was still its window. */
    fun expire(fileId: Int, token: Long): Boolean = undo(fileId, token)

    /**
     * Runs the end of the window of [fileId] in [scope] (the process-wide one, not the page's):
     * after [delayMs], if [token] is still its window, [drop] deletes what was fetched and only then
     * is the card let go, so it never flashes back between the two.
     */
    fun schedule(scope: CoroutineScope, delayMs: Long, fileId: Int, token: Long, drop: suspend (Int) -> Unit) {
        scope.launch {
            delay(delayMs)
            if (isCurrent(fileId, token)) {
                try {
                    drop(fileId)
                } finally {
                    expire(fileId, token)
                }
            }
        }
    }

    companion object {
        /** The one window of the process: a rotated or re-entered page finds the same cancels open. */
        val shared = CancelWindow()
    }
}
