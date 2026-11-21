package com.tmplayer.player

import android.view.SurfaceView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import com.tmplayer.data.PlayerEngine

// libVLC as an engine on iOS, in place of upstream's VlcEngine.kt, which is a Media3 SimpleBasePlayer
// over libVLC's Java binding. iOS has no libVLC: the app's engines are AVPlayer and mpv (docs/IOS.md),
// VlcRuntime (the build without libVLC) never gives a backend and EngineAvailability never offers
// VLC, so PlayerActivity never makes one. The class is here with upstream's names so the activity
// compiles unchanged; it drives a backend straight through and its [player] is a player that holds no
// video.

/** libVLC as an [Engine] over a [VlcBackend]: never made on iOS, where no backend exists. */
class VlcEngine(
    private val backend: VlcBackend,
    private val onRelease: () -> Unit = {},
    private val onActive: (Boolean) -> Unit = {},
) : Engine {

    override val kind: PlayerEngine get() = PlayerEngine.Vlc

    override val player: Player = NoVideoPlayer()

    /** What the media session shows; set by the activity before [prepare]. */
    var mediaItem: MediaItem = MediaItem.EMPTY

    private var released = false

    override fun prepare(source: EngineSource, startMs: Long) {
        if (released) return
        onActive(true)
        backend.open(source, startMs.coerceAtLeast(0L))
        backend.play()
    }

    override fun play() {
        if (!released) backend.play()
    }

    override fun pause() {
        if (!released) backend.pause()
    }

    override fun seekTo(positionMs: Long) {
        if (!released) backend.seekTo(positionMs.coerceAtLeast(0L))
    }

    override val positionMs: Long get() = if (released) 0L else backend.timeMs.coerceAtLeast(0L)

    override val durationMs: Long get() = if (released) 0L else backend.lengthMs.coerceAtLeast(0L)

    override val isPlaying: Boolean get() = !released && backend.isPlaying

    override fun tracks(): List<EngineTrack> = emptyList()

    override fun selectAudio(id: String) = Unit

    override fun selectSubtitle(id: String?) = Unit

    override fun addSubtitleFile(uri: String) {
        if (!released) backend.addSubtitle(uri)
    }

    override var speed: Float
        get() = backend.rate
        set(value) {
            backend.rate = PlaybackSpeed.sanitise(value)
        }

    override var volume: Float
        get() = backend.volumePercent / 100f
        set(value) {
            backend.volumePercent = (value.coerceIn(0f, 1f) * 100f).toInt()
        }

    override fun setAspect(widthOverHeight: Double?) = Unit

    override fun attachSurface(view: SurfaceView?) = Unit

    override fun setVideoEnabled(enabled: Boolean) {
        if (!released) backend.setVideoTrackEnabled(enabled)
    }

    /** The sound and subtitle offsets of the track picker's timing lines, later positive. */
    fun setDelays(audioMs: Long, subtitleMs: Long) {
        if (!released) backend.setDelays(audioMs * 1000, subtitleMs * 1000)
    }

    override fun stats(): EngineStats = EngineStats(engine = kind, decoder = "libVLC", width = backend.videoWidth, height = backend.videoHeight)

    override fun release() {
        if (released) return
        released = true
        backend.listener = null
        runCatching { backend.stop() }
        runCatching { backend.release() }
        onActive(false)
        runCatching { onRelease() }
    }

    /** Opens the same source again where it stood. */
    internal fun prepareAgain() = Unit

    /** Its item's metadata was written again (the player's private metadata, HX88): Now Playing reads it from the session. */
    fun metadataChanged() = Unit
}

/** A Media3 player with nothing loaded and nothing to draw: [VlcEngine]'s on iOS. Listeners are kept and never told. */
private class NoVideoPlayer : Player {
    private val listeners = mutableListOf<Player.Listener>()
    private var playWhenReady = false
    private var repeatMode = Player.REPEAT_MODE_OFF
    private var shuffle = false
    private var parameters = PlaybackParameters.DEFAULT
    private var trackSelection = TrackSelectionParameters.DEFAULT
    private var volume = 1f

    override fun addListener(listener: Player.Listener) {
        listeners += listener
    }

    override fun removeListener(listener: Player.Listener) {
        listeners -= listener
    }

