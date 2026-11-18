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
 * Volume boost and Night mode, one processor: a gain to lift quiet dialogue and a compressor
 * behind it to hold the loud scenes back down, so the explosion that follows a whisper does not
 * wake the house.
 *
 * Volume boost makes everything louder and keeps the peaks from clipping. Night mode ([night])
 * squeezes the range itself: a smaller lift ([NIGHT_GAIN], about 6 dB) and a compressor that
 * starts far lower ([NIGHT_THRESHOLD], about -15 dBFS) at a steeper [NIGHT_RATIO], so a full scale
 * peak lands near -11 dBFS while dialogue at -30 dBFS comes up to -24: the loud moments end up
 * quieter than they were, the quiet ones louder. The two together stack their gains.
 *
 * The gain is [BOOST], about 9 dB. The compressor follows the loudest channel of each frame with a
 * fast attack and a slow release, and above [THRESHOLD] squeezes anything louder at [RATIO] to one,
 * which is what keeps a full scale peak lifted by the gain from clipping: it lands near -4.5 dBFS.
 * A hard limit at full scale catches whatever the attack is too slow for.
 *
 * Even out volume ([level], on unless the viewer turns it off) lifts a track that is quiet as a
 * whole: films are mastered for a cinema, around -24 to -31 LUFS with their dialogue low, where a
 * video made for phones sits near -14, so the same volume setting played the film 10 dB or more
 * quieter. It follows the track's average level (the mean square of its channels over about
 * [LEVEL_SECONDS], with silence left out so a pause does not count as quiet) and lifts it towards
 * [LEVEL_TARGET] (about -18 dBFS), at most [LEVEL_MAX] (about +12 dB), rising slowly and falling
 * fast so it never pumps. It only ever lifts: a track already at that level or louder is left
 * as it is. Its lift goes through the same compressor and limit as the boost, so nothing clips.
 *
 * Always in the chain, so it can be switched while the video plays: the sink's processors are
 * fixed when the player is built. Off, it copies the sound straight through. Switching ramps the
 * gain over [RAMP_SECONDS] rather than jumping, which would click.
 *
 * Quieter than the file is not here: Player volume ([PlayerVolume]) is the engine's own gain,
 * applied after this chain by the audio output, so the boost, Night mode and the leveller hear
 * and shape the same sound whatever it is set to, and a figure under 100 per cent costs nothing.
 *
 * Handles 16 bit and float PCM, which is what the decoders here hand over. Anything else, and a
 * Dolby or DTS track sent to the receiver untouched, passes it by.
 */
@UnstableApi
class VolumeBoostProcessor : BaseAudioProcessor() {

    /** Whether the boost is wanted. Set from the main thread, read on the playback thread. */
    @Volatile
    var enabled: Boolean = false

    /** Night mode. Set from the main thread, read on the playback thread, like [enabled]. */
    @Volatile
    var night: Boolean = false

    /** Even out volume. Set from the main thread, read on the playback thread, like [enabled]. */
    @Volatile
    var level: Boolean = true

    /** The track's average level as the leveller hears it: a mean square, before any gain. */
    private var loudness = LEVEL_TARGET_SQUARE

    /** The leveller's lift as applied, walking towards what [loudness] asks for. */
    private var levelGain = 1f

    /**
     * What the leveller's lift is walking towards, worked out from [loudness] once every
     * [LEVEL_BLOCK] frames rather than every frame: [levelFor] is a square root, and [loudness]
     * moves on a two second time constant, so a few milliseconds' stale answer is inaudible.
     */
    private var levelWanted = 1f

    /** Frames until [levelWanted] is worked out again; counted across buffers. */
    private var levelCountdown = 0

    private var loudnessStep = 0f
    private var levelRise = 0f
    private var levelFall = 0f

    /** The gain actually applied, walking towards 1 or [BOOST]. */
    private var gain = 1f

    /** The level the compressor is following, in full scale units after the gain. */
    private var envelope = 0f

    private var attack = 0f
    private var release = 0f
    private var rampStep = 0f
    private var frame = FloatArray(0)

