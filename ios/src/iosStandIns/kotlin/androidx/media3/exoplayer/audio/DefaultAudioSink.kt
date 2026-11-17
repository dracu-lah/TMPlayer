package androidx.media3.exoplayer.audio

// ExoPlayer's audio output on iOS, by name: the engine plays the sound, so this compiles Android's
// tuning of it (PassthroughBufferCap) as written.

/** Where decoded sound goes, Media3's; on iOS the engine's, so only its processors matter here. */
interface AudioSink

/** What an audio renderer reports, Media3's: every call has an empty default. */
interface AudioRendererEventListener {
    fun onAudioUnderrun(bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {}
}

/** Where an audio sink writes, Media3's. */
interface AudioOutputProvider

/** Media3's AudioTrack output, its buffer sizes kept as set. */
class AudioTrackAudioOutputProvider private constructor(val bufferSizeProvider: DefaultAudioSink.AudioTrackBufferSizeProvider) : AudioOutputProvider {
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
