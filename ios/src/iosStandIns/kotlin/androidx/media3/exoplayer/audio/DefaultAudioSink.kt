package androidx.media3.exoplayer.audio

// ExoPlayer's audio output on iOS, by name: the engine plays the sound, so this compiles Android's
// tuning of it (PassthroughBufferCap) as written.

/** Where decoded sound goes, Media3's; on iOS the engine's, so only its processors matter here. */
interface AudioSink {
    /** What an audio track was opened with, Media3's: the encoding says PCM or a bitstream. */
    class AudioTrackConfig(
        val encoding: Int,
        val sampleRate: Int,
        val channelConfig: Int,
        val tunneling: Boolean,
        val offload: Boolean,
        val bufferSize: Int,
    )

    /** An audio track that could not start, Media3's: the format it was for is kept. */
    class InitializationException(
        val audioTrackState: Int,
        val sampleRate: Int,
        val channelConfig: Int,
        val bufferSize: Int,
        val format: androidx.media3.common.Format,
        val isRecoverable: Boolean,
        cause: Exception?,
    ) : Exception("AudioTrack init failed $audioTrackState Config($sampleRate, $channelConfig, $bufferSize)" + if (isRecoverable) " (recoverable)" else "", cause)
}

/** What an audio renderer reports, Media3's: every call has an empty default. */
interface AudioRendererEventListener {
    fun onAudioUnderrun(bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {}
}

/**
 * Where an audio sink writes, Media3's. Only the question the player's wrapper overrides is here
 * ([getFormatSupport]); no sound goes through it on iOS, where the engine plays it.
 */
interface AudioOutputProvider {
    /** What an output is asked about: the format to play. */
    class FormatConfig(val format: androidx.media3.common.Format)

    /** The answer. Only the two the player's code names; an output that plays nothing supports nothing. */
    class FormatSupport private constructor(val supportLevel: Int) {
        companion object {
            const val SUPPORT_LEVEL_UNSUPPORTED = 0
            const val SUPPORT_LEVEL_DIRECTLY = 1
            val UNSUPPORTED = FormatSupport(SUPPORT_LEVEL_UNSUPPORTED)
            val SUPPORTED_DIRECTLY = FormatSupport(SUPPORT_LEVEL_DIRECTLY)
        }
    }

    fun getFormatSupport(formatConfig: FormatConfig): FormatSupport
}

/** Media3's output that hands every call to another. */
open class ForwardingAudioOutputProvider(private val output: AudioOutputProvider) : AudioOutputProvider {
    override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport =
        output.getFormatSupport(formatConfig)
}

/** Media3's AudioTrack output, its buffer sizes kept as set. */
class AudioTrackAudioOutputProvider private constructor(val bufferSizeProvider: DefaultAudioSink.AudioTrackBufferSizeProvider) : AudioOutputProvider {
    /** There is no AudioTrack on iOS, so no format is supported through it. */
    override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport =
        AudioOutputProvider.FormatSupport.UNSUPPORTED

    class Builder(@Suppress("UNUSED_PARAMETER") context: android.content.Context) {
        private var sizes: DefaultAudioSink.AudioTrackBufferSizeProvider = DefaultAudioSink.AudioTrackBufferSizeProvider.DEFAULT

        fun setAudioTrackBufferSizeProvider(provider: DefaultAudioSink.AudioTrackBufferSizeProvider): Builder = apply { sizes = provider }

        fun build(): AudioTrackAudioOutputProvider = AudioTrackAudioOutputProvider(sizes)
    }
}

/** Media3's buffer sizes for an AudioTrack, by duration. */
class DefaultAudioTrackBufferSizeProvider private constructor() : DefaultAudioSink.AudioTrackBufferSizeProvider {
    override fun getBufferSizeInBytes(
        minBufferSizeInBytes: Int,
        encoding: Int,
        outputMode: Int,
        pcmFrameSize: Int,
        sampleRate: Int,
        bitrate: Int,
        maxAudioTrackPlaybackSpeed: Double,
    ): Int = minBufferSizeInBytes

    class Builder {
        fun setMinPcmBufferDurationUs(durationUs: Int): Builder = this

        fun setMaxPcmBufferDurationUs(durationUs: Int): Builder = this

        fun setTargetPcmBufferDurationUs(durationUs: Int): Builder = this

        fun setPcmBufferMultiplicationFactor(factor: Int): Builder = this

        fun setPassthroughBufferDurationUs(durationUs: Int): Builder = this

        fun setOffloadBufferDurationUs(durationUs: Int): Builder = this

        fun setAc3BufferMultiplicationFactor(factor: Int): Builder = this

        fun build(): DefaultAudioTrackBufferSizeProvider = DefaultAudioTrackBufferSizeProvider()
    }
}

/** Media3's default audio sink: on iOS, the processors Android's player chains, for the engine. */
class DefaultAudioSink private constructor(val audioProcessors: List<androidx.media3.common.audio.AudioProcessor>) : AudioSink {
    class Builder(@Suppress("UNUSED_PARAMETER") context: android.content.Context?) {
        private var processors: List<androidx.media3.common.audio.AudioProcessor> = emptyList()

        fun setEnableFloatOutput(enableFloatOutput: Boolean): Builder = this

        fun setEnableAudioOutputPlaybackParameters(enable: Boolean): Builder = this

        fun setEnableAudioTrackPlaybackParams(enable: Boolean): Builder = this

        fun setAudioProcessors(audioProcessors: Array<androidx.media3.common.audio.AudioProcessor>): Builder = apply { processors = audioProcessors.toList() }

        fun setAudioOutputProvider(provider: AudioOutputProvider): Builder = this

        fun setAudioTrackBufferSizeProvider(provider: AudioTrackBufferSizeProvider): Builder = this

        fun build(): DefaultAudioSink = DefaultAudioSink(processors)
    }

    /** How large an audio track's buffer is, Media3's. */
    interface AudioTrackBufferSizeProvider {
        fun getBufferSizeInBytes(
            minBufferSizeInBytes: Int,
            encoding: Int,
            outputMode: Int,
            pcmFrameSize: Int,
            sampleRate: Int,
            bitrate: Int,
            maxAudioTrackPlaybackSpeed: Double,
        ): Int

        companion object {
            val DEFAULT: AudioTrackBufferSizeProvider = object : AudioTrackBufferSizeProvider {
                override fun getBufferSizeInBytes(
                    minBufferSizeInBytes: Int,
                    encoding: Int,
                    outputMode: Int,
                    pcmFrameSize: Int,
                    sampleRate: Int,
                    bitrate: Int,
                    maxAudioTrackPlaybackSpeed: Double,
                ): Int = minBufferSizeInBytes
            }
        }
    }

    companion object {
        const val OUTPUT_MODE_PCM = 0
        const val OUTPUT_MODE_OFFLOAD = 1
        const val OUTPUT_MODE_PASSTHROUGH = 2
    }
}
