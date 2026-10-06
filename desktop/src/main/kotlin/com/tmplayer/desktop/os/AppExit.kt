package com.tmplayer.desktop.os

import com.sun.jna.Library
import com.sun.jna.Native
import kotlin.system.exitProcess

/**
 * Ends the process once the window is gone.
 *
 * On Linux a plain `System.exit` could hang for good: the JVM's last step is libc's `exit()`, whose
 * library destructors include libGLX's, and that one takes the X display lock. AWT's X thread can
 * be holding that very lock at the time, parked inside an X error handler (the window was just
 * destroyed) waiting for the exiting JVM, which never lets it go. The window vanished and the
 * process stayed. So on Linux the app does its own tidying and then leaves with `_exit()`, which
 * runs no destructors; TDLib's database is written to survive exactly that.
 */
object AppExit {

    private const val HALT_AFTER_MS = 5_000L

    private interface CLib : Library {
        @Suppress("FunctionName")
        fun _exit(status: Int)
    }

    fun now(code: Int = 0): Nothing {
        runCatching { SingleInstance.close() }
        // Everywhere else `System.exit` runs the shutdown hooks first, and one that never returns
        // (a native library waiting on its own thread) would leave a process with no window that
        // still holds the single instance lock, so every later launch hands over to it and quits.
        // Past the grace period the process is halted, hooks or not.
        Thread {
            Thread.sleep(HALT_AFTER_MS)
            Runtime.getRuntime().halt(code)
        }.apply { isDaemon = true }.start()
        if (OsInfo.isLinux) {
            System.out.flush()
            System.err.flush()
            runCatching { Native.load("c", CLib::class.java)._exit(code) }
        }
        exitProcess(code)
    }
}
