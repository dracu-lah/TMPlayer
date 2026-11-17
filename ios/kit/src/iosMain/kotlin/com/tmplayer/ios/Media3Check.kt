package com.tmplayer.ios

import androidx.compose.ui.platform.IosAppContext
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import com.tmplayer.ios.media3.EngineExoPlayer
import com.tmplayer.ios.media3.PipelineFeatures
import com.tmplayer.ios.media3.TextProbe
import com.tmplayer.player.AudioOffsetProcessor
import com.tmplayer.player.DelayedTextRenderer
import com.tmplayer.player.StateChangeOnlyPlayer
import com.tmplayer.player.VolumeBoostProcessor
import platform.UIKit.UIView

/** One track as the player sees it through Media3, for the test. */
class Media3Track internal constructor(
    /** "audio", "text" or "video". */
    val type: String,
    val language: String?,
    val selected: Boolean,
)

/** What the player sees through Media3 now, for the test. */
class Media3Snapshot internal constructor(
    /** Media3's STATE_IDLE (1), BUFFERING (2), READY (3) or ENDED (4). */
    val playbackState: Int,
    val playWhenReady: Boolean,
    val isPlaying: Boolean,
    val positionMs: Long,
    /** -1 while unknown (Media3's C.TIME_UNSET). */
    val durationMs: Long,
    val videoWidth: Int,
    val videoHeight: Int,
    val error: String?,
    val tracks: List<Media3Track>,
    // What the engine was asked for from the pipeline's features (PipelineFeatures).
    val volumeBoost: Boolean,
    val nightMode: Boolean,
    val audioDelayMs: Long,
    val subtitleDelayMs: Long,
    val downmix: Boolean,
)

/**
 * The Media3 bridge's test handle (iosApp/TMPlayerTests/Media3BridgeTests.swift): an ExoPlayer
 * built as Android's player builds one, wrapped in upstream's own StateChangeOnlyPlayer, driven
 * through Media3's calls, with every listener callback it receives written down.
 */
class Media3Check {
    private val exo: ExoPlayer = ExoPlayer.Builder(IosAppContext)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .build()
    private val player: Player = StateChangeOnlyPlayer(exo)
    private val heard = ArrayList<String>()
    private val heardThroughWrapper = ArrayList<String>()

    // The pipeline as Android's player builds it: upstream's own processors and text renderer.
    private val boost = VolumeBoostProcessor()
    private val offset = AudioOffsetProcessor()
    private val mixer = ChannelMixingAudioProcessor()
    private var subtitleDelayUs = 0L
    private val text = DelayedTextRenderer(TextProbe()) { subtitleDelayUs }

    init {
        exo.addListener(Recorder(heard, exo))
        player.addListener(Recorder(heardThroughWrapper, player))
        (exo as? EngineExoPlayer)?.attachPipeline(PipelineFeatures(listOf(boost, offset, mixer), listOf(text)))
    }

    /** As the player's menu sets Volume boost: on its processor. */
    fun setBoost(on: Boolean) {
        boost.enabled = on
    }

    /** As the player's menu sets Night mode: on the same processor. */
    fun setNight(on: Boolean) {
        boost.night = on
    }

    /** As the player's sync panel moves the sound: on its offset processor. */
    fun setAudioOffsetMs(ms: Long) {
        offset.offsetUs = ms * 1000
    }

    /** As the player's sync panel moves the subtitles: the delay its text renderer reads. */
    fun setSubtitleDelayMs(ms: Long) {
        subtitleDelayUs = ms * 1000
    }

    /** As the player folds 5.1 to stereo, or leaves it: the mixer's matrix for six channels. */
    fun foldSurround(on: Boolean) {
        mixer.putChannelMixingMatrix(
            if (on) {
                ChannelMixingMatrix(6, 2, FloatArray(12) { if (it % 2 == (it / 2) % 2) 0.5f else 0f })
            } else {
                ChannelMixingMatrix(6, 6, FloatArray(36) { if (it % 7 == 0) 1f else 0f })
            },
        )
    }

