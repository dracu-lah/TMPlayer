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

    private interface CLib : Library {
        @Suppress("FunctionName")
        fun _exit(status: Int)
    }

    fun now(code: Int = 0): Nothing {
        runCatching { SingleInstance.close() }
        if (OsInfo.isLinux) {
            System.out.flush()
            System.err.flush()
            runCatching { Native.load("c", CLib::class.java)._exit(code) }
        }
        exitProcess(code)
    }
}
