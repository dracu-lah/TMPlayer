package androidx.media3.exoplayer.text

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities

/** Where a text renderer sends its cues, Media3's. */
fun interface TextOutput {
    fun onCues(cueGroup: CueGroup)
}

/**
 * Media3's text renderer. On iOS the engine draws the subtitles, so this one renders nothing; it is
 * here for what Android's player wraps around it (the subtitle delay, DelayedTextRenderer), which
 * reaches the engine as a command (PipelineFeatures).
 */
class TextRenderer(@Suppress("UNUSED_PARAMETER") output: TextOutput, @Suppress("UNUSED_PARAMETER") outputLooper: Looper?) : Renderer {
    override fun getTrackType(): Int = C.TRACK_TYPE_TEXT

    override fun getCapabilities(): RendererCapabilities = Capabilities

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) = Unit

    override fun isReady(): Boolean = true

    override fun isEnded(): Boolean = false

    override fun getName(): String = "TextRenderer"

    override fun getState(): Int = Renderer.STATE_DISABLED

    private object Capabilities : RendererCapabilities {
        override fun getName(): String = "TextRenderer"

        override fun getTrackType(): Int = C.TRACK_TYPE_TEXT

        override fun supportsFormat(format: androidx.media3.common.Format): Int = C.FORMAT_HANDLED

        override fun supportsMixedMimeTypeAdaptation(): Int = 0
    }
}
