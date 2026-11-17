package android.app

/** How a process of the app ended, Android 11's. iOS lists none (see ActivityManager.getHistoricalProcessExitReasons), so none is ever made. */
class ApplicationExitInfo private constructor() {
    val reason: Int = REASON_UNKNOWN
    val timestamp: Long = 0L

    companion object {
        const val REASON_UNKNOWN = 0
        const val REASON_EXIT_SELF = 1
        const val REASON_SIGNALED = 2
        const val REASON_LOW_MEMORY = 3
        const val REASON_CRASH = 4
        const val REASON_CRASH_NATIVE = 5
        const val REASON_ANR = 6
    }
}
