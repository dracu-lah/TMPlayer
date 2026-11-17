package androidx.media3.exoplayer

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

// Media3's builders of an ExoPlayer's pieces on iOS. The engine decodes and renders, so no
// MediaCodec renderer is ever made; what Android's player builds into the pipeline still is (the
// audio sink's processors, the text renderers it wraps), and ExoPlayer.Builder hands those to the
// engine (EngineExoPlayer.attachPipeline), as ExoPlayer hands them to its renderers.

/** Media3's renderers factory, its hooks overridden as Android's player overrides them. */
open class DefaultRenderersFactory(protected val context: Context) : RenderersFactory {
    private var extensionRendererMode = EXTENSION_RENDERER_MODE_OFF
    private var enableDecoderFallback = false
    private var mediaCodecSelector: MediaCodecSelector = MediaCodecSelector.DEFAULT
    private var allowedVideoJoiningTimeMs = DEFAULT_ALLOWED_VIDEO_JOINING_TIME_MS
    private var enableFloatOutput = false
    private var enableAudioTrackPlaybackParams = false

    fun setExtensionRendererMode(extensionRendererMode: Int): DefaultRenderersFactory = apply { this.extensionRendererMode = extensionRendererMode }

    /**
     * Whether the player asked for the app's own decoder first: Android's Decoder setting on
     * Prefer software video (PlayerActivity sets EXTENSION_RENDERER_MODE_PREFER). On iOS that is
     * mpv decoding in software, where Automatic lets Apple's player take what it plays.
     */
    internal val prefersSoftwareVideo: Boolean get() = extensionRendererMode == EXTENSION_RENDERER_MODE_PREFER

    fun setEnableDecoderFallback(enableDecoderFallback: Boolean): DefaultRenderersFactory = apply { this.enableDecoderFallback = enableDecoderFallback }

    fun setMediaCodecSelector(mediaCodecSelector: MediaCodecSelector): DefaultRenderersFactory = apply { this.mediaCodecSelector = mediaCodecSelector }

    fun setAllowedVideoJoiningTimeMs(allowedVideoJoiningTimeMs: Long): DefaultRenderersFactory = apply { this.allowedVideoJoiningTimeMs = allowedVideoJoiningTimeMs }

    fun setEnableAudioFloatOutput(enableFloatOutput: Boolean): DefaultRenderersFactory = apply { this.enableFloatOutput = enableFloatOutput }

    fun setEnableAudioTrackPlaybackParams(enable: Boolean): DefaultRenderersFactory = apply { enableAudioTrackPlaybackParams = enable }

    fun forceEnableMediaCodecAsynchronousQueueing(): DefaultRenderersFactory = this

    fun forceDisableMediaCodecAsynchronousQueueing(): DefaultRenderersFactory = this

    protected open fun getCodecAdapterFactory(): MediaCodecAdapter.Factory =
        MediaCodecAdapter.Factory { throw UnsupportedOperationException("No MediaCodec on iOS: the engine decodes") }

    protected open fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? =
        DefaultAudioSink.Builder(context).build()

    protected open fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) = Unit

    protected open fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) = Unit

    protected open fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        out.add(TextRenderer(output, outputLooper))
    }

    /** No renderer runs on iOS: the engine plays everything. */
    override fun createRenderers(): Array<Renderer> = emptyArray()

    /**
     * What Android's player builds into the pipeline, built as ExoPlayer builds it: the audio sink's
     * processors, and the text renderers with whatever the player wrapped them in.
     */
    internal fun pipelinePieces(): Pair<List<AudioProcessor>, List<Renderer>> {
        val sink = buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
        val processors = (sink as? DefaultAudioSink)?.audioProcessors.orEmpty()
        val text = ArrayList<Renderer>()
        buildTextRenderers(context, TextOutput { }, Looper.getMainLooper(), extensionRendererMode, text)
        return processors to text
    }

    companion object {
        const val EXTENSION_RENDERER_MODE_OFF = 0
        const val EXTENSION_RENDERER_MODE_ON = 1
        const val EXTENSION_RENDERER_MODE_PREFER = 2
        const val DEFAULT_ALLOWED_VIDEO_JOINING_TIME_MS = 5_000L
        const val MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY = 50
    }
}

/**
 * Media3's buffering policy, kept as set and handed to the engine, which reads ahead by it
 * (com.tmplayer.ios.media3.ReadAhead). Unset, Media3's own defaults. Local playback's figures are
 * kept apart and not used: the player pins them to the same values, and the engine reads a file and
 * a stream alike.
 */
