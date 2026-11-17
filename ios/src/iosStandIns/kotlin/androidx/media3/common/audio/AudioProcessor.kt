package androidx.media3.common.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

// Media3's audio processors on iOS, the way Android's player builds Volume boost and the sound
// delay into ExoPlayer (VolumeBoost.kt, SyncShims.kt). The engine processes sound itself, so these
// compile Android's processors as written; the features reach the engine as commands instead
// (EngineExoPlayer, docs/IOS.md, layer 2).

/** Media3's processor of PCM audio. */
interface AudioProcessor {
    fun configure(inputAudioFormat: AudioFormat): AudioFormat

    fun isActive(): Boolean

    fun queueInput(inputBuffer: ByteBuffer)

    fun queueEndOfStream()

    fun getOutput(): ByteBuffer

    fun isEnded(): Boolean

    fun flush(streamMetadata: StreamMetadata)

    fun reset()

    fun getDurationAfterProcessorApplied(durationUs: Long): Long = durationUs

    /** A PCM format, Media3's. */
    class AudioFormat(val sampleRate: Int, val channelCount: Int, val encoding: Int) {
        val bytesPerFrame: Int = if (isEncodingLinearPcm(encoding)) bytesPerSample(encoding) * channelCount else androidx.media3.common.Format.NO_VALUE

        override fun equals(other: Any?): Boolean =
            other is AudioFormat && other.sampleRate == sampleRate && other.channelCount == channelCount && other.encoding == encoding

        override fun hashCode(): Int = (31 * sampleRate + channelCount) * 31 + encoding

        override fun toString(): String = "AudioFormat[sampleRate=$sampleRate, channelCount=$channelCount, encoding=$encoding]"

        companion object {
            val NOT_SET: AudioFormat = AudioFormat(androidx.media3.common.Format.NO_VALUE, androidx.media3.common.Format.NO_VALUE, androidx.media3.common.Format.NO_VALUE)

            private fun isEncodingLinearPcm(encoding: Int): Boolean = encoding in setOf(
                androidx.media3.common.C.ENCODING_PCM_8BIT, androidx.media3.common.C.ENCODING_PCM_16BIT,
                androidx.media3.common.C.ENCODING_PCM_16BIT_BIG_ENDIAN, androidx.media3.common.C.ENCODING_PCM_24BIT,
                androidx.media3.common.C.ENCODING_PCM_24BIT_BIG_ENDIAN, androidx.media3.common.C.ENCODING_PCM_32BIT,
                androidx.media3.common.C.ENCODING_PCM_32BIT_BIG_ENDIAN, androidx.media3.common.C.ENCODING_PCM_FLOAT,
            )

            private fun bytesPerSample(encoding: Int): Int = when (encoding) {
                androidx.media3.common.C.ENCODING_PCM_8BIT -> 1
                androidx.media3.common.C.ENCODING_PCM_16BIT, androidx.media3.common.C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 2
                androidx.media3.common.C.ENCODING_PCM_24BIT, androidx.media3.common.C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 3
                else -> 4
            }
        }
    }

    /** Where a stream starts, given to a flush, Media3's. */
    class StreamMetadata(val positionOffsetUs: Long) {
        companion object {
            val DEFAULT: StreamMetadata = StreamMetadata(androidx.media3.common.C.TIME_UNSET)
        }
    }

    /** A format a processor cannot take, Media3's. */
    class UnhandledAudioFormatException(message: String?, val inputAudioFormat: AudioFormat) : Exception("$message $inputAudioFormat") {
        constructor(inputAudioFormat: AudioFormat) : this("Unhandled input format:", inputAudioFormat)
    }

    companion object {
        val EMPTY_BUFFER: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }
}

/** Media3's base for an audio processor that writes into a buffer of its own. */
abstract class BaseAudioProcessor : AudioProcessor {
    protected var inputAudioFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET
    protected var outputAudioFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var pendingInputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var pendingOutputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    final override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        pendingInputAudioFormat = inputAudioFormat
        pendingOutputAudioFormat = onConfigure(inputAudioFormat)
        return if (isActive()) pendingOutputAudioFormat else AudioProcessor.AudioFormat.NOT_SET
    }

    override fun isActive(): Boolean = pendingOutputAudioFormat != AudioProcessor.AudioFormat.NOT_SET

    final override fun queueEndOfStream() {
        inputEnded = true
        onQueueEndOfStream()
    }

    override fun getOutput(): ByteBuffer {
        val output = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return output
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === AudioProcessor.EMPTY_BUFFER

    final override fun flush(streamMetadata: AudioProcessor.StreamMetadata) {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        inputAudioFormat = pendingInputAudioFormat
        outputAudioFormat = pendingOutputAudioFormat
        onFlush(streamMetadata)
    }

    final override fun reset() {
        flush(AudioProcessor.StreamMetadata.DEFAULT)
        buffer = AudioProcessor.EMPTY_BUFFER
        pendingInputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        pendingOutputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        inputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        outputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        onReset()
    }

    /** A buffer of at least [size] bytes for the output, which getOutput hands out next. */
    protected fun replaceOutputBuffer(size: Int): ByteBuffer {
        if (buffer.capacity() < size) {
            buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        } else {
            buffer.clear()
        }
        outputBuffer = buffer
        return buffer
    }

    protected fun hasPendingOutput(): Boolean = outputBuffer.hasRemaining()

    protected open fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET

    protected open fun onQueueEndOfStream() {}

    protected open fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {}

    protected open fun onReset() {}
}
