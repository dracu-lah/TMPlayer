package com.tmplayer.desktop.os

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase

/**
 * Windows: `SetThreadExecutionState(ES_CONTINUOUS | ES_DISPLAY_REQUIRED | ES_SYSTEM_REQUIRED)`.
 *
 * The state belongs to the calling thread and lasts until that thread clears it or ends, which is
 * why [SerialKeepAwake] runs every call on the same long lived thread: setting it from one thread
 * and clearing it from another would leave the first one's request in force.
 *
 * Only constructed when [OsInfo.isWindows]; `Kernel32` is never touched elsewhere.
 */
internal class WindowsKeepAwake : SerialKeepAwake("windows") {

    override fun hold(reason: String): Boolean {
        val flags = WinBase.ES_CONTINUOUS or WinBase.ES_DISPLAY_REQUIRED or WinBase.ES_SYSTEM_REQUIRED
        // The previous state comes back on success, zero on failure.
        return Kernel32.INSTANCE.SetThreadExecutionState(flags) != 0
    }

    override fun letGo() {
        Kernel32.INSTANCE.SetThreadExecutionState(WinBase.ES_CONTINUOUS)
    }
}