    /**
     * A whole buffer's samples, read and written in bulk: one call each way instead of a
     * `getShort` and a `putShort` per sample, which for a 5.1 track is over a quarter of a million
     * calls a second on the playback thread of a TV stick.
     */
    private var samples = FloatArray(0)
    private var shorts = ShortArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        val rate = inputAudioFormat.sampleRate.toFloat()
        attack = coefficient(ATTACK_SECONDS, rate)
        release = coefficient(RELEASE_SECONDS, rate)
        rampStep = (BOOST * NIGHT_GAIN - 1f) / (RAMP_SECONDS * rate)
        loudnessStep = coefficient(LEVEL_SECONDS, rate)
        levelRise = coefficient(LEVEL_RISE_SECONDS, rate)
        levelFall = coefficient(LEVEL_FALL_SECONDS, rate)
        frame = FloatArray(inputAudioFormat.channelCount)
        levelCountdown = 0
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        val night = night
        val level = level
        // The leveller's lift eases back to 1 when Even out volume is turned off, but in floats it
        // stalls a hair above (about 1.001), and the copy below never ran again: every sample went
        // through the full chain for the rest of the video for a lift nobody can hear.
        if (!level && abs(levelGain - 1f) < LEVEL_SNAP) levelGain = 1f
        if (!enabled && !night && !level && gain == 1f && levelGain == 1f) {
            envelope = 0f
            out.put(inputBuffer).flip()
            return
        }
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val channels = frame.size
        val frames = remaining / inputAudioFormat.bytesPerFrame
        val count = frames * channels
        if (samples.size < count) samples = FloatArray(count)
        val buffer = samples
        if (float) {
            input.asFloatBuffer().get(buffer, 0, count)
        } else {
            if (shorts.size < count) shorts = ShortArray(count)
            input.asShortBuffer().get(shorts, 0, count)
            for (i in 0 until count) buffer[i] = shorts[i] / SHORT_SCALE
        }
        // The views above read without moving the buffer: it is consumed here, as before.
        input.position(input.position() + frames * inputAudioFormat.bytesPerFrame)
        val target = targetGain(enabled, night)
        // Even out volume alone keeps the track's own dynamics: only the very top of the range,
        // which its lift or a surround fold may push past full scale, is held back.
        val levelOnly = !enabled && !night
        val threshold = if (night) NIGHT_THRESHOLD else if (levelOnly) LEVEL_THRESHOLD else THRESHOLD
        val ratio = if (night) NIGHT_RATIO else if (levelOnly) LEVEL_RATIO else RATIO
        var at = 0
        repeat(frames) {
            gain = if (gain < target) minOf(target, gain + rampStep) else maxOf(target, gain - rampStep)
            var square = 0f
            var peak = 0f
            for (c in 0 until channels) {
                val sample = buffer[at + c]
                square += sample * sample
                peak = maxOf(peak, abs(sample))
            }
            square /= channels
            // Silence and near silence say nothing about how loud the track is.
            if (square > LEVEL_GATE_SQUARE) loudness += loudnessStep * (square - loudness)
            if (levelCountdown <= 0) {
                levelWanted = levelFor(loudness)
                levelCountdown = LEVEL_BLOCK
            }
            levelCountdown--
            val wanted = if (level) levelWanted else 1f
            levelGain += (if (wanted > levelGain) levelRise else levelFall) * (wanted - levelGain)
            val lift = gain * levelGain
            peak *= lift
            envelope += (if (peak > envelope) attack else release) * (peak - envelope)
            val squeeze = squeeze(envelope, threshold, ratio)
            val scale = lift * squeeze
            // The frame's loudest sample, scaled, is the most any of them reaches: a frame that
            // stays clear of full scale (nearly all of them) needs no clamp per sample. The margin
            // covers the rounding of multiplying in another order.
            if (peak * squeeze < CLAMP_FREE) {
                for (c in 0 until channels) buffer[at + c] *= scale
            } else {
                for (c in 0 until channels) {
                    buffer[at + c] = (buffer[at + c] * scale).coerceIn(-1f, 1f)
                }
            }
            at += channels
        }
        if (float) {
            out.asFloatBuffer().put(buffer, 0, count)
        } else {
            val pcm = shorts
            for (i in 0 until count) {
                pcm[i] = (buffer[i] * SHORT_SCALE).toInt().coerceIn(-32768, 32767).toShort()
            }
            out.asShortBuffer().put(pcm, 0, count)
        }
        out.position(count * (if (float) 4 else 2))
        out.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        envelope = 0f
    }

    override fun onReset() {
        envelope = 0f
        gain = 1f
        // A new track is measured afresh. A seek (onFlush) keeps what was learnt of this one.
        loudness = LEVEL_TARGET_SQUARE
        levelGain = 1f
        levelCountdown = 0
    }

    companion object {
        /** About +9 dB. */
        const val BOOST = 2.8f

        /** About -9 dBFS, where the squeeze starts. */
        const val THRESHOLD = 0.35f
        const val RATIO = 4f

        /** Night mode's lift, about +6 dB. */
        const val NIGHT_GAIN = 2f

        /** About -15 dBFS: Night mode squeezes everything above dialogue level. */
        const val NIGHT_THRESHOLD = 0.18f
        const val NIGHT_RATIO = 6f

        /** Even out volume's aim, about -18 dBFS of average level. */
        const val LEVEL_TARGET = 0.126f

        /** Even out volume's largest lift, about +12 dB. */
        const val LEVEL_MAX = 4f

        private const val LEVEL_TARGET_SQUARE = LEVEL_TARGET * LEVEL_TARGET

        /**
         * About -1 dBFS and 20 to 1: Even out volume's own squeeze, a soft limit at the very top
         * that a track at its own level barely touches, and that catches the peaks of its lift and
         * of a surround fold (whose channels add up past full scale) instead of clipping them.
         */
        const val LEVEL_THRESHOLD = 0.89f
        const val LEVEL_RATIO = 20f

        /** About -60 dBFS: below this a frame is silence and is not measured. */
        private const val LEVEL_GATE_SQUARE = 1e-6f

        /** How long the average level is taken over. */
        const val LEVEL_SECONDS = 2f
        private const val LEVEL_RISE_SECONDS = 1.5f
        private const val LEVEL_FALL_SECONDS = 0.3f

        /**
         * The lift Even out volume wants for a track whose average level is [meanSquare]: towards
         * [LEVEL_TARGET], never below 1 (it only lifts) and never above [LEVEL_MAX].
         */
        fun levelFor(meanSquare: Float): Float =
            if (meanSquare <= 0f) LEVEL_MAX else kotlin.math.sqrt(LEVEL_TARGET_SQUARE / meanSquare).coerceIn(1f, LEVEL_MAX)

        /** The gain the ramp walks towards for these switches. */
        fun targetGain(boost: Boolean, night: Boolean): Float =
            (if (boost) BOOST else 1f) * (if (night) NIGHT_GAIN else 1f)

        /** What a level of [envelope] is multiplied by: 1 below [threshold], [ratio] to one above. */
        fun squeeze(envelope: Float, threshold: Float, ratio: Float): Float =
            if (envelope > threshold) (threshold / envelope).pow(1f - 1f / ratio) else 1f

        /**
         * Where a steady level [input] (full scale units) comes out once the compressor has
         * settled on it, for the tests and the sentence above.
         */
        fun settledOutput(input: Float, boost: Boolean, night: Boolean): Float {
            val lifted = input * targetGain(boost, night)
            val threshold = if (night) NIGHT_THRESHOLD else THRESHOLD
            val ratio = if (night) NIGHT_RATIO else RATIO
            return (lifted * squeeze(lifted, threshold, ratio)).coerceAtMost(1f)
        }

        private const val ATTACK_SECONDS = 0.005f
        private const val RELEASE_SECONDS = 0.25f
        private const val RAMP_SECONDS = 0.08f
        private const val SHORT_SCALE = 32768f

        /** Frames between two answers of [levelFor]: about 5 ms at 48 kHz. */
        private const val LEVEL_BLOCK = 256

        /** Below this a frame's scaled peak cannot round past full scale, so it is not clamped. */
        private const val CLAMP_FREE = 0.999f

        /** How near 1 the leveller's lift has to come, with the leveller off, to count as 1. */
        private const val LEVEL_SNAP = 0.01f

        /** A one pole follower's step for a time constant of [seconds]. */
        private fun coefficient(seconds: Float, rate: Float): Float =
            1f - kotlin.math.exp(-1f / (seconds * rate))
    }
}
