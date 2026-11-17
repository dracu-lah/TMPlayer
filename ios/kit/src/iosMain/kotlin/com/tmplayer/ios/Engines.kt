package com.tmplayer.ios

import com.tmplayer.ios.media3.EngineHandle
import com.tmplayer.ios.media3.PlaybackEngines

/**
 * The video engine Android's player plays on. Every ExoPlayer it builds (the Media3 stand ins,
 * com.tmplayer.ios.media3.EngineExoPlayer) asks here for a fresh engine, under upstream's
 * PlaybackEngine through the same adapter the rest of the app uses (NativePlaybackEngine).
 */
object Engines {
    /** Called by the app as it starts, with the engine VideoEngines chooses (iosApp, VideoEngines.swift). */
    fun install(make: () -> VideoEngine) {
        PlaybackEngines.make = {
            val engine = make()
            val native = NativePlaybackEngine(engine)
            EngineHandle(native, engine.surface, subtitles = native, readAhead = native)
        }
    }
}
