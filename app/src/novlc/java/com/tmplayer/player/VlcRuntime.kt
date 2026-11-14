package com.tmplayer.player

import android.content.Context

/**
 * The stand-in for a build without libVLC (the default and the F-Droid one, see `vlcBuild` in
 * app/build.gradle.kts): no library, so no VLC engine, and Settings offers none.
 */
object VlcRuntime {
    const val BUNDLED: Boolean = false

    /** The native libraries this build would load, for [EngineAvailability]'s check on disk. */
    val libraries: List<String> = emptyList()

    @Suppress("UNUSED_PARAMETER")
    fun backend(context: Context, options: VlcOptions): VlcBackend? = null
}
