package androidx.media3.exoplayer

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Format
import androidx.media3.common.Player
import com.tmplayer.ios.media3.EngineExoPlayer

// Media3's ExoPlayer on iOS. Android's player builds one as it always does; what it gets plays
// on the video engine the app chose (com.tmplayer.ios.media3.EngineExoPlayer), and the pipeline
// it configures on the way (renderers, extractors, buffers, decoders) has nothing to do here: the
// engine opens, decodes and draws by itself (docs/IOS.md).

interface ExoPlayer : Player {
    /**
     * ExoPlayer's renderers. On iOS the engine decodes and renders, so a player has none, and none
     * of Android's decoder switching applies: loops over them find nothing to do.
     */
    fun getRendererCount(): Int = 0

    val rendererCount: Int get() = getRendererCount()

    fun getRenderer(index: Int): Renderer = throw IndexOutOfBoundsException("No renderer $index on iOS")

    fun getRendererType(index: Int): Int = throw IndexOutOfBoundsException("No renderer $index on iOS")

    fun addAnalyticsListener(listener: androidx.media3.exoplayer.analytics.AnalyticsListener) = Unit

    fun removeAnalyticsListener(listener: androidx.media3.exoplayer.analytics.AnalyticsListener) = Unit

    /** Effects on the video's frames, Media3's; on iOS 3D and the like are engine commands. */
    fun setVideoEffects(videoEffects: List<androidx.media3.common.Effect>) = Unit

    fun setVideoChangeFrameRateStrategy(videoChangeFrameRateStrategy: Int) = Unit

    fun setVideoFrameMetadataListener(listener: androidx.media3.exoplayer.video.VideoFrameMetadataListener) = Unit

    fun clearVideoFrameMetadataListener(listener: androidx.media3.exoplayer.video.VideoFrameMetadataListener) = Unit

    fun getVideoDecoderCounters(): DecoderCounters? = null

    val videoDecoderCounters: DecoderCounters? get() = getVideoDecoderCounters()

    /** The track selector, which on iOS the engine's track choices replace (EngineExoPlayer). */
    fun getTrackSelector(): androidx.media3.exoplayer.trackselection.TrackSelector? = null

    val trackSelector: androidx.media3.exoplayer.trackselection.TrackSelector? get() = getTrackSelector()

    fun setSeekParameters(seekParameters: SeekParameters?)

    fun getSeekParameters(): SeekParameters

    fun getVideoFormat(): Format?

    fun getAudioFormat(): Format?

    fun setWakeMode(wakeMode: Int)

    fun setHandleAudioBecomingNoisy(handleAudioBecomingNoisy: Boolean)

    fun setAudioAttributes(audioAttributes: AudioAttributes, handleAudioFocus: Boolean)

    fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean)

    fun getSkipSilenceEnabled(): Boolean

    fun setVideoScalingMode(videoScalingMode: Int)

    fun getVideoScalingMode(): Int

    fun isReleased(): Boolean

    /** Media3's builder: the settings that mean something to an engine are kept, the rest accepted. */
    class Builder(@Suppress("UNUSED_PARAMETER") context: Context, private var renderersFactory: RenderersFactory?) {
        constructor(context: Context) : this(context, null)

        private var seekBackIncrementMs = DEFAULT_SEEK_BACK_INCREMENT_MS
        private var seekForwardIncrementMs = DEFAULT_SEEK_FORWARD_INCREMENT_MS
        private var seekParameters = SeekParameters.DEFAULT
        private var built = false

        fun setSeekBackIncrementMs(seekBackIncrementMs: Long): Builder = apply { this.seekBackIncrementMs = seekBackIncrementMs }

        fun setSeekForwardIncrementMs(seekForwardIncrementMs: Long): Builder = apply { this.seekForwardIncrementMs = seekForwardIncrementMs }

        fun setSeekParameters(seekParameters: SeekParameters): Builder = apply { this.seekParameters = seekParameters }

        fun setRenderersFactory(renderersFactory: RenderersFactory): Builder = apply { this.renderersFactory = renderersFactory }

        fun experimentalSetDynamicSchedulingEnabled(dynamicSchedulingEnabled: Boolean): Builder = this

        private var mediaSourceFactory: androidx.media3.exoplayer.source.MediaSource.Factory? = null

        /** The player's media source factory: its data sources are what the engine reads the video through. */
        fun setMediaSourceFactory(mediaSourceFactory: androidx.media3.exoplayer.source.MediaSource.Factory): Builder =
            apply { this.mediaSourceFactory = mediaSourceFactory }

        private var trackSelector: androidx.media3.exoplayer.trackselection.TrackSelector? = null

        fun setTrackSelector(trackSelector: androidx.media3.exoplayer.trackselection.TrackSelector): Builder = apply { this.trackSelector = trackSelector }

        private var loadControl: LoadControl? = null

        /** Android's buffering policy, which the engine reads ahead by (com.tmplayer.ios.media3.ReadAhead). */
        fun setLoadControl(loadControl: LoadControl): Builder = apply { this.loadControl = loadControl }

        fun setAudioAttributes(audioAttributes: AudioAttributes, handleAudioFocus: Boolean): Builder = this

        fun setHandleAudioBecomingNoisy(handleAudioBecomingNoisy: Boolean): Builder = this

        fun setWakeMode(wakeMode: Int): Builder = this

        fun setReleaseTimeoutMs(releaseTimeoutMs: Long): Builder = this

        fun setUseLazyPreparation(useLazyPreparation: Boolean): Builder = this

        fun setPauseAtEndOfMediaItems(pauseAtEndOfMediaItems: Boolean): Builder = this

        fun setVideoScalingMode(videoScalingMode: Int): Builder = this

        fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean): Builder = this

        fun setUsePlatformDiagnostics(usePlatformDiagnostics: Boolean): Builder = this

        fun build(): ExoPlayer {
            check(!built) { "build() called twice" }
            built = true
            val player = EngineExoPlayer(seekBackIncrementMs, seekForwardIncrementMs, seekParameters)
            loadControl?.let(player::useLoadControl)
            // What Android's player built into the pipeline reaches the engine, as ExoPlayer hands
            // the renderers their sink and wrappers: the audio processors, the text renderers.
            (renderersFactory as? DefaultRenderersFactory)?.let { factory ->
                player.softwareVideo = factory.prefersSoftwareVideo
                val (processors, text) = factory.pipelinePieces()
                player.attachPipeline(com.tmplayer.ios.media3.PipelineFeatures(processors, text))
            }
            // The player's own data sources, as ExoPlayer reads through them (SourceStreams).
            (mediaSourceFactory as? androidx.media3.exoplayer.source.DefaultMediaSourceFactory)?.let { player.dataSources = it.dataSourceFactory }
            // The track selector's preferences are the player's first track choices.
            (trackSelector as? androidx.media3.exoplayer.trackselection.DefaultTrackSelector)?.let { selector ->
                player.selector = selector
                player.setTrackSelectionParameters(selector.parameters)
            }
            return player
        }
    }

    companion object {
        const val DEFAULT_SEEK_BACK_INCREMENT_MS = 5_000L
        const val DEFAULT_SEEK_FORWARD_INCREMENT_MS = 15_000L
        const val DEFAULT_RELEASE_TIMEOUT_MS = 500L
    }
}

/** Where a seek may land, Media3's: exactly, or on a key frame near the place. */
class SeekParameters(val toleranceBeforeUs: Long, val toleranceAfterUs: Long) {
    override fun equals(other: Any?): Boolean =
        other is SeekParameters && other.toleranceBeforeUs == toleranceBeforeUs && other.toleranceAfterUs == toleranceAfterUs

    override fun hashCode(): Int = 31 * toleranceBeforeUs.hashCode() + toleranceAfterUs.hashCode()

    companion object {
        val EXACT: SeekParameters = SeekParameters(0, 0)
        val CLOSEST_SYNC: SeekParameters = SeekParameters(Long.MAX_VALUE, Long.MAX_VALUE)
        val PREVIOUS_SYNC: SeekParameters = SeekParameters(Long.MAX_VALUE, 0)
        val NEXT_SYNC: SeekParameters = SeekParameters(0, Long.MAX_VALUE)
        val DEFAULT: SeekParameters = EXACT
    }
}

/** Media3's renderers factory; renderers are the engine's on iOS. */
fun interface RenderersFactory {
    fun createRenderers(): Array<Renderer>
}

/** An error of ExoPlayer's own, Media3's. */
class ExoPlaybackException(
    message: String?,
    cause: Throwable?,
    errorCode: Int,
    val type: Int,
) : androidx.media3.common.PlaybackException(message, cause, errorCode) {
    val rendererIndex: Int = androidx.media3.common.C.INDEX_UNSET
    val rendererFormat: Format? = null

    companion object {
        const val TYPE_SOURCE = 0
        const val TYPE_RENDERER = 1
        const val TYPE_UNEXPECTED = 2
        const val TYPE_REMOTE = 3
    }
}
