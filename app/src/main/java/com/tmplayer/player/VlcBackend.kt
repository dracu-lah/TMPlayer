package com.tmplayer.player

import android.view.SurfaceView

/**
 * The few calls [VlcEngine] makes of libVLC (PR2), so the engine's own rules (the states, the
 * tracks by language, a seek before the file is open, what carries over) run on the JVM in the
 * contract test over a stand-in, and the library itself is only touched in `src/vlc`
 * ([VlcRuntime]). Every call is made on the main thread; events are delivered there too.
 */
interface VlcBackend {

    /** Opens [source] and starts loading it at [startMs]; [play] is what starts the reading. */
    fun open(source: EngineSource, startMs: Long)

    fun play()

    fun pause()

    fun stop()

    fun seekTo(positionMs: Long)

    val timeMs: Long

    /** The length, or 0 or less while it is not known. */
    val lengthMs: Long

    val isPlaying: Boolean

    /** The file's soundtracks and subtitles as libVLC lists them (its "Disable" entry left out). */
    fun tracks(): List<VlcTrack>

    /** libVLC's id of the soundtrack playing, or -1. */
    val audioTrack: Int

    /** libVLC's id of the subtitle shown, or -1 for none. */
    val spuTrack: Int

    fun setAudioTrack(id: Int): Boolean

    /** -1 hides the subtitles. */
    fun setSpuTrack(id: Int): Boolean

    fun addSubtitle(uri: String): Boolean

    var rate: Float

    /** 0 to 100, which is the file's own level in libVLC. */
    var volumePercent: Int

    /** `16:9` and the like, or null for the video's own. */
    fun setAspect(ratio: String?)

    fun attach(view: SurfaceView?)

    fun setVideoTrackEnabled(enabled: Boolean)

    /** The sound's and the subtitles' offsets, in microseconds, later positive. */
    fun setDelays(audioUs: Long, subtitleUs: Long)

    /** The picture's size, or 0 by 0 while it is not known. */
    val videoWidth: Int
    val videoHeight: Int

    var listener: ((VlcEvent) -> Unit)?

    /** Lets go of the player, the media and the library instance. Never throws. */
    fun release()
}

/** One soundtrack or subtitle as libVLC lists it. */
data class VlcTrack(val id: Int, val type: EngineTrack.Type, val name: String, val language: String?)

/** What libVLC says while it plays, cut down to what [VlcEngine] acts on. */
sealed interface VlcEvent {
    data object Opening : VlcEvent
    data class Buffering(val percent: Float) : VlcEvent
    data object Playing : VlcEvent
    data object Paused : VlcEvent
    data object Stopped : VlcEvent
    data object Ended : VlcEvent

    /** libVLC's EncounteredError: it says nothing more than that, so the log has the rest. */
    data object Error : VlcEvent
    data object TracksChanged : VlcEvent
    data object VideoOut : VlcEvent
}
