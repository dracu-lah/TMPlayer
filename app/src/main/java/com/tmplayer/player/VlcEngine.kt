package com.tmplayer.player

import android.os.Looper
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.tmplayer.data.PlayerEngine

/**
 * libVLC as an [Engine] (PR2), over a [VlcBackend] so its rules run on the JVM in the contract
 * test. A video not fully downloaded is read from a [com.tmplayer.cast.LoopbackStream] URL (the
 * Java binding has no input callbacks), a finished one by its path or descriptor.
 *
 * [player] is a Media3 [Player] ([VlcPlayer]) over this engine, so the activity's overlay, its
 * listener, the track picker and the media session drive VLC as they drive ExoPlayer. VLC draws
 * its own subtitles (libass, with FriBidi and HarfBuzz for right to left and joined scripts)
 * into the surface, so the activity's subtitle view gets no lines from it.
 *
 * libVLC has to read to open a file, so [prepare] always starts it and, when the viewer had not
 * asked to play, pauses it as soon as it plays ([pauseWhenPlaying]).
 */
@OptIn(UnstableApi::class)
class VlcEngine(
    private val backend: VlcBackend,
    looper: Looper = Looper.getMainLooper(),
    /** Runs once after [release]: closes the loopback stream and clears the crash marker. */
    private val onRelease: () -> Unit = {},
    /** VLC is opening or meant to play (true), or not (false): the native crash mark ([EngineCrashWatch]). */
    private val onActive: (Boolean) -> Unit = {},
) : Engine {

    override val kind: PlayerEngine get() = PlayerEngine.Vlc

    private val adapter = VlcPlayer(this, looper)

    override val player: Player get() = adapter

    /** What the media session and the notification show; set by the activity before [prepare]. */
    var mediaItem: MediaItem = MediaItem.EMPTY

    internal var state: Int = Player.STATE_IDLE
        private set
    internal var playWhenReady: Boolean = false
        private set
    internal var error: PlaybackException? = null
        private set
    internal var source: EngineSource? = null
        private set

    private var released = false
    private var pauseWhenPlaying = false
    private var lastPositionMs = 0L
    private var firstFrameDue = false
    private var firstFrameSent = false
    private var pendingCarry: EngineCarry? = null

    init {
        backend.listener = { event -> runCatching { onEvent(event) } }
    }

    override fun prepare(source: EngineSource, startMs: Long) {
        if (released) return
        this.source = source
        error = null
        state = Player.STATE_BUFFERING
        lastPositionMs = startMs.coerceAtLeast(0L)
        firstFrameSent = false
        active(true)
        backend.open(source, lastPositionMs)
        pauseWhenPlaying = !playWhenReady
        backend.play()
        changed()
    }

    /** Opens the same source again where it stood: Try again on the status sheet. */
    internal fun prepareAgain() {
        val again = source ?: return
        prepare(again, positionMs)
    }

    override fun play() {
        if (released) return
        playWhenReady = true
        pauseWhenPlaying = false
        active(true)
        if (state == Player.STATE_ENDED) seekTo(0L)
        if (source != null && state != Player.STATE_IDLE) backend.play()
        changed()
    }

    override fun pause() {
        if (released) return
        playWhenReady = false
        active(false)
        if (source != null) backend.pause()
        changed()
    }

    override fun seekTo(positionMs: Long) {
        if (released) return
        val length = durationMs
        val target = positionMs.coerceAtLeast(0L).let { if (length > 0) it.coerceAtMost(length) else it }
        lastPositionMs = target
        if (source == null || state == Player.STATE_IDLE) return changed()
        backend.seekTo(target)
        if (state == Player.STATE_ENDED) state = Player.STATE_BUFFERING
        changed()
    }

    override val positionMs: Long
        get() {
            if (released || source == null) return lastPositionMs
            if (state == Player.STATE_ENDED && durationMs > 0) return durationMs
            val now = runCatching { backend.timeMs }.getOrDefault(-1L)
            if (now >= 0) lastPositionMs = now
            return lastPositionMs
        }

    override val durationMs: Long
        get() = if (released) 0L else runCatching { backend.lengthMs }.getOrDefault(0L).coerceAtLeast(0L)

    override val isPlaying: Boolean
        get() = !released && playWhenReady && state == Player.STATE_READY && error == null

    override fun tracks(): List<EngineTrack> {
        if (released) return emptyList()
        val audio = runCatching { backend.audioTrack }.getOrDefault(-1)
        val spu = runCatching { backend.spuTrack }.getOrDefault(-1)
        return runCatching { backend.tracks() }.getOrDefault(emptyList()).map { track ->
            EngineTrack(
                id = track.id.toString(),
                type = track.type,
                language = track.language,
                label = track.name,
                selected = track.id == if (track.type == EngineTrack.Type.Audio) audio else spu,
            )
        }
    }

    override fun selectAudio(id: String) {
        val vlcId = id.toIntOrNull() ?: return
        if (released) return
        runCatching { backend.setAudioTrack(vlcId) }
        changed()
    }

    override fun selectSubtitle(id: String?) {
        if (released) return
        val vlcId = if (id == null) -1 else id.toIntOrNull() ?: return
        runCatching { backend.setSpuTrack(vlcId) }
        changed()
    }

    override fun addSubtitleFile(uri: String) {
        if (released || uri.isBlank()) return
        runCatching { backend.addSubtitle(uri) }
    }

    override var speed: Float
        get() = if (released) 1f else runCatching { backend.rate }.getOrDefault(1f).takeIf { it > 0f } ?: 1f
        set(value) {
            if (released) return
            runCatching { backend.rate = PlaybackSpeed.sanitise(value) }
            changed()
        }

    override var volume: Float
        get() = if (released) 1f else (runCatching { backend.volumePercent }.getOrDefault(100) / 100f).coerceIn(0f, 1f)
        set(value) {
            if (released) return
            // libVLC takes whole per cents: a quiet Player volume rounds, and never down to silence.
            val percent = kotlin.math.round(value.coerceIn(0f, 1f) * 100).toInt()
            runCatching { backend.volumePercent = if (value > 0f) percent.coerceAtLeast(1) else 0 }
            changed()
        }

    override fun setAspect(widthOverHeight: Double?) {
        if (released) return
        runCatching { backend.setAspect(aspectText(widthOverHeight)) }
    }

    override fun attachSurface(view: SurfaceView?) {
        if (released) return
        runCatching { backend.attach(view) }
    }

    override fun setVideoEnabled(enabled: Boolean) {
        if (released) return
        runCatching { backend.setVideoTrackEnabled(enabled) }
    }

    /**
     * The sound and subtitle offsets of the track picker's timing lines ([SyncDelays]): later is
     * positive, as ExoPlayer's are. libVLC takes them in microseconds.
     */
    fun setDelays(audioMs: Long, subtitleMs: Long) {
        if (released) return
        runCatching { backend.setDelays(audioMs.coerceIn(-MAX_DELAY_MS, MAX_DELAY_MS) * 1000, subtitleMs.coerceIn(-MAX_DELAY_MS, MAX_DELAY_MS) * 1000) }
    }

    override fun stats(): EngineStats = EngineStats(
        engine = kind,
        decoder = "libVLC",
        width = videoWidth,
        height = videoHeight,
    )

    override fun release() {
        if (released) return
        released = true
        backend.listener = null
        runCatching { backend.stop() }
        runCatching { backend.release() }
        state = Player.STATE_IDLE
        active(false)
        runCatching { onRelease() }
        changed()
    }

    override fun restore(carry: EngineCarry): Boolean {
        val done = super.restore(carry)
        pendingCarry = if (done) null else carry
        return done
    }

    internal val videoWidth: Int get() = if (released) 0 else runCatching { backend.videoWidth }.getOrDefault(0).coerceAtLeast(0)
    internal val videoHeight: Int get() = if (released) 0 else runCatching { backend.videoHeight }.getOrDefault(0).coerceAtLeast(0)

    /** True once, on the first read after the picture first showed: Media3's onRenderedFirstFrame. */
    internal fun takeFirstFrame(): Boolean {
        if (!firstFrameDue) return false
        firstFrameDue = false
        return true
    }

    private fun onEvent(event: VlcEvent) {
        if (released) return
        when (event) {
            VlcEvent.Opening -> if (state != Player.STATE_READY) state = Player.STATE_BUFFERING
            is VlcEvent.Buffering -> state = if (event.percent < 100f) Player.STATE_BUFFERING else Player.STATE_READY
            VlcEvent.Playing -> {
                state = Player.STATE_READY
                if (pauseWhenPlaying) {
                    pauseWhenPlaying = false
                    runCatching { backend.pause() }
                    active(false)
                }
            }
            VlcEvent.Paused -> if (state == Player.STATE_BUFFERING) state = Player.STATE_READY
            VlcEvent.Ended -> {
                state = Player.STATE_ENDED
                active(false)
            }
            VlcEvent.Stopped -> if (state != Player.STATE_ENDED && error == null) state = Player.STATE_IDLE
            VlcEvent.Error -> {
                error = PlaybackException(VLC_ERROR, null, PlaybackException.ERROR_CODE_DECODING_FAILED)
                state = Player.STATE_IDLE
                // Failed, not crashed: the process lives on, so the crash mark goes.
                active(false)
            }
            VlcEvent.TracksChanged -> pendingCarry?.let { restore(it) }
            VlcEvent.VideoOut -> if (!firstFrameSent && videoWidth > 0) {
                firstFrameSent = true
                firstFrameDue = true
            }
        }
        changed()
    }

    private fun changed() {
        adapter.refresh()
    }

    /** The session reads [mediaItem] again: its metadata changed (HX88, the private one while hidden). */
    internal fun metadataChanged() = changed()

    private fun active(on: Boolean) {
        runCatching { onActive(on) }
    }

    companion object {
        /** The message of a libVLC failure, which is how the activity tells it from ExoPlayer's. */
        const val VLC_ERROR = "libVLC could not play this video"

        /** An offset beyond a minute is a mistake in the stored value, not a delay. */
        const val MAX_DELAY_MS = 60_000L

        /**
         * A shape as libVLC takes it: the smallest `w:h` within a thousandth of [widthOverHeight]
         * (`16:9`, `47:20` for 2.35), or null for the video's own or a ratio past any screen's.
         */
        fun aspectText(widthOverHeight: Double?): String? {
            val ratio = widthOverHeight?.takeIf { it.isFinite() && it in 0.1..10.0 } ?: return null
            for (height in 1..100) {
                val width = Math.round(ratio * height)
                if (width > 0 && kotlin.math.abs(width.toDouble() / height - ratio) <= ratio / 1000) return "$width:$height"
            }
            return "${Math.round(ratio * 100)}:100"
        }
    }
}

