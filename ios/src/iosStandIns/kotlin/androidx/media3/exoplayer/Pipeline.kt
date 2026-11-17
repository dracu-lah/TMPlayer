package androidx.media3.exoplayer

import androidx.media3.common.Format

// ExoPlayer's renderers on iOS: the names Android's player builds its pipeline from, with nothing
// behind them, since the engine decodes and draws by itself. They are here so the code that
// configures them compiles as upstream wrote it.

/** One of ExoPlayer's renderers, Media3's. */
interface Renderer {
    fun getTrackType(): Int

    fun getCapabilities(): RendererCapabilities

    fun render(positionUs: Long, elapsedRealtimeUs: Long)

    fun isReady(): Boolean

    fun isEnded(): Boolean

    fun getName(): String

    fun getState(): Int

    companion object {
        const val STATE_DISABLED = 0
        const val STATE_ENABLED = 1
        const val STATE_STARTED = 2
        const val MSG_SET_VOLUME = 2
        const val MSG_SET_VIDEO_OUTPUT = 1
    }
}

/** What a renderer can play, Media3's. */
interface RendererCapabilities {
    fun getName(): String

    fun getTrackType(): Int

    fun supportsFormat(format: Format): Int

    fun supportsMixedMimeTypeAdaptation(): Int

    companion object {
        const val ADAPTIVE_SEAMLESS = 2 shl 3
        const val ADAPTIVE_NOT_SEAMLESS = 1 shl 3
        const val ADAPTIVE_NOT_SUPPORTED = 0
        const val DECODER_SUPPORT_PRIMARY = 1 shl 7
        const val DECODER_SUPPORT_FALLBACK = 0
        const val HARDWARE_ACCELERATION_SUPPORTED = 1 shl 6
        const val HARDWARE_ACCELERATION_NOT_SUPPORTED = 0
        const val TUNNELING_NOT_SUPPORTED = 0

        fun create(formatSupport: Int): Int = formatSupport

        fun create(formatSupport: Int, adaptiveSupport: Int, tunnelingSupport: Int): Int = formatSupport or adaptiveSupport or tunnelingSupport

        fun getFormatSupport(supportFlags: Int): Int = supportFlags and 0b111
    }
}

/** A renderer that passes every call to another, Media3's. */
open class ForwardingRenderer(private val renderer: Renderer) : Renderer {
    override fun getTrackType(): Int = renderer.getTrackType()

    override fun getCapabilities(): RendererCapabilities = renderer.getCapabilities()

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) = renderer.render(positionUs, elapsedRealtimeUs)

    override fun isReady(): Boolean = renderer.isReady()

    override fun isEnded(): Boolean = renderer.isEnded()

    override fun getName(): String = renderer.getName()

    override fun getState(): Int = renderer.getState()
}
