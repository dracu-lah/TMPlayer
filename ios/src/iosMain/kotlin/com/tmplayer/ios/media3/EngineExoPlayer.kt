package com.tmplayer.ios.media3

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.FlagSet
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.tmplayer.desktop.player.MediaTrack
import com.tmplayer.desktop.player.OpenPrefs
import com.tmplayer.desktop.player.PlaybackEngine
import com.tmplayer.desktop.player.PlaybackStatus
import com.tmplayer.desktop.player.TrackType
import com.tmplayer.player.SubtitlePlan
import com.tmplayer.player.TrackLanguages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.openani.mediamp.source.UriMediaData
import platform.UIKit.UIView
import kotlin.math.roundToInt

/**
 * What the app's video engine is made of, for one player: upstream's PlaybackEngine over it, the
 * view it draws in, and the text subtitles it can hand over instead of drawing them.
 */
class EngineHandle(
    val playback: PlaybackEngine,
    val surface: UIView,
    val subtitles: SubtitleText? = null,
    val readAhead: ReadAheadTaker? = null,
)

/**
 * Media3's buffering policy as Android's player sets it ([androidx.media3.exoplayer.DefaultLoadControl]):
 * read ahead to [maxBufferMs], read again once under [minBufferMs], play on after a stall once
 * [bufferForPlaybackAfterRebufferMs] are in, and hold no more than [targetBufferBytes] in all,
 * [backBufferMs] of what played included. A [targetBufferBytes] under 1 leaves the bytes to the engine.
 */
data class ReadAhead(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    val targetBufferBytes: Int,
    val backBufferMs: Int,
) {
    /** The part of the budget for what already played: its share of the time held, as Media3 keeps both in one budget. */
    val behindBytes: Long
        get() = if (targetBufferBytes <= 0 || backBufferMs <= 0) 0 else targetBufferBytes.toLong() * backBufferMs / (backBufferMs + maxBufferMs.coerceAtLeast(1))

    /** The rest of the budget, for what is ahead. */
    val aheadBytes: Long
        get() = if (targetBufferBytes <= 0) 0 else targetBufferBytes.toLong() - behindBytes
}

/** An engine that reads ahead by Android's policy, told before each file opens. */
interface ReadAheadTaker {
    fun readAhead(plan: ReadAhead)
}

/**
 * An engine that hands the player the text subtitle due now instead of drawing it, so Android's
 * player draws it in its own SubtitleView, as on Android (docs/IOS.md, layer 4 step 6).
 */
interface SubtitleText {
    /** The text due now in ASS event markup (VideoEngine's EngineReports.subtitleText), null when none. */
    val subtitleText: kotlinx.coroutines.flow.StateFlow<String?>

    /** True: the engine draws text subtitles itself; false: they are the player's to draw. */
    fun drawTextSubtitles(draw: Boolean)
}

/** Where an ExoPlayer gets its engine; the shell installs it as the app starts (:ios:kit). */
object PlaybackEngines {
    var make: (() -> EngineHandle)? = null
}

/**
 * Media3's ExoPlayer over a video engine: what Android's player asks of ExoPlayer is said in the
 * rules' words (upstream's PlaybackEngine, docs/IOS.md), and what the engine reports
 * comes back as Media3's state and listener callbacks, on the main thread as on Android.
 *
 * Media3's meanings are kept where the two differ: play at the end waits for a seek, as ExoPlayer
 * does, and preparing with playWhenReady off opens the video paused. The track choices of
 * TrackSelectionParameters become the engine's: an override picks that track, a disabled type
 * turns it off, the preferred languages pick the first track in them.
 */
