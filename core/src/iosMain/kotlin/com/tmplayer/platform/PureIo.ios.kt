package com.tmplayer.platform

/**
 * The one IOException on iOS: the pure half's, and java.io.IOException for the JVM half compiled
 * against the stand ins (core/src/iosStandIns), so a catch in either half sees a throw from the
 * other, as on the JVM where both are java.io.IOException.
 */
actual open class IOException actual constructor(message: String?) : Exception(message) {
    constructor(message: String?, cause: Throwable?) : this(message) {
        if (cause != null) initCauseCompat(cause)
    }

    constructor(cause: Throwable?) : this(cause?.toString(), cause)

    constructor() : this(null as String?)

    private var causeCompat: Throwable? = null

    private fun initCauseCompat(cause: Throwable) {
        causeCompat = cause
    }

    override val cause: Throwable? get() = causeCompat ?: super.cause
}
