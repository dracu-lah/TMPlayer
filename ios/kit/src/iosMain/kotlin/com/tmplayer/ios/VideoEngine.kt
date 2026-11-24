package com.tmplayer.ios

import com.tmplayer.desktop.player.TrackType
import com.tmplayer.player.Stereo3d
import com.tmplayer.player.SubtitleStyle
import com.tmplayer.player.VideoScale
import platform.UIKit.UIView

/**
 * A video engine on iOS: libmpv today, VLCKit, AVPlayer or any other tomorrow.
 *
 * The rules are upstream's. The desktop player's PlaybackEngine (desktop/.../player/PlaybackEngine.kt,
 * compiled for iOS unchanged) says what every command means, and TMPlayer's player speaks only
 * that; this interface is the same commands in a shape Swift can implement. What an engine must do
 * for each is written in docs/IOS.md, and EngineConformanceTests checks it.
 *
 * An engine carries out commands and reports what happens, nothing more. What a player decides
 * (what play does after the end, the tracks a video opens with, a resumed video's tracks, raising
 * the volume out of mute, how far a seek may go) is decided once, for every engine, by
 * [NativePlaybackEngine].
 *
 * Commands come on the main thread and return at once: an engine never blocks the main thread and
 * never throws. Reports may be sent from any thread.
 */
interface VideoEngine {
    /** The view the picture is drawn in. The player sizes it; the engine follows its size. */
    val surface: UIView

    /** What the engine can do beyond the commands every engine carries out. */
    val capabilities: Set<EngineCapability>

    /**
     * Opens [source] (a path, a URL or `tdfile://<file id>`) at [startAtMs] and reports to
     * [reports] until [close]: the tracks first, then [EngineReports.opened], or
     * [EngineReports.failed] when it cannot.
     */
    fun open(source: String, startAtMs: Long, options: EngineOpenOptions, reports: EngineReports)

    fun play()

    fun pause()

    /** Stops and lets go of the source, so nothing more is read for it; [open] again to carry on. */
    fun stop()

    /** To [positionMs]: exactly, or, when [exact] is false, to the key frame at or before it. */
    fun seekTo(positionMs: Long, exact: Boolean)

    /** By [deltaMs] from where it stands, to a key frame on the side it moves to. */
    fun seekBy(deltaMs: Long)

    /** One frame on or back, paused. */
    fun frameStep(forward: Boolean)

    fun setSpeed(speed: Float)

    /** 0 to 100 as the viewer sees it; with Volume boost on, 100 is the boosted full volume. */
    fun setVolume(percent: Int)

    fun setMuted(muted: Boolean)

    /** A track by its [EngineTrack.id], or [EngineValues.NO_TRACK] to turn the type off. */
    fun selectTrack(type: TrackType, id: Int)

    /** The picture's shape: fit, fill, stretch or a fixed ratio. */
    fun setScale(scale: VideoScale)

    /** The free zoom over the shape; 0, 0, 0 is none (upstream's PlaybackEngine.setZoom). */
    fun setZoom(zoom: Double, panX: Double, panY: Double)

    /** Every soundtrack mixed down to two channels. */
    fun setDownmix(stereo: Boolean)

    /** The volume scale lifted, so full volume is louder than the file's own. */
    fun setVolumeBoost(on: Boolean)

    /** The loud and the quiet brought closer. */
    fun setNightMode(on: Boolean)

    /** A 3D file's two eyes as one anaglyph, or one eye alone. */
    fun setStereo3d(stereo: Stereo3d)

    /** Size, box and position for plain text subtitles; a file's own styled ones are left alone. */
    fun setSubtitleStyle(style: SubtitleStyle)

    /**
     * Whether the engine draws text subtitles itself (true until told otherwise, for every file it
     * opens after) or leaves them to the player, which draws them from
     * [EngineReports.subtitleText] as Android's player does.
     * Picture subtitles (PGS, VobSub, DVB) the engine draws either way: they cannot be handed out.
     */
    fun setTextSubtitlesShown(shown: Boolean)

    /** Subtitles moved against the picture; positive is later. */
    fun setSubtitleDelay(ms: Long)

