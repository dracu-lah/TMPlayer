package com.tmplayer.platform

/**
 * Where `:core` sends its log lines.
 *
 * Two levels, because that is all the shared code has ever used. Android installs a sink that
 * writes to logcat with the same tags as before; anything that installs nothing (the desktop until
 * it says otherwise, and every JVM test) gets standard error.
 */
interface LogSink {
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, error: Throwable? = null)
}

object Logger {

    @Volatile
    var sink: LogSink = StandardErrorSink

    fun i(tag: String, message: String) = sink.i(tag, message)

    fun w(tag: String, message: String, error: Throwable? = null) = sink.w(tag, message, error)
}

/** The sink nothing has to install. */
object StandardErrorSink : LogSink {
    override fun i(tag: String, message: String) {
        System.err.println("I/$tag: $message")
    }

    override fun w(tag: String, message: String, error: Throwable?) {
        System.err.println("W/$tag: $message")
        error?.printStackTrace()
    }
}
