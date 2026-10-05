package com.tmplayer.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import java.nio.ByteBuffer

/**
 * A text renderer that runs [delayUs] behind the picture, which is how the subtitle delay works.
 *
 * Media3 has no subtitle offset of its own, and the cues come out of the renderer already timed,
 * so the only place to move them is the clock the renderer is handed. Telling it the video is at
 * `position - delay` shows each line [delayUs] later; a negative delay asks it for lines ahead of
 * the picture, which it can give because a text track is read well ahead of playback.
 *
 * [delayUs] is read on the playback thread each frame and written from the main thread, hence
 * the lambda over a volatile field in the activity.
 */
@UnstableApi
class DelayedTextRenderer(
    renderer: Renderer,
    private val delayUs: () -> Long,
) : ForwardingRenderer(renderer) {
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        super.render((positionUs - delayUs()).coerceAtLeast(0L), elapsedRealtimeUs)
    }
}

/**
 * Moves the sound against the picture, which is how the audio delay works.
 *
 * The player's clock is the audio itself: the position is however much sound has been played. So
 * slipping silence into the stream holds the sound back by that long while the picture carries on
 * (a positive delay), and dropping samples brings it forward (a negative one). The offset is
 * applied from scratch after every flush, which is what a seek is to an audio processor, so it
 * survives seeking without being counted twice.
 *
 * Silence is fed out a slice at a time rather than as one buffer: a ten second gap at 7.1 float is
 * twenty megabytes, which a 1 GB stick should never be asked for in one go.
 *
 * A Dolby or DTS track sent to the receiver untouched (passthrough) skips every audio processor,
 * so the delay does nothing there; decoded audio, which is most of it, is covered.
 */
@UnstableApi
class AudioOffsetProcessor : BaseAudioProcessor() {

    /** The offset wanted, in microseconds, positive for later. Set from any thread. */
    @Volatile
    var offsetUs: Long = 0L

    /** How many frames of offset have been applied since the last flush, positive for silence. */
    private var appliedFrames = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // Silence has to be zero bytes for the trick to be silent, which rules out 8 bit PCM. That
        // track is left alone rather than refused: refusing would fail the whole audio sink.
        if (inputAudioFormat.encoding == C.ENCODING_PCM_8BIT) return AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val format = inputAudioFormat
        val frameBytes = format.bytesPerFrame
        val wanted = offsetUs * format.sampleRate / 1_000_000L
        val owed = wanted - appliedFrames

        if (owed > 0) {
            // Silence first, a slice per call; the input waits, since what is left of it is
            // handed back on the next call.
            val frames = minOf(owed, format.sampleRate.toLong() / SILENCE_SLICES_PER_SECOND)
            val bytes = (frames * frameBytes).toInt()
            // Counted rather than filled to the limit: a reused buffer comes back cleared to its
            // whole capacity, which can be more than this slice.
            val silence = replaceOutputBuffer(bytes)
            repeat(bytes) { silence.put(0) }
            silence.flip()
            appliedFrames += frames
            return
        }

        if (owed < 0) {
            val dropFrames = minOf(-owed, (inputBuffer.remaining() / frameBytes).toLong())
            inputBuffer.position(inputBuffer.position() + (dropFrames * frameBytes).toInt())
            appliedFrames -= dropFrames
        }

        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        replaceOutputBuffer(remaining).put(inputBuffer).flip()
    }

    override fun onFlush() {
        appliedFrames = 0L
    }

    override fun onReset() {
        appliedFrames = 0L
    }

    private companion object {
        /** A tenth of a second of silence per call at most. */
        const val SILENCE_SLICES_PER_SECOND = 10
    }
}