    /** Sound moved against the picture; positive is later. */
    fun setAudioDelay(ms: Long)

    /** Loads a subtitle file beside the video's own tracks and selects it; false when it cannot. */
    fun addSubtitle(path: String): Boolean

    /** The frame on screen as a PNG at [path]; [done] on the main thread, true once it is there. */
    fun screenshot(path: String, withSubtitles: Boolean, done: (Boolean) -> Unit)

    /** Repeats from [startMs] to [endMs] once playback reaches it; [EngineValues.NO_LOOP] for both plays straight through. */
    fun setAbLoop(startMs: Long, endMs: Long)

    /** What is actually playing, read from the engine, for the Playback details panel. */
    fun facts(): EngineFacts

    /** Stops and lets go of everything, without blocking the main thread; nothing is reported after. */
    fun close()
}

/**
 * What Apple's player alone does (WP-31), on an engine that holds it: AirPlay to a TV and picture
 * in picture. The engine the player sees (ChoosingEngine) implements it and answers for the engine
 * playing; [usesApplePlayer] is false while mpv holds the video, and then the other commands do
 * nothing. The player reaches it through android.app.IosExternalPlayback, from the More panel's rows.
 */
interface ExternalPlaybackEngine {
    /** True while Apple's player holds the video: AirPlay and picture in picture work for it alone. */
    val usesApplePlayer: Boolean

    /** Opens iOS's list of AirPlay screens, over the player. */
    fun showAirPlayPicker()

    /** Starts the corner window; false when it cannot be started. */
    fun startPictureInPicture(): Boolean

    fun stopPictureInPicture()

    /** Whether the system may start the corner window by itself when the app goes away while this plays. */
    fun setAutoPictureInPicture(on: Boolean)

    /**
     * Whether Apple's player may send the video to an AirPlay screen at all, from any button (SEC2-01):
     * false until the player knows the chat allows saving. False pauses a video already on another screen.
     */
    fun setExternalPlaybackAllowed(on: Boolean)

    /** Called on the main thread when the corner window opens (true) or closes (false). */
    var onPictureInPictureChanged: ((Boolean) -> Unit)?
}

/** The values the commands take for "none". */
object EngineValues {
    /** For [VideoEngine.selectTrack]: no track of that type. */
    const val NO_TRACK = -1

    /** For [VideoEngine.setAbLoop]: no loop. */
    const val NO_LOOP = -1L
}

/**
 * What an engine can do beyond the commands every engine carries out (open, play, pause, stop,
 * seek, speed, volume, mute, tracks, scale, close). A command for something an engine does not
 * list is accepted and does nothing; the conformance test checks only what is listed.
 */
enum class EngineCapability {
    FRAME_STEP,
    ZOOM,
    DOWNMIX,
    VOLUME_BOOST,
    NIGHT_MODE,
    STEREO_3D,
    SUBTITLE_STYLE,
    SUBTITLE_DELAY,
    AUDIO_DELAY,
    EXTERNAL_SUBTITLES,
    SCREENSHOT,
    AB_LOOP,
}

/** How a video opens: the settings the player read beforehand, carried out from the first frame. */
data class EngineOpenOptions(
    val speed: Float = 1f,
    val volume: Int = 100,
    val muted: Boolean = false,
    val volumeBoost: Boolean = false,
    val downmix: Boolean = false,
    /** Decode in software, for a file the hardware decoder gets wrong. */
    val softwareDecoding: Boolean = false,
    val subtitleDelayMs: Long = 0,
    val audioDelayMs: Long = 0,
    /** The soundtrack's language in its spellings, to start on it; empty for the file's own pick. */
    val audioLanguages: List<String> = emptyList(),
    /** The subtitles' language in its spellings, to start on it; empty for the file's own pick. */
    val subtitleLanguages: List<String> = emptyList(),
    /** Start with no subtitles; the player may turn some on once the tracks are known. */
    val subtitlesOff: Boolean = false,
    /** How many seconds ahead to read, as Android's player buffers; 0 leaves it to the engine. */
    val readAheadSeconds: Int = 0,
    /** Once that far ahead, read again only when under this many seconds. */
    val readAgainBelowSeconds: Int = 0,
    /** The bytes held ahead, and behind (what already played), within Android's budget; 0 for the engine's own. */
    val aheadBytes: Long = 0,
    val behindBytes: Long = 0,
    /** After running dry, the seconds to gather before playing on; 0 for the engine's own. */
    val rebufferSeconds: Double = 0.0,
)

