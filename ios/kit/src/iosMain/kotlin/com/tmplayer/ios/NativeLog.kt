package com.tmplayer.ios

import com.tmplayer.platform.Logger

/**
 * The Swift side's log lines (the engines, the audio session, the turns of the screen), through
 * the app's [Logger]: so they reach the system log as before and the app's kept log as well, which
 * "Save logs" reads (com.tmplayer.platform.KeptLog). Called from Swift's `tmLog` (AppLog.swift).
 */
object NativeLog {
    fun line(tag: String, message: String) = Logger.i(tag, message)

    fun warning(tag: String, message: String) = Logger.w(tag, message)
}