/**
 * [VlcEngine] as a Media3 [Player], so everything that drives ExoPlayer (the overlay, the
 * activity's listener, the track picker, the media session and its notification) drives VLC too.
 * Each soundtrack and subtitle is a group of one, its id `vlc-a-<id>` or `vlc-s-<id>`, so a
 * track override names the libVLC track. No picture group is listed: libVLC picks and draws the
 * picture itself, and the activity's "no decoder for the picture" check is ExoPlayer's.
 */
@OptIn(UnstableApi::class)
internal class VlcPlayer(private val engine: VlcEngine, looper: Looper) : SimpleBasePlayer(looper) {

    private var parameters: TrackSelectionParameters = TrackSelectionParameters.DEFAULT

    fun refresh() {
        runCatching { invalidateState() }
    }

    override fun getState(): State {
        val hasSource = engine.source != null
        val builder = State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlayWhenReady(engine.playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (hasSource) engine.state else Player.STATE_IDLE)
            .setPlayerError(engine.error)
            .setIsLoading(engine.state == Player.STATE_BUFFERING)
            .setPlaybackParameters(PlaybackParameters(engine.speed))
            .setVolume(engine.volume)
            .setTrackSelectionParameters(parameters)
        val width = engine.videoWidth
        val height = engine.videoHeight
        if (width > 0 && height > 0) builder.setVideoSize(VideoSize(width, height))
        if (hasSource) {
            val length = engine.durationMs
            builder.setPlaylist(
                listOf(
                    MediaItemData.Builder(UID)
                        .setMediaItem(engine.mediaItem)
                        .setDurationUs(if (length > 0) length * 1000 else C.TIME_UNSET)
                        .setIsSeekable(true)
                        .setTracks(media3Tracks())
                        .build(),
                ),
            )
                .setCurrentMediaItemIndex(0)
                .setContentPositionMs { engine.positionMs }
                .setContentBufferedPositionMs { engine.positionMs }
        }
        if (engine.takeFirstFrame()) builder.setNewlyRenderedFirstFrame(true)
        return builder.build()
    }

