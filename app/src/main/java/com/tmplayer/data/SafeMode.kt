package com.tmplayer.data

import android.content.Context
import android.os.SystemClock
import java.io.File

/**
 * Safe mode for a start-up loop (the report of 2026-11-14, a phone that closed on every start):
 * after two runs in a row that crashed soon after opening ([CrashLoop]), the next start leaves out
 * what is not needed to show the chat list. It opens on Chats rather than Home, skips the loading
 * Home and the chats' pages do in the background (their caches, posters and overviews, the
 * checks the phone makes while closed, the home screen channels), and says so once.
 *
 * Counted by [CrashLoop], the same file the desktop uses, whatever the crash reports switch says.
 * It ends by itself: a run that stays up for [CrashLoop.EARLY_MS] forgets the crashes ([stayedUp]),
 * so the next start is an ordinary one. It is not a setting, and nothing of the viewer's is
 * changed or deleted by it.
 *
 * Only a crash the uncaught exception handler sees is counted. A native crash or the system
 * killing a hung app leaves no mark here.
 */
object SafeMode {
    /** Whether this run is in safe mode. Decided once, in [watch], before anything is built. */
    @Volatile
    var active: Boolean = false
        private set

    private var loop: CrashLoop? = null

    /**
     * First thing in [com.tmplayer.App.onCreate], before [CrashReports.watch], which has no
     * Sentry key in a build without one (F-Droid) and so would count nothing. Decides the run,
     * then counts a crash of this run as it happens, written down before the process dies, and
     * passes it on. Never throws.
     */
    fun watch(context: Context) {
        runCatching {
            val loop = CrashLoop(File(context.filesDir, "crash/early.txt"))
            this.loop = loop
            active = loop.looping()
            val startedAt = SystemClock.elapsedRealtime()
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                runCatching { loop.note(SystemClock.elapsedRealtime() - startedAt) }
                previous?.uncaughtException(thread, error)
            }
        }
    }

    /** The run has stayed up long enough: the crashes before it are forgotten, and the next start is ordinary. */
    fun stayedUp() {
        loop?.clean()
    }

    /**
     * The viewer asked to open normally: forgotten at once, so the next start is ordinary. This
     * run goes on in safe mode, which has already left out what it left out.
     */
    fun openNormallyNextTime() {
        loop?.clean()
    }
}