    override fun setMediaItem(mediaItem: MediaItem) = Unit
    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) = Unit
    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) = Unit
    override fun setMediaItems(mediaItems: List<MediaItem>) = Unit
    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) = Unit
    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) = Unit
    override fun addMediaItem(mediaItem: MediaItem) = Unit
    override fun replaceMediaItem(index: Int, mediaItem: MediaItem) = Unit
    override fun clearMediaItems() = Unit
    override fun getMediaItemCount(): Int = 0
    override fun getMediaItemAt(index: Int): MediaItem = MediaItem.EMPTY
    override fun getCurrentMediaItem(): MediaItem? = null
    override fun getCurrentMediaItemIndex(): Int = 0
    override fun isCurrentMediaItemSeekable(): Boolean = false
    override fun isCurrentMediaItemLive(): Boolean = false
    override fun isCurrentMediaItemDynamic(): Boolean = false
    override fun getCurrentTimeline(): Timeline = Timeline.EMPTY
    override fun prepare() = Unit
    override fun play() {
        playWhenReady = true
    }

    override fun pause() {
        playWhenReady = false
    }

    override fun stop() = Unit
    override fun release() = Unit
    override fun getPlaybackState(): Int = Player.STATE_IDLE
    override fun getPlaybackSuppressionReason(): Int = Player.PLAYBACK_SUPPRESSION_REASON_NONE
    override fun isPlaying(): Boolean = false
    override fun isLoading(): Boolean = false
    override fun getPlayerError(): PlaybackException? = null
    override fun setPlayWhenReady(playWhenReady: Boolean) {
        this.playWhenReady = playWhenReady
    }

    override fun getPlayWhenReady(): Boolean = playWhenReady
    override fun setRepeatMode(repeatMode: Int) {
        this.repeatMode = repeatMode
    }

    override fun getRepeatMode(): Int = repeatMode
    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        shuffle = shuffleModeEnabled
    }

    override fun getShuffleModeEnabled(): Boolean = shuffle
    override fun seekTo(positionMs: Long) = Unit
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) = Unit
    override fun seekToDefaultPosition() = Unit
    override fun seekBack() = Unit
    override fun seekForward() = Unit
    override fun getSeekBackIncrement(): Long = 0L
    override fun getSeekForwardIncrement(): Long = 0L
    override fun hasNextMediaItem(): Boolean = false
    override fun hasPreviousMediaItem(): Boolean = false
    override fun seekToNextMediaItem() = Unit
    override fun seekToPreviousMediaItem() = Unit
    override fun seekToNext() = Unit
    override fun seekToPrevious() = Unit
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        parameters = playbackParameters
    }

    override fun getPlaybackParameters(): PlaybackParameters = parameters
    override fun setPlaybackSpeed(speed: Float) {
        parameters = PlaybackParameters(speed)
    }

    override fun getDuration(): Long = androidx.media3.common.C.TIME_UNSET
    override fun getCurrentPosition(): Long = 0L
    override fun getBufferedPosition(): Long = 0L
    override fun getBufferedPercentage(): Int = 0
    override fun getTotalBufferedDuration(): Long = 0L
    override fun getContentDuration(): Long = androidx.media3.common.C.TIME_UNSET
    override fun getContentPosition(): Long = 0L
    override fun getContentBufferedPosition(): Long = 0L
    override fun getCurrentTracks(): Tracks = Tracks.EMPTY
    override fun getTrackSelectionParameters(): TrackSelectionParameters = trackSelection
    override fun setTrackSelectionParameters(parameters: TrackSelectionParameters) {
        trackSelection = parameters
    }

    override fun getMediaMetadata(): MediaMetadata = MediaMetadata.EMPTY
    override fun getVideoSize(): VideoSize = VideoSize.UNKNOWN
    override fun getVolume(): Float = volume
    override fun setVolume(volume: Float) {
        this.volume = volume
    }

    override fun getAudioAttributes(): AudioAttributes = AudioAttributes.DEFAULT
    override fun getCurrentCues(): CueGroup = CueGroup.EMPTY_TIME_ZERO
    override fun getAvailableCommands(): Player.Commands = Player.Commands.EMPTY
    override fun isCommandAvailable(command: Int): Boolean = false
}
