package com.tmplayer.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow

/**
 * Volume boost, the night mode: a gain to lift quiet dialogue and a compressor behind it to hold
 * the loud scenes back down, so the explosion that follows a whisper does not wake the house.
 *
 * The gain is [BOOST], about 9 dB. The compressor follows the loudest channel of each frame with a
 * fast attack and a slow release, and above [THRESHOLD] squeezes anything louder at [RATIO] to one,
 * which is what keeps a full scale peak lifted by the gain from clipping: it lands near -4.5 dBFS.
 * A hard limit at full scale catches whatever the attack is too slow for.
 *
 * Always in the chain, so it can be switched while the video plays: the sink's processors are
 * fixed when the player is built. Off, it copies the sound straight through. Switching ramps the
 * gain over [RAMP_SECONDS] rather than jumping, which would click.
 *
 * Handles 16 bit and float PCM, which is what the decoders here hand over. Anything else, and a
 * Dolby or DTS track sent to the receiver untouched, passes it by.
 */
@UnstableApi
class VolumeBoostProcessor : BaseAudioProcessor() {

    /** Whether the boost is wanted. Set from the main thread, read on the playback thread. */
    @Volatile
    var enabled: Boolean = false

    /** The gain actually applied, walking towards 1 or [BOOST]. */
    private var gain = 1f

    /** The level the compressor is following, in full scale units after the gain. */
    private var envelope = 0f

    private var attack = 0f
    private var release = 0f
    private var rampStep = 0f
    private var frame = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        val rate = inputAudioFormat.sampleRate.toFloat()
        attack = coefficient(ATTACK_SECONDS, rate)
        release = coefficient(RELEASE_SECONDS, rate)
        rampStep = (BOOST - 1f) / (RAMP_SECONDS * rate)
        frame = FloatArray(inputAudioFormat.channelCount)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        if (!enabled && gain == 1f) {
            envelope = 0f
            out.put(inputBuffer).flip()
            return
        }
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val channels = frame.size
        val frames = remaining / inputAudioFormat.bytesPerFrame
        val target = if (enabled) BOOST else 1f
        repeat(frames) {
            for (c in 0 until channels) {
                frame[c] = if (float) input.getFloat() else input.getShort() / SHORT_SCALE
            }
            gain = if (gain < target) minOf(target, gain + rampStep) else maxOf(target, gain - rampStep)
            var peak = 0f
            for (c in 0 until channels) peak = maxOf(peak, abs(frame[c]))
            peak *= gain
            envelope += (if (peak > envelope) attack else release) * (peak - envelope)
            val squeeze = if (envelope > THRESHOLD) (THRESHOLD / envelope).pow(1f - 1f / RATIO) else 1f
            val scale = gain * squeeze
            for (c in 0 until channels) {
                val sample = (frame[c] * scale).coerceIn(-1f, 1f)
                if (float) {
                    out.putFloat(sample)
                } else {
                    out.putShort((sample * SHORT_SCALE).toInt().coerceIn(-32768, 32767).toShort())
                }
            }
        }
        out.flip()
    }

    override fun onFlush() {
        envelope = 0f
    }

    override fun onReset() {
        envelope = 0f
        gain = 1f
    }

    companion object {
        /** About +9 dB. */
        const val BOOST = 2.8f

        /** About -9 dBFS, where the squeeze starts. */
        const val THRESHOLD = 0.35f
        const val RATIO = 4f

        private const val ATTACK_SECONDS = 0.005f
        private const val RELEASE_SECONDS = 0.25f
        private const val RAMP_SECONDS = 0.08f
        private const val SHORT_SCALE = 32768f

        /** A one pole follower's step for a time constant of [seconds]. */
        private fun coefficient(seconds: Float, rate: Float): Float =
            1f - kotlin.math.exp(-1f / (seconds * rate))
    }
}