class EngineExoPlayer internal constructor(
    private val seekBackIncrementMs: Long,
    private val seekForwardIncrementMs: Long,
    private var seekParameters: SeekParameters,
) : ExoPlayer {
    private val engine: EngineHandle? = PlaybackEngines.make?.invoke()
    private val playback: PlaybackEngine? = engine?.playback

    /** Android's Decoder setting on Prefer software video: every file opens decoding in software. */
    internal var softwareVideo = false

    /** Android's buffering policy from the builder, for the engine to read ahead by. */
    internal fun useLoadControl(control: androidx.media3.exoplayer.LoadControl) {
        // The player's AheadFirstLoadControl wraps the default one; its budget is the default one's.
        val inner = (control as? com.tmplayer.player.AheadFirstLoadControl)?.delegate ?: control
        val plan = (inner as? androidx.media3.exoplayer.DefaultLoadControl)?.plan ?: return
        engine?.readAhead?.readAhead(plan)
    }
    private val scope = MainScope() + CoroutineExceptionHandler { _, e -> com.tmplayer.platform.Logger.w("media3", "a player job failed", e) }
    private val listeners = ArrayList<Player.Listener>()

    private var items: List<MediaItem> = emptyList()
    private var index = 0
    private var startPositionMs = 0L
    private var prepared = false
    private var playWhenReady = false
    private var released = false
    private var trackParameters: TrackSelectionParameters = TrackSelectionParameters.DEFAULT
    private var playbackParameters: PlaybackParameters = PlaybackParameters.DEFAULT
    private var volume = 1f
    private var noEngineError: PlaybackException? = null

    // One PlaybackException per message, so a listener sees the same error as the same object.
    private val errors = HashMap<String, PlaybackException>()

    // What the listeners were last told, to tell them only what changed.
    private var shown = Shown()
    private var status = PlaybackStatus()
    private var tracks: Tracks = Tracks.EMPTY
    private var engineTracks: List<MediaTrack> = emptyList()
    private var firstFrameTold = false
    private var cues: CueGroup = CueGroup.EMPTY_TIME_ZERO

    init {
        val playback = playback
        if (playback != null) {
            scope.launch {
                combine(playback.state, playback.tracks) { state, list -> state to list }.collect { (state, list) -> update(state, list) }
            }
        }
        // Android's player draws the text subtitles itself, from onCues (layer 4, step 6); the
        // engine keeps drawing only the picture ones.
        engine?.subtitles?.let { subtitles ->
            subtitles.drawTextSubtitles(false)
            scope.launch { subtitles.subtitleText.collect(::showSubtitleText) }
        }
    }

    /** The engine's text subtitle due now as Media3's cues, to the listeners as ExoPlayer's text output sends them. */
    private fun showSubtitleText(text: String?) {
        val codec = engineTracks.firstOrNull { it.type == TrackType.Subtitle && it.selected }?.codec?.lowercase()
        val next = EngineCues.of(text, ass = codec == "ass" || codec == "ssa")
        if (next.isEmpty() && cues.cues.isEmpty()) return
        cues = CueGroup(next, getCurrentPosition() * 1000)
        val group = cues
        tell(Player.EVENT_CUES) { it.onCues(group) }
    }

    /** The view the engine draws in, for the player's view to hold (layer 3, PlayerView). */
    fun engineSurface(): UIView? = engine?.surface

    /**
     * The frame on screen without the subtitles, as Android's PixelCopy reads a SurfaceView: the
     * engine's own capture (PlaybackEngine.screenshot) through a file of its own, read back and
     * removed. [done] on the main thread, with null when there is no frame.
     */
    fun captureFrame(done: (android.graphics.Bitmap?) -> Unit) {
        val engine = playback
        if (engine == null || released) {
            done(null)
            return
        }
        scope.launch {
            val file = java.io.File(platform.Foundation.NSTemporaryDirectory(), "frame-${platform.Foundation.NSUUID().UUIDString}.png")
            val frame = runCatching {
                if (engine.screenshot(file, withSubtitles = false)) android.graphics.BitmapFactory.decodeFile(file.path) else null
            }.getOrNull()
            file.delete()
            done(frame)
        }
    }

    /**
     * The picture drawn to the whole of the engine's view, as a SurfaceView's buffer fills the
     * SurfaceView: inside Media3's PlayerView the frame around it already has the video's shape.
     */
    fun fillView() {
        engine?.playback?.setScale(com.tmplayer.player.VideoScale.Stretch)
    }

    /** What the engine reports now, upstream's PlaybackStatus; for the bridge's tests. */
    fun engineStatus(): PlaybackStatus? = playback?.state?.value

    // The features Android builds into ExoPlayer's pipeline, and what was last passed on of them.
    private var pipeline: PipelineFeatures? = null
    private var applied = PipelineFeatures.Wanted()
    private var pipelineWatch: kotlinx.coroutines.Job? = null

    /**
     * The pipeline Android's player built for this player (its audio processors and text renderers),
     * read a few times a second while a video is open and passed to the engine as commands.
     */
    /** The track selector the player was built with, as ExoPlayer keeps it. */
    internal var selector: androidx.media3.exoplayer.trackselection.TrackSelector? = null

    /**
     * The data sources Android's player gave its media source factory: a Telegram stream or a video
     * joined from parts is read through them ([SourceStreams]), as ExoPlayer reads it.
     */
    internal var dataSources: androidx.media3.datasource.DataSource.Factory? = null
    private var sourceKey: String? = null

    override fun getTrackSelector(): androidx.media3.exoplayer.trackselection.TrackSelector? = selector

    fun attachPipeline(features: PipelineFeatures) {
        pipeline = features
        applied = PipelineFeatures.Wanted()
        applyPipeline()
        if (prepared) watchPipeline()
    }

    private fun watchPipeline() {
        if (pipeline == null || pipelineWatch?.isActive == true) return
        pipelineWatch = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(PIPELINE_READ_MS)
                applyPipeline()
            }
        }
    }

    private fun applyPipeline() {
        val features = pipeline ?: return
        val engine = playback ?: return
        val wanted = features.read()
        val was = applied
        if (wanted == was) return
        applied = wanted
        if (wanted.volumeBoost != was.volumeBoost) engine.setVolumeBoost(wanted.volumeBoost)
        if (wanted.nightMode != was.nightMode) engine.setNightMode(wanted.nightMode)
        if (wanted.audioDelayMs != was.audioDelayMs) engine.setAudioDelay(wanted.audioDelayMs)
        if (wanted.subtitleDelayMs != was.subtitleDelayMs) engine.setSubtitleDelay(wanted.subtitleDelayMs)
        if (wanted.downmix != was.downmix) engine.setDownmix(wanted.downmix)
    }

    // Listeners

    override fun addListener(listener: Player.Listener) {
        if (listener !in listeners) listeners.add(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)
    }

    // The playlist: one item plays at a time.

    override fun setMediaItem(mediaItem: MediaItem) = setMediaItems(listOf(mediaItem), 0, 0)

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) = setMediaItems(listOf(mediaItem), 0, startPositionMs)

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) =
        setMediaItems(listOf(mediaItem), 0, if (resetPosition) 0 else getCurrentPosition())

    override fun setMediaItems(mediaItems: List<MediaItem>) = setMediaItems(mediaItems, 0, 0)

    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) =
        setMediaItems(mediaItems, 0, if (resetPosition) 0 else getCurrentPosition())

    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        val before = getCurrentMediaItem()
        items = mediaItems.toList()
        index = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        this.startPositionMs = startPositionMs.coerceAtLeast(0)
        if (prepared) openCurrent()
        val now = getCurrentMediaItem()
        if (now != before) tell(Player.EVENT_MEDIA_ITEM_TRANSITION) { it.onMediaItemTransition(now, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) }
        tell(Player.EVENT_TIMELINE_CHANGED) { it.onTimelineChanged(getCurrentTimeline(), Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) }
    }

    override fun addMediaItem(mediaItem: MediaItem) {
        items = items + mediaItem
    }

    override fun replaceMediaItem(index: Int, mediaItem: MediaItem) {
        if (index !in items.indices) return
        val before = items[index]
        items = items.toMutableList().also { it[index] = mediaItem }
        if (index != this.index) return
        // Only what describes it changed (the player names the session's video, reads its
        // thumbnail): Media3 updates the item in place and playback carries on.
        if (sameSource(before, mediaItem)) {
            if (before.mediaMetadata !== mediaItem.mediaMetadata) {
                tell(Player.EVENT_MEDIA_METADATA_CHANGED) { it.onMediaMetadataChanged(mediaItem.mediaMetadata) }
            }
            return
        }
        // The same place in a new copy: open it where playback stands, as Media3 replaces a source in place.
        if (prepared) {
            startPositionMs = getCurrentPosition()
            openCurrent()
        }
    }

    /** The same file and the same side-loaded subtitles, which Media3 can update without preparing again. */
    private fun sameSource(a: MediaItem, b: MediaItem): Boolean {
        val one = a.localConfiguration
        val two = b.localConfiguration
        return one?.uri == two?.uri && one?.customCacheKey == two?.customCacheKey &&
            one?.subtitleConfigurations?.map { it.uri } == two?.subtitleConfigurations?.map { it.uri }
    }

    override fun clearMediaItems() {
        items = emptyList()
        index = 0
        if (prepared) stop()
    }

    override fun getMediaItemCount(): Int = items.size

    override fun getMediaItemAt(index: Int): MediaItem = items[index]

    override fun getCurrentMediaItem(): MediaItem? = items.getOrNull(index)

    override fun getCurrentMediaItemIndex(): Int = index

    override fun isCurrentMediaItemSeekable(): Boolean = status.opened && status.durationMs > 0

    override fun isCurrentMediaItemLive(): Boolean = false

    override fun isCurrentMediaItemDynamic(): Boolean = false

    override fun getCurrentTimeline(): Timeline = if (items.isEmpty()) Timeline.EMPTY else OneItem(items.size)

    // Playback

    override fun prepare() {
        if (released || prepared) return
        prepared = true
        openCurrent()
        watchPipeline()
    }

    override fun play() = setPlayWhenReady(true)

    override fun pause() = setPlayWhenReady(false)

    override fun setPlayWhenReady(playWhenReady: Boolean) = setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)

    private fun setPlayWhenReady(playWhenReady: Boolean, reason: Int) {
        if (this.playWhenReady == playWhenReady) return
        this.playWhenReady = playWhenReady
        val engine = playback
        if (engine != null && prepared) {
            // At the end ExoPlayer waits for a seek before playing again; so does this.
            if (playWhenReady && !status.ended && suppression == Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
                engine.play()
            } else if (!playWhenReady) {
                engine.pause()
            }
        }
        tell(Player.EVENT_PLAY_WHEN_READY_CHANGED) { it.onPlayWhenReadyChanged(playWhenReady, reason) }
        update(status, engineTracks)
    }

    // ExoPlayer's audio focus and "becoming noisy", from what iOS's audio session reports: an
    // interruption (a call, an alarm, Siri) holds playback back while it lasts and lets it go on
    // after when iOS says it may, as a transient loss of focus does on Android; one iOS does not
    // let go on pauses, as a lasting loss does. Headphones taken out pause, as Android's
    // ACTION_AUDIO_BECOMING_NOISY does.
    private var handleAudioFocus = false
    private var handleBecomingNoisy = false
    private var suppression = Player.PLAYBACK_SUPPRESSION_REASON_NONE
    private var audioWatch: List<Any> = emptyList()

    private fun watchAudioSession() {
        if (audioWatch.isNotEmpty() || released) return
        val center = platform.Foundation.NSNotificationCenter.defaultCenter
        val main = platform.Foundation.NSOperationQueue.mainQueue
        audioWatch = listOf(
            center.addObserverForName(platform.AVFAudio.AVAudioSessionInterruptionNotification, null, main) { note ->
                val info = note?.userInfo ?: return@addObserverForName
                val type = (info[platform.AVFAudio.AVAudioSessionInterruptionTypeKey] as? platform.Foundation.NSNumber)?.unsignedLongValue
                if (type == platform.AVFAudio.AVAudioSessionInterruptionTypeBegan) {
                    interrupted(true, resume = false)
                } else {
                    val options = (info[platform.AVFAudio.AVAudioSessionInterruptionOptionKey] as? platform.Foundation.NSNumber)?.unsignedLongValue ?: 0u
                    interrupted(false, resume = options and platform.AVFAudio.AVAudioSessionInterruptionOptionShouldResume != 0uL)
                }
            },
            center.addObserverForName(platform.AVFAudio.AVAudioSessionRouteChangeNotification, null, main) { note ->
                val reason = (note?.userInfo?.get(platform.AVFAudio.AVAudioSessionRouteChangeReasonKey) as? platform.Foundation.NSNumber)?.unsignedLongValue
                if (reason == platform.AVFAudio.AVAudioSessionRouteChangeReasonOldDeviceUnavailable && handleBecomingNoisy && playWhenReady) {
                    setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
                }
            },
        )
    }

    private fun interrupted(began: Boolean, resume: Boolean) {
        if (!handleAudioFocus || released) return
        if (began) {
            if (suppression == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS) return
            suppression = Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
            if (prepared) playback?.pause()
        } else {
            if (suppression == Player.PLAYBACK_SUPPRESSION_REASON_NONE) return
            suppression = Player.PLAYBACK_SUPPRESSION_REASON_NONE
            if (!resume) {
                tell(Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED) { it.onPlaybackSuppressionReasonChanged(suppression) }
                setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
                return
            }
            if (prepared && playWhenReady && !status.ended) playback?.play()
        }
        tell(Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED) { it.onPlaybackSuppressionReasonChanged(suppression) }
        update(status, engineTracks)
    }

    override fun stop() {
        if (!prepared) return
        prepared = false
        pipelineWatch?.cancel()
        playback?.stop()
        update(status, engineTracks)
    }

    override fun release() {
        if (released) return
        released = true
        prepared = false
        playback?.close()
        audioWatch.forEach { platform.Foundation.NSNotificationCenter.defaultCenter.removeObserver(it) }
        audioWatch = emptyList()
        sourceKey?.let(SourceStreams::forget)
        sourceKey = null
        listeners.clear()
        scope.cancel()
    }

    override fun isReleased(): Boolean = released

    override fun getPlaybackState(): Int = stateOf(status)

    override fun getPlaybackSuppressionReason(): Int = suppression

    override fun isPlaying(): Boolean = playingOf(status)

    override fun isLoading(): Boolean = prepared && status.buffering

    override fun getPlayerError(): PlaybackException? = errorOf(status)

    override fun getPlayWhenReady(): Boolean = playWhenReady

    override fun setRepeatMode(repeatMode: Int) {}

    override fun getRepeatMode(): Int = Player.REPEAT_MODE_OFF

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {}

    override fun getShuffleModeEnabled(): Boolean = false

    // Seeking

    override fun seekTo(positionMs: Long) = seekTo(index, positionMs)

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        val old = positionInfo()
        val target = positionMs.coerceAtLeast(0)
        val engine = playback
        when {
            mediaItemIndex != index && mediaItemIndex in items.indices -> {
                index = mediaItemIndex
                startPositionMs = target
                if (prepared) openCurrent()
            }
            !prepared || engine == null || !status.opened -> startPositionMs = target
            else -> {
                val wasEnded = status.ended
                if (seekParameters == SeekParameters.EXACT) engine.seekToExact(target) else engine.seekTo(target)
                // Seeking away from the end with playWhenReady on plays again, as ExoPlayer does.
                if (wasEnded && playWhenReady) engine.play()
            }
        }
        val new = positionInfo(target)
        tell(Player.EVENT_POSITION_DISCONTINUITY) { it.onPositionDiscontinuity(old, new, Player.DISCONTINUITY_REASON_SEEK) }
    }

    override fun seekToDefaultPosition() = seekTo(0)

    override fun seekBack() = seekTo((getCurrentPosition() - seekBackIncrementMs).coerceAtLeast(0))

    override fun seekForward() {
        val duration = getDuration()
        val target = getCurrentPosition() + seekForwardIncrementMs
        seekTo(if (duration != C.TIME_UNSET) target.coerceAtMost(duration) else target)
    }

    override fun getSeekBackIncrement(): Long = seekBackIncrementMs

    override fun getSeekForwardIncrement(): Long = seekForwardIncrementMs

    override fun hasNextMediaItem(): Boolean = index + 1 < items.size

    override fun hasPreviousMediaItem(): Boolean = index > 0

    override fun seekToNextMediaItem() {
        if (hasNextMediaItem()) seekTo(index + 1, 0)
    }

    override fun seekToPreviousMediaItem() {
        if (hasPreviousMediaItem()) seekTo(index - 1, 0)
    }

    override fun seekToNext() = seekToNextMediaItem()

    override fun seekToPrevious() {
        if (getCurrentPosition() > MAX_SEEK_TO_PREVIOUS_MS || !hasPreviousMediaItem()) seekTo(0) else seekToPreviousMediaItem()
    }

    override fun setSeekParameters(seekParameters: SeekParameters?) {
        this.seekParameters = seekParameters ?: SeekParameters.DEFAULT
    }

    override fun getSeekParameters(): SeekParameters = seekParameters

    // Speed, volume, time

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        if (this.playbackParameters == playbackParameters) return
        this.playbackParameters = playbackParameters
        playback?.setSpeed(playbackParameters.speed)
        tell(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED) { it.onPlaybackParametersChanged(playbackParameters) }
    }

    override fun getPlaybackParameters(): PlaybackParameters = playbackParameters

    override fun setPlaybackSpeed(speed: Float) = setPlaybackParameters(playbackParameters.withSpeed(speed))

    override fun getDuration(): Long = if (status.opened && status.durationMs > 0) status.durationMs else C.TIME_UNSET

    override fun getCurrentPosition(): Long = if (prepared && status.opened) status.positionMs else startPositionMs

    override fun getBufferedPosition(): Long = maxOf(getCurrentPosition(), if (status.opened) status.bufferedMs else 0)

    override fun getBufferedPercentage(): Int {
        val duration = getDuration()
        return if (duration == C.TIME_UNSET || duration == 0L) 0 else (getBufferedPosition() * 100 / duration).toInt().coerceIn(0, 100)
    }

    override fun getTotalBufferedDuration(): Long = (getBufferedPosition() - getCurrentPosition()).coerceAtLeast(0)

    override fun getContentDuration(): Long = getDuration()

    override fun getContentPosition(): Long = getCurrentPosition()

    override fun getContentBufferedPosition(): Long = getBufferedPosition()

    override fun getVolume(): Float = volume

    override fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        if (clamped == this.volume) return
        this.volume = clamped
        playback?.setVolume((clamped * 100).roundToInt())
        tell(Player.EVENT_VOLUME_CHANGED) { it.onVolumeChanged(clamped) }
    }

    override fun getAudioAttributes(): AudioAttributes = AudioAttributes.DEFAULT

    override fun setAudioAttributes(audioAttributes: AudioAttributes, handleAudioFocus: Boolean) {
        this.handleAudioFocus = handleAudioFocus
        if (handleAudioFocus) watchAudioSession()
    }

    override fun setHandleAudioBecomingNoisy(handleAudioBecomingNoisy: Boolean) {
        handleBecomingNoisy = handleAudioBecomingNoisy
        if (handleAudioBecomingNoisy) watchAudioSession()
    }

    override fun setWakeMode(wakeMode: Int) {}

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {}

    override fun getSkipSilenceEnabled(): Boolean = false

    override fun setVideoScalingMode(videoScalingMode: Int) {}

    override fun getVideoScalingMode(): Int = C.VIDEO_SCALING_MODE_DEFAULT

    // Tracks and picture

    override fun getCurrentTracks(): Tracks = tracks

    override fun getTrackSelectionParameters(): TrackSelectionParameters = trackParameters

    override fun setTrackSelectionParameters(parameters: TrackSelectionParameters) {
        if (parameters == trackParameters) return
        trackParameters = parameters
        applyTrackChoices()
        tell(Player.EVENT_TRACK_SELECTION_PARAMETERS_CHANGED) { it.onTrackSelectionParametersChanged(parameters) }
    }

    override fun getVideoFormat(): Format? = selectedFormat(C.TRACK_TYPE_VIDEO)

    override fun getAudioFormat(): Format? = selectedFormat(C.TRACK_TYPE_AUDIO)

    override fun getMediaMetadata(): MediaMetadata = getCurrentMediaItem()?.mediaMetadata ?: MediaMetadata.EMPTY

    override fun getVideoSize(): VideoSize =
        if (status.videoWidth > 0 && status.videoHeight > 0) VideoSize(status.videoWidth, status.videoHeight) else VideoSize.UNKNOWN

    override fun getCurrentCues(): CueGroup = cues

    override fun getAvailableCommands(): Player.Commands = Player.Commands.Builder().addAllCommands().build()

    override fun isCommandAvailable(command: Int): Boolean = getAvailableCommands().contains(command)

    // The engine

    /** Opens the current item at the start position, paused unless playWhenReady is on. */
    private fun openCurrent() {
        val item = getCurrentMediaItem() ?: return
        val engine = playback
        if (engine == null) {
            noEngineError = PlaybackException("No video engine installed", null, PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK)
            update(status, engineTracks)
            return
        }
        val source = engineAddress(item)
        val start = startPositionMs
        firstFrameTold = false
        // What Android's pipeline asks of the sound (the stereo downmix above all), carried by the
        // open itself: set afterwards, the downmix had mpv open its audio output again while it was
        // still starting, which now and then never came back, the player stuck on Buffering
        // (iPhone 13, 2026-11-13). Only what changes later is applied afterwards ([applyPipeline]).
        val wanted = pipeline?.read() ?: PipelineFeatures.Wanted()
        // Undispatched, so the engine has the file before the pause below reaches it.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                engine.open(UriMediaData(source), start, openPrefs(wanted))
                // The item's side-loaded subtitle files, as ExoPlayer merges them into its tracks.
                item.localConfiguration?.subtitleConfigurations.orEmpty().forEach { file ->
                    val path = file.uri.path?.takeIf { file.uri.scheme == null || file.uri.scheme == "file" }
                    if (path != null) engine.addSubtitle(path)
                }
                applyTrackChoices()
                applied = wanted
                applyPipeline()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                com.tmplayer.platform.Logger.w("media3", "open failed", e)
            }
        }
        if (!playWhenReady) engine.pause()
        if (playbackParameters.speed != 1f) engine.setSpeed(playbackParameters.speed)
    }

    /** The settings a video opens with, from what the player set on this ExoPlayer and its pipeline. */
    private fun openPrefs(wanted: PipelineFeatures.Wanted): OpenPrefs {
        val textOff = C.TRACK_TYPE_TEXT in trackParameters.disabledTrackTypes
        val textLanguage = trackParameters.preferredTextLanguages.firstOrNull()
        return OpenPrefs(
            audioLanguage = trackParameters.preferredAudioLanguages.firstOrNull(),
            subtitles = when {
                textOff -> SubtitlePlan.Off()
                textLanguage != null -> SubtitlePlan.On(textLanguage)
                else -> SubtitlePlan.FileDefault
            },
            speed = playbackParameters.speed,
            volume = (volume * 100).roundToInt(),
            downmix = wanted.downmix,
            volumeBoost = wanted.volumeBoost,
            nightMode = wanted.nightMode,
            delays = com.tmplayer.player.SyncDelays(subtitleMs = wanted.subtitleDelayMs, audioMs = wanted.audioDelayMs),
            hwdec = OpenPrefs.hwdecFor(softwareVideo),
        )
    }

    /** Carries out the track choices of TrackSelectionParameters on the engine. */
    private fun applyTrackChoices() {
        val engine = playback ?: return
        if (!status.opened) return
        for ((media3Type, type) in listOf(C.TRACK_TYPE_AUDIO to TrackType.Audio, C.TRACK_TYPE_TEXT to TrackType.Subtitle)) {
            val ofType = engineTracks.filter { it.type == type }
            if (media3Type in trackParameters.disabledTrackTypes) {
                if (ofType.any { it.selected }) engine.selectTrack(type, null)
                continue
            }
            val override = trackParameters.overrides.values.firstOrNull { it.getType() == media3Type }
            val chosen = override?.mediaTrackGroup?.id?.let(::engineIdOf)?.let { id -> ofType.firstOrNull { it.id == id } }
                ?: preferred(media3Type)?.let { language -> ofType.firstOrNull { TrackLanguages.same(language, it.language) } }
            if (chosen != null && !chosen.selected) engine.selectTrack(type, chosen.id)
            // Subtitles disabled before and enabled now, with nothing chosen: the file's own pick.
            if (chosen == null && type == TrackType.Subtitle && ofType.none { it.selected } && override == null && ofType.isNotEmpty() &&
                trackParameters.preferredTextLanguages.isNotEmpty()
            ) {
                engine.selectTrack(type, ofType.first().id)
            }
        }
        // The picture turned off while nobody can see it (upstream's BackgroundPlayback.withoutVideo),
        // so the decoder rests, and the same track back once the player is seen again.
        val video = engineTracks.filter { it.type == TrackType.Video }
        if (C.TRACK_TYPE_VIDEO in trackParameters.disabledTrackTypes) {
            video.firstOrNull { it.selected }?.let {
                videoTurnedOff = it.id
                engine.selectTrack(TrackType.Video, null)
            }
        } else {
            val off = videoTurnedOff
            if (off != null && video.none { it.selected }) engine.selectTrack(TrackType.Video, video.firstOrNull { it.id == off }?.id ?: video.firstOrNull()?.id)
            videoTurnedOff = null
        }
    }

    /** The video track [applyTrackChoices] turned off, to turn back on. */
    private var videoTurnedOff: Int? = null

    private fun preferred(media3Type: Int): String? = when (media3Type) {
        C.TRACK_TYPE_AUDIO -> trackParameters.preferredAudioLanguages.firstOrNull()
        C.TRACK_TYPE_TEXT -> trackParameters.preferredTextLanguages.firstOrNull()
        else -> null
    }

    private fun selectedFormat(media3Type: Int): Format? =
        tracks.groups.firstOrNull { it.type == media3Type && it.isSelected() }?.getTrackFormat(0)

    /** What the engine reported, as Media3's state and callbacks; on the main thread. */
    private fun update(state: PlaybackStatus, list: List<MediaTrack>) {
        if (released) return
        status = state
        if (list != engineTracks) {
            engineTracks = list
            tracks = tracksOf(list)
        }
        val now = Shown(
            playbackState = stateOf(state),
            playing = playingOf(state),
            tracks = tracks,
            videoSize = getVideoSize(),
            error = errorOf(state),
            durationMs = getDuration(),
        )
        val before = shown
        shown = now
        val events = FlagSet.Builder()
        val calls = ArrayList<(Player.Listener) -> Unit>()
        if (now.durationMs != before.durationMs) {
            events.add(Player.EVENT_TIMELINE_CHANGED)
            calls += { it.onTimelineChanged(getCurrentTimeline(), Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE) }
        }
        if (now.tracks != before.tracks) {
            events.add(Player.EVENT_TRACKS_CHANGED)
            calls += { it.onTracksChanged(now.tracks) }
        }
        if (now.playbackState != before.playbackState) {
            events.add(Player.EVENT_PLAYBACK_STATE_CHANGED)
            calls += { it.onPlaybackStateChanged(now.playbackState) }
        }
        if (now.playing != before.playing) {
            events.add(Player.EVENT_IS_PLAYING_CHANGED)
            calls += { it.onIsPlayingChanged(now.playing) }
        }
        if (now.videoSize != before.videoSize) {
            events.add(Player.EVENT_VIDEO_SIZE_CHANGED)
            calls += { it.onVideoSizeChanged(now.videoSize) }
        }
        if (now.error != before.error) {
            events.add(Player.EVENT_PLAYER_ERROR)
            val error = now.error
            calls += { it.onPlayerErrorChanged(error) }
            if (error != null) calls += { it.onPlayerError(error) }
        }
        if (!firstFrameTold && now.playbackState == Player.STATE_READY && now.videoSize != VideoSize.UNKNOWN) {
            firstFrameTold = true
            events.add(Player.EVENT_RENDERED_FIRST_FRAME)
            calls += { it.onRenderedFirstFrame() }
        }
        if (calls.isEmpty()) return
        val flags = events.build()
        for (listener in listeners.toList()) {
            calls.forEach { call -> call(listener) }
            listener.onEvents(this, Player.Events(flags))
        }
    }

    /** One callback now, with its onEvents, for a change the player made itself. */
    private fun tell(event: Int, call: (Player.Listener) -> Unit) {
        if (released) return
        val flags = FlagSet.Builder().add(event).build()
        for (listener in listeners.toList()) {
            call(listener)
            listener.onEvents(this, Player.Events(flags))
        }
    }

    private fun stateOf(state: PlaybackStatus): Int = when {
        !prepared || items.isEmpty() -> Player.STATE_IDLE
        errorOf(state) != null -> Player.STATE_IDLE
        state.ended -> Player.STATE_ENDED
        !state.opened || state.buffering -> Player.STATE_BUFFERING
        else -> Player.STATE_READY
    }

    private fun playingOf(state: PlaybackStatus): Boolean =
        playWhenReady && suppression == Player.PLAYBACK_SUPPRESSION_REASON_NONE && stateOf(state) == Player.STATE_READY && state.playing

    // The source's own failure, when the engine stopped for one: Telegram's words reach the player's
    // error sheet as its cause, as ExoPlayer hands on a data source's exception.
    private fun errorOf(state: PlaybackStatus): PlaybackException? =
        noEngineError ?: state.error?.takeIf { prepared }?.let { message ->
            errors.getOrPut(message) {
                val cause = sourceKey?.let(SourceStreams::failure)
                PlaybackException(cause?.message ?: message, cause, if (cause != null) PlaybackException.ERROR_CODE_IO_UNSPECIFIED else PlaybackException.ERROR_CODE_UNSPECIFIED)
            }
        }

    /**
     * Where the engine opens [item]: through the player's data sources when it is a Telegram file or
     * a video joined from parts and the player gave any, otherwise the file or the address itself.
     */
    private fun engineAddress(item: MediaItem): String {
        sourceKey?.let(SourceStreams::forget)
        sourceKey = null
        val uri = item.localConfiguration?.uri
        val sources = dataSources
        val scheme = uri?.scheme?.lowercase()
        if (uri != null && sources != null && (scheme == "tdfile" || scheme == com.tmplayer.player.JOINED_SCHEME)) {
            val key = SourceStreams.register(sources, uri)
            sourceKey = key
            return "${SourceStreams.SCHEME}://$key"
        }
        return sourceOf(item)
    }


    private fun positionInfo(positionMs: Long = getCurrentPosition()) = Player.PositionInfo(
        windowUid = null,
        mediaItemIndex = index,
        mediaItem = getCurrentMediaItem(),
        periodUid = null,
        periodIndex = index,
        positionMs = positionMs,
        contentPositionMs = positionMs,
        adGroupIndex = C.INDEX_UNSET,
        adIndexInAdGroup = C.INDEX_UNSET,
    )

    private data class Shown(
        val playbackState: Int = Player.STATE_IDLE,
        val playing: Boolean = false,
        val tracks: Tracks = Tracks.EMPTY,
        val videoSize: VideoSize = VideoSize.UNKNOWN,
        val error: PlaybackException? = null,
        val durationMs: Long = C.TIME_UNSET,
    )

    /** One window per item, the only shape a player of TDLib's files has. */
    private class OneItem(private val count: Int) : Timeline() {
        override fun getWindowCount(): Int = count
    }

    companion object {
        private const val MAX_SEEK_TO_PREVIOUS_MS = 3_000L
        private const val PIPELINE_READ_MS = 250L
        private const val GROUP_PREFIX = "engine:"

        /**
         * The location an engine opens for a media item: TDLib's `tdfile://<file id>` without the
         * type Android's extractors read from it, a file's path, or the URL as it is.
         */
        fun sourceOf(item: MediaItem): String {
            val uri = item.localConfiguration?.uri ?: return ""
            return when (uri.scheme?.lowercase()) {
                "tdfile" -> "tdfile://" + (uri.authority ?: uri.schemeSpecificPart.removePrefix("//").substringBefore('?'))
                "file" -> uri.path ?: uri.toString()
                else -> uri.toString()
            }
        }

        /** The engine's tracks as Media3's: one group per track, its id the engine's. */
        fun tracksOf(list: List<MediaTrack>): Tracks = Tracks(
            list.map { track ->
                val format = Format.Builder()
                    .setId(track.id.toString())
                    .setLabel(track.title)
                    .setLanguage(track.language)
                    .setCodecs(track.codec)
                    .setSampleMimeType(mimeOf(track))
                    .setChannelCount(track.channels ?: Format.NO_VALUE)
                    .setSelectionFlags(
                        (if (track.isDefault) C.SELECTION_FLAG_DEFAULT else 0) or (if (track.forced) C.SELECTION_FLAG_FORCED else 0),
                    )
                    .build()
                Tracks.Group(
                    TrackGroup("$GROUP_PREFIX${track.type.name}:${track.id}", format),
                    false,
                    intArrayOf(C.FORMAT_HANDLED),
                    booleanArrayOf(track.selected),
                )
            },
        )

        /** The engine's track id from a group id made by [tracksOf]; null for any other group. */
        fun engineIdOf(groupId: String): Int? = groupId.takeIf { it.startsWith(GROUP_PREFIX) }?.substringAfterLast(':')?.toIntOrNull()

        /** A MIME type Media3 sorts by: the track's type, with the codec where it is a known one. */
        private fun mimeOf(track: MediaTrack): String {
            val codec = track.codec?.lowercase().orEmpty()
            return when (track.type) {
                TrackType.Video -> when {
                    "hevc" in codec || "h265" in codec -> "video/hevc"
                    "h264" in codec || "avc" in codec -> "video/avc"
                    "av1" in codec -> "video/av01"
                    "vp9" in codec -> "video/x-vnd.on2.vp9"
                    else -> "video/x-unknown"
                }
                TrackType.Audio -> when {
                    codec == "aac" -> "audio/mp4a-latm"
                    codec == "ac3" -> "audio/ac3"
                    codec == "eac3" -> "audio/eac3"
                    codec.startsWith("dts") -> "audio/vnd.dts"
                    codec == "opus" -> "audio/opus"
                    codec == "flac" -> "audio/flac"
                    codec == "mp3" -> "audio/mpeg"
                    else -> "audio/x-unknown"
                }
                TrackType.Subtitle -> when {
                    codec == "subrip" || codec == "srt" -> "application/x-subrip"
                    codec == "ass" || codec == "ssa" -> "text/x-ssa"
                    codec == "webvtt" -> "text/vtt"
                    codec.contains("pgs") -> "application/pgs"
                    else -> "text/x-unknown"
                }
            }
        }
    }
}
