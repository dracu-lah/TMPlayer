package com.tmplayer.ios.media3

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import com.tmplayer.player.AudioOffsetProcessor
import com.tmplayer.player.DelayedTextRenderer
import com.tmplayer.player.VolumeBoostProcessor

/**
 * The features Android's player builds into ExoPlayer's pipeline, read back so the engine can do
 * them instead: on Android they are audio processors and a renderer of its own (VolumeBoost.kt,
 * SyncShims.kt), here upstream's same classes, compiled unchanged, whose state the player keeps
 * setting as it does on Android. Read a few times a second by EngineExoPlayer and passed on as
 * the rules' commands (docs/IOS.md):
 *
 * - VolumeBoostProcessor's `enabled` and `night`: Volume boost and Night mode.
 * - AudioOffsetProcessor's `offsetUs`: the sound's delay.
 * - DelayedTextRenderer's delay, measured on the TextProbe it shifts time for: the subtitles' delay.
 * - ChannelMixingAudioProcessor's matrices, one folding more channels into two: the downmix.
 */
class PipelineFeatures(private val processors: List<AudioProcessor>, private val textRenderers: List<Renderer>) {
    /** What the pipeline asks for now. */
    data class Wanted(
        val volumeBoost: Boolean = false,
        val nightMode: Boolean = false,
        val audioDelayMs: Long = 0,
        val subtitleDelayMs: Long = 0,
        val downmix: Boolean = false,
    )

    fun read(): Wanted {
        val boost = processors.firstNotNullOfOrNull { it as? VolumeBoostProcessor }
        val offset = processors.firstNotNullOfOrNull { it as? AudioOffsetProcessor }
        val mixer = processors.firstNotNullOfOrNull { it as? ChannelMixingAudioProcessor }
        return Wanted(
            volumeBoost = boost?.enabled == true,
            nightMode = boost?.night == true,
            audioDelayMs = (offset?.offsetUs ?: 0) / 1000,
            subtitleDelayMs = subtitleDelayUs() / 1000,
            downmix = mixer?.matrices().orEmpty().any { it.getInputChannelCount() > 2 && it.getOutputChannelCount() == 2 },
        )
    }

    /** The delay a DelayedTextRenderer puts on time, from what reaches the probe under it. */
    private fun subtitleDelayUs(): Long {
        val delayed = textRenderers.firstNotNullOfOrNull { it as? DelayedTextRenderer } ?: return 0
        val probe = TextProbe.under(delayed) ?: return 0
        delayed.render(PROBE_TIME_US, 0)
        return PROBE_TIME_US - probe.lastPositionUs
    }

    companion object {
        /** A time far from zero, so a delay either way is measured whole (the renderer stops at zero). */
        private const val PROBE_TIME_US = 1_000_000_000_000L
    }
}

/**
 * The text renderer on iOS: subtitles are the engine's to draw, so this one draws nothing and
 * remembers the time it was last asked to render, which is how a DelayedTextRenderer around it
 * says its delay (PipelineFeatures).
 */
class TextProbe : Renderer, RendererCapabilities {
    var lastPositionUs: Long = 0
        private set

    override fun getTrackType(): Int = androidx.media3.common.C.TRACK_TYPE_TEXT

    override fun getCapabilities(): RendererCapabilities = this

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        lastPositionUs = positionUs
    }

    override fun isReady(): Boolean = true

    override fun isEnded(): Boolean = false

    override fun getName(): String = "TextProbe"

    override fun getState(): Int = Renderer.STATE_ENABLED

    override fun supportsFormat(format: androidx.media3.common.Format): Int = androidx.media3.common.C.FORMAT_HANDLED

    override fun supportsMixedMimeTypeAdaptation(): Int = RendererCapabilities.ADAPTIVE_NOT_SUPPORTED

    companion object {
        /** The probe a forwarding renderer wraps, found through the wrapper's own capabilities call. */
        internal fun under(renderer: Renderer): TextProbe? = renderer.getCapabilities() as? TextProbe
    }
}
