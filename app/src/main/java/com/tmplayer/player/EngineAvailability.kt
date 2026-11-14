package com.tmplayer.player

import com.tmplayer.data.DeviceFacts
import com.tmplayer.data.PlayerEngine

/**
 * Whether the VLC engine can run in this build (PR5): asked of [EngineSelector], the one place that
 * decides engines, with the library's presence as the only input. libVLC is not in the app yet
 * (PR2), so VLC is never offered; flipping [LIBRARY_PRESENT] with the library is the whole change.
 */
object EngineAvailability {

    private const val LIBRARY_PRESENT = false

    /** True when Settings may show VLC as a choice. */
    val vlcOffered: Boolean by lazy {
        val input = EngineInput(EngineChoice.Automatic, DeviceFacts(), vlcAvailable = LIBRARY_PRESENT)
        EngineSelector.toOffer(input, PlayerEngine.ExoPlayer) == PlayerEngine.Vlc
    }
}