    private fun media3Tracks(): Tracks {
        val groups = engine.tracks().map { track ->
            val audio = track.type == EngineTrack.Type.Audio
            val format = Format.Builder()
                .setId(track.id)
                .setLabel(track.label)
                .setLanguage(track.language)
                .setSampleMimeType(if (audio) MimeTypes.AUDIO_UNKNOWN else MimeTypes.TEXT_UNKNOWN)
                .build()
            Tracks.Group(
                TrackGroup(groupId(track.type, track.id), format),
                false,
                intArrayOf(C.FORMAT_HANDLED),
                booleanArrayOf(track.selected),
            )
        }
        return Tracks(groups)
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) engine.play() else engine.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (engine.state == Player.STATE_IDLE) engine.prepareAgain()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        engine.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        engine.release()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (positionMs != C.TIME_UNSET) engine.seekTo(positionMs) else engine.seekTo(0L)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        engine.speed = playbackParameters.speed
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVolume(volume: Float, volumeOperationType: Int): ListenableFuture<*> {
        engine.volume = volume
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        (videoOutput as? SurfaceView)?.let(engine::attachSurface)
        return Futures.immediateVoidFuture()
    }

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        engine.attachSurface(null)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetTrackSelectionParameters(trackSelectionParameters: TrackSelectionParameters): ListenableFuture<*> {
        val videoWasOff = C.TRACK_TYPE_VIDEO in parameters.disabledTrackTypes
        parameters = trackSelectionParameters
        val overrides = trackSelectionParameters.overrides.keys.map { it.id }
        overrides.firstOrNull { it.startsWith(AUDIO_PREFIX) }?.let { engine.selectAudio(it.removePrefix(AUDIO_PREFIX)) }
        when {
            C.TRACK_TYPE_TEXT in trackSelectionParameters.disabledTrackTypes -> engine.selectSubtitle(null)
            else -> overrides.firstOrNull { it.startsWith(TEXT_PREFIX) }?.let { engine.selectSubtitle(it.removePrefix(TEXT_PREFIX)) }
        }
        // Background audio turns the picture off this way, as it does on ExoPlayer ([hideVideoUnseen]).
        val videoOff = C.TRACK_TYPE_VIDEO in trackSelectionParameters.disabledTrackTypes
        if (videoOff != videoWasOff) engine.setVideoEnabled(!videoOff)
        return Futures.immediateVoidFuture()
    }

    companion object {
        private const val UID = "vlc"
        const val AUDIO_PREFIX = "vlc-a-"
        const val TEXT_PREFIX = "vlc-s-"

        fun groupId(type: EngineTrack.Type, id: String): String =
            (if (type == EngineTrack.Type.Audio) AUDIO_PREFIX else TEXT_PREFIX) + id

        private val COMMANDS = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_RELEASE,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_SET_SPEED_AND_PITCH,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_TRACKS,
                Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS,
                Player.COMMAND_GET_VOLUME,
                Player.COMMAND_SET_VOLUME,
                Player.COMMAND_SET_VIDEO_SURFACE,
            )
            .build()
    }
}
