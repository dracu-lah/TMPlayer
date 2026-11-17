package com.tmplayer.data

/**
 * What a crash report is told about the playback, in place of core's CrashContext, which builds
 * Sentry events (Sentry's JVM SDK is not in the iOS build, so no crash report is made). The player
 * still says what plays and when it stops, as it does on Android; nothing keeps it.
 */
object CrashContext {
    /** The engines a report may name, upstream's. */
    val ENGINES: Set<String> = setOf("media3", "libvlc", "mpv", "external")

    @Suppress("UNUSED_PARAMETER")
    fun playing(engine: String, decoder: String, codec: String, width: Int, height: Int) = Unit

    fun stopped() = Unit
}