class DefaultLoadControl private constructor(internal val plan: com.tmplayer.ios.media3.ReadAhead) : LoadControl {
    // Media3's own answers by the plan's durations; nothing on iOS asks, the engine reads ahead by the plan itself.
    override fun getAllocator(playerId: androidx.media3.exoplayer.analytics.PlayerId): androidx.media3.exoplayer.upstream.Allocator =
        androidx.media3.exoplayer.upstream.Allocator.None

    override fun getBackBufferDurationUs(playerId: androidx.media3.exoplayer.analytics.PlayerId): Long = plan.backBufferMs * 1000L

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
        parameters.bufferedDurationUs < plan.maxBufferMs * 1000L

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        val needed = if (parameters.rebuffering) plan.bufferForPlaybackAfterRebufferMs else plan.bufferForPlaybackMs
        return parameters.bufferedDurationUs >= needed * 1000L
    }

    class Builder {
        private var minBufferMs = DEFAULT_MIN_BUFFER_MS
        private var maxBufferMs = DEFAULT_MAX_BUFFER_MS
        private var bufferForPlaybackMs = DEFAULT_BUFFER_FOR_PLAYBACK_MS
        private var bufferForPlaybackAfterRebufferMs = DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
        private var targetBufferBytes = DEFAULT_TARGET_BUFFER_BYTES
        private var backBufferMs = DEFAULT_BACK_BUFFER_DURATION_MS

        fun setBufferDurationsMs(minBufferMs: Int, maxBufferMs: Int, bufferForPlaybackMs: Int, bufferForPlaybackAfterRebufferMs: Int): Builder = apply {
            this.minBufferMs = minBufferMs
            this.maxBufferMs = maxBufferMs
            this.bufferForPlaybackMs = bufferForPlaybackMs
            this.bufferForPlaybackAfterRebufferMs = bufferForPlaybackAfterRebufferMs
        }

        @Suppress("UNUSED_PARAMETER")
        fun setBufferDurationsMsForLocalPlayback(minBufferMs: Int, maxBufferMs: Int, bufferForPlaybackMs: Int, bufferForPlaybackAfterRebufferMs: Int): Builder = this

        fun setTargetBufferBytes(targetBufferBytes: Int): Builder = apply { this.targetBufferBytes = targetBufferBytes }

        @Suppress("UNUSED_PARAMETER")
        fun setPrioritizeTimeOverSizeThresholds(prioritize: Boolean): Builder = this

        @Suppress("UNUSED_PARAMETER")
        fun setBackBuffer(backBufferDurationMs: Int, retainBackBufferFromKeyframe: Boolean): Builder = apply { this.backBufferMs = backBufferDurationMs }

        fun build(): DefaultLoadControl = DefaultLoadControl(
            com.tmplayer.ios.media3.ReadAhead(
                minBufferMs, maxBufferMs, bufferForPlaybackMs, bufferForPlaybackAfterRebufferMs, targetBufferBytes, backBufferMs,
            ),
        )
    }

    companion object {
        const val DEFAULT_MIN_BUFFER_MS = 50_000
        const val DEFAULT_MAX_BUFFER_MS = 50_000
        const val DEFAULT_BUFFER_FOR_PLAYBACK_MS = 2_500
        const val DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000

        /** Media3 sizes it from the tracks when unset; the engine keeps its own then. */
        const val DEFAULT_TARGET_BUFFER_BYTES = -1
        const val DEFAULT_BACK_BUFFER_DURATION_MS = 0
    }
}

/** How a decoder was reused across a format change, Media3's. */
class DecoderReuseEvaluation(val decoderName: String, val oldFormat: androidx.media3.common.Format, val newFormat: androidx.media3.common.Format, val result: Int, val discardReasons: Int)

/** A decoder's counts, Media3's: on iOS the engine's frames are not counted here. */
class DecoderCounters {
    var decoderInitCount = 0
    var decoderReleaseCount = 0
    var queuedInputBufferCount = 0
    var skippedInputBufferCount = 0
    var renderedOutputBufferCount = 0
    var droppedBufferCount = 0
    var droppedInputBufferCount = 0
    var skippedOutputBufferCount = 0
    var maxConsecutiveDroppedBufferCount = 0
    var droppedToKeyframeCount = 0
    var totalVideoFrameProcessingOffsetUs = 0L
    var videoFrameProcessingOffsetCount = 0

    fun ensureUpdated() = Unit
}
