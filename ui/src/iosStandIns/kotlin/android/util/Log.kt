package android.util

import com.tmplayer.platform.StandardErrorSink as Logger

/**
 * android.util.Log, written to the unified log through core's own default sink. Not through
 * Logger: :app installs a sink that writes to android.util.Log, which would call itself.
 */
object Log {
    fun v(tag: String?, message: String): Int = 0.also { Logger.i(tag.orEmpty(), message) }
    fun d(tag: String?, message: String): Int = 0.also { Logger.i(tag.orEmpty(), message) }
    fun d(tag: String?, message: String, error: Throwable?): Int = 0.also { Logger.w(tag.orEmpty(), message, error) }
    fun i(tag: String?, message: String): Int = 0.also { Logger.i(tag.orEmpty(), message) }
    fun i(tag: String?, message: String, error: Throwable?): Int = 0.also { Logger.w(tag.orEmpty(), message, error) }
    fun w(tag: String?, message: String): Int = 0.also { Logger.w(tag.orEmpty(), message) }
    fun w(tag: String?, message: String, error: Throwable?): Int = 0.also { Logger.w(tag.orEmpty(), message, error) }
    fun w(tag: String?, error: Throwable?): Int = 0.also { Logger.w(tag.orEmpty(), error?.message.orEmpty(), error) }
    fun e(tag: String?, message: String): Int = 0.also { Logger.w(tag.orEmpty(), message) }
    fun e(tag: String?, message: String, error: Throwable?): Int = 0.also { Logger.w(tag.orEmpty(), message, error) }
    fun isLoggable(tag: String?, level: Int): Boolean = true
    fun getStackTraceString(error: Throwable?): String = error?.stackTraceToString().orEmpty()
    const val VERBOSE = 2
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6
}