/** One track of the open file, as the engine knows it. */
data class EngineTrack(
    val id: Int,
    val type: TrackType,
    val title: String?,
    val language: String?,
    val codec: String?,
    /** The sound's channel count, 0 when unknown or not sound. */
    val channels: Int,
    val selected: Boolean,
    val isDefault: Boolean,
    val external: Boolean,
    val forced: Boolean,
)

/** One chapter of the open file. */
data class EngineChapter(val startMs: Long, val title: String?)

/**
 * What is actually playing, read from the engine for the Playback details panel. Each field is
 * empty (0 or "") where the engine does not know it; the player words the panel itself.
 */
class EngineFacts {
    var videoWidth: Int = 0
    var videoHeight: Int = 0
    var framesPerSecond: Double = 0.0
    var videoCodec: String = ""
    var pixelFormat: String = ""

    /** The hardware decoder in use, "" for software. */
    var hardwareDecoder: String = ""
    var audioCodec: String = ""
    var audioChannels: Int = 0
    var sampleRate: Int = 0
    var container: String = ""
    var bufferedSeconds: Double = 0.0
    var videoOutput: String = ""
    var audioOutput: String = ""

    /** The engine and its version, "mpv 0.40.0" for example. */
    var engine: String = ""
    var engineVersion: String = ""

    /** The decoding library and its version where the engine has one apart, FFmpeg's for mpv. */
    var decoder: String = ""
    var decoderVersion: String = ""

    // What the commands set, as the engine holds it now.
    var speed: Double = 1.0

    /** 0 to 100 as the viewer sees it, Volume boost taken out. */
    var volume: Int = 100
    var muted: Boolean = false
    var subtitleDelayMs: Long = 0
    var audioDelayMs: Long = 0
}

/**
 * Where an engine reports what happens, from any thread. The player made it for one [VideoEngine.open];
 * after the engine is closed, or opens something else, what it sends here is dropped.
 */
class EngineReports internal constructor(private val to: NativePlaybackEngine) {
    /** The open file's tracks, all of them, each time they change. */
    fun tracks(tracks: List<EngineTrack>) = to.onTracks(this, tracks)

    fun chapters(chapters: List<EngineChapter>) = to.onChapters(this, chapters)

    /** The file is loaded and its length known (0 when it has none, a live stream). */
    fun opened(durationMs: Long) = to.onOpened(this, durationMs)

    /** Where playback is, a few times a second while it moves; [bufferedMs] is where the buffer ends. */
    fun progress(positionMs: Long, durationMs: Long, bufferedMs: Long) = to.onProgress(this, positionMs, durationMs, bufferedMs)

    /** Paused or playing, whichever way it came about. */
    fun paused(paused: Boolean) = to.onPaused(this, paused)

    /** Waiting for bytes, or not any more. */
    fun buffering(buffering: Boolean) = to.onBuffering(this, buffering)

    /** A seek has landed at [positionMs] and the picture there is on screen. */
    fun seekLanded(positionMs: Long) = to.onSeekLanded(this, positionMs)

    /** The picture's size, once known and each time it changes. */
    fun videoSize(width: Int, height: Int) = to.onVideoSize(this, width, height)

    /**
     * The text subtitle due now, each time it changes, drawn by the engine or not: ASS event
     * markup (`\N` a new line, override tags in braces), which is how mpv hands SubRip too; an
     * engine without markup sends plain text. Null or empty when no line is due.
     */
    fun subtitleText(text: String?) = to.onSubtitleText(this, text)

    /** Playback reached the end and stopped there, paused on the last frame. */
    fun ended() = to.onEnded(this)

    /** The file cannot be opened or played on; [message] says why, for the log and the error panel. */
    fun failed(message: String) = to.onFailed(this, message)
}