    /** Writes down every callback, and whether it named the player it was registered on. */
    private class Recorder(private val into: MutableList<String>, private val registeredOn: Player) : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            into += "state $playbackState"
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            into += "playing $isPlaying"
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            into += "playWhenReady $playWhenReady"
        }

        override fun onTracksChanged(tracks: Tracks) {
            into += "tracks ${tracks.groups.size}"
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            into += "size ${videoSize.width}x${videoSize.height}"
        }

        override fun onRenderedFirstFrame() {
            into += "first frame"
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            into += "timeline"
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            into += "seek ${newPosition.positionMs}"
        }

        override fun onPlayerError(error: PlaybackException) {
            into += "error"
        }

        override fun onEvents(player: Player, events: Player.Events) {
            // A wrapping player hands its listeners itself, as Media3's ForwardingPlayer does.
            if (player !== registeredOn) into += "wrong player"
        }
    }

    /** The view the engine draws in. */
    fun surface(): UIView? = (exo as? EngineExoPlayer)?.engineSurface()

    /** As Android's player opens a video: the item, prepare, and play when [playWhenReady]. */
    fun open(uri: String, playWhenReady: Boolean) {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.playWhenReady = playWhenReady
        player.prepare()
    }

    fun play() = player.play()

    fun pause() = player.pause()

    fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    fun seekForward() = player.seekForward()

    fun setExactSeeks(exact: Boolean) = exo.setSeekParameters(if (exact) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)

    fun setSpeed(speed: Float) = player.setPlaybackSpeed(speed)

    fun setVolume(volume: Float) {
        player.volume = volume
    }

    /** The soundtrack in [language], as Android's track picker chooses one: an override on its group. */
    fun chooseAudio(language: String) {
        val group = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.getTrackFormat(0).language == language } ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
    }

    /** Subtitles off or back on, as Android's picker does: the text type disabled or not. */
    fun subtitles(on: Boolean) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !on)
            .setPreferredTextLanguage(if (on) "eng" else null)
            .build()
    }

    fun snapshot(): Media3Snapshot {
        val size = player.videoSize
        val engine = (exo as? EngineExoPlayer)?.engineStatus()
        return Media3Snapshot(
            playbackState = player.playbackState,
            playWhenReady = player.playWhenReady,
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition,
            durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: -1,
            videoWidth = size.width,
            videoHeight = size.height,
            error = player.playerError?.message,
            tracks = player.currentTracks.groups.map { group ->
                Media3Track(
                    type = when (group.type) {
                        C.TRACK_TYPE_AUDIO -> "audio"
                        C.TRACK_TYPE_TEXT -> "text"
                        C.TRACK_TYPE_VIDEO -> "video"
                        else -> "other"
                    },
                    language = group.getTrackFormat(0).language,
                    selected = group.isSelected,
                )
            },
            volumeBoost = engine?.volumeBoost == true,
            nightMode = engine?.nightMode == true,
            audioDelayMs = engine?.audioDelayMs ?: 0,
            subtitleDelayMs = engine?.subtitleDelayMs ?: 0,
            downmix = engine?.downmix == true,
        )
    }

    /** Every callback the ExoPlayer's own listener heard, in order ("state 3", "playing true", "first frame", ...). */
    fun heard(): List<String> = heard.toList()

    /** What a listener of upstream's StateChangeOnlyPlayer around it heard: state changes only, by its design. */
    fun heardThroughWrapper(): List<String> = heardThroughWrapper.toList()

    fun release() = player.release()

    companion object {
        /** The location the engine is given for a media item's URI. */
        fun sourceOf(uri: String): String = EngineExoPlayer.sourceOf(MediaItem.fromUri(uri))
    }
}

// The Media3 getters this file reads as properties, as Android's player reads them (its package
// has them generated, ios/src/iosStandIns/generate-getters.py).
private var Player.playWhenReady: Boolean
    get() = getPlayWhenReady()
    set(value) = setPlayWhenReady(value)
private var Player.volume: Float
    get() = getVolume()
    set(value) = setVolume(value)
private var Player.trackSelectionParameters: androidx.media3.common.TrackSelectionParameters
    get() = getTrackSelectionParameters()
    set(value) = setTrackSelectionParameters(value)
private val Player.currentTracks: Tracks get() = getCurrentTracks()
private val Player.videoSize: VideoSize get() = getVideoSize()
private val Player.playbackState: Int get() = getPlaybackState()
private val Player.isPlaying: Boolean get() = isPlaying()
private val Player.currentPosition: Long get() = getCurrentPosition()
private val Player.duration: Long get() = getDuration()
private val Player.playerError: PlaybackException? get() = getPlayerError()
private val Tracks.Group.isSelected: Boolean get() = isSelected()
