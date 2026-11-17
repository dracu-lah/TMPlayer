package com.tmplayer.platform

/**
 * The log a platform keeps for "Save logs" when it has no system log the app can read back. Android
 * reads its own logcat, where TDLib writes too, and the desktop has its log file, so nothing is
 * kept here: iOS's KeptLog (src/iosMain) is the one that keeps lines.
 */
object KeptLog {
    /** TDLib's level: errors only, as before, into logcat. */
    val tdlibVerbosity: Int get() = 1

    /** Where TDLib writes its log: nowhere of the app's own, it goes to logcat. */
    val tdlibLogPath: String? get() = null

    /** The size TDLib's file grows to before it starts a new one; unused without a file. */
    val tdlibMaxBytes: Long get() = 0L

    /** The kept lines, or null where the platform's own log is read instead. */
    fun lines(max: Int): List<String>? = null
}
