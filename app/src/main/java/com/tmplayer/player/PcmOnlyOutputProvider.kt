package com.tmplayer.player

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider

/**
 * Audio passthrough off (A2, research 4.1): the sound output, except that it says no to a Dolby or
 * DTS bitstream a decoder here can turn into PCM ([decodable]: the device's or the app's FFmpeg
 * one), and yes to everything else as before. Media3 asks the output what it takes before it picks
 * a decoder, so such a track goes to that decoder and reaches the output as PCM, which Android
 * mixes down where the output has fewer channels. A format nothing here decodes (TrueHD on a stick
 * without FFmpeg's decoder) is still offered as it is: refusing it would only play the video silent.
 *
 * Why a wrapper and not Media3's fixed `AudioCapabilities`: the player builds its own
 * [androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider] (for the buffer sizes), and
 * Media3 1.11 only hands fixed capabilities to the output it builds itself.
 */
@UnstableApi
class PcmOnlyOutputProvider(
    output: AudioOutputProvider,
    private val decodable: (Format) -> Boolean = AudioPolicy::decodable,
) : ForwardingAudioOutputProvider(output) {

    override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport {
        val format = formatConfig.format
        val refuse = AudioPolicy.dolbyOrDts(format) && runCatching { decodable(format) }.getOrDefault(false)
        return if (refuse) AudioOutputProvider.FormatSupport.UNSUPPORTED else super.getFormatSupport(formatConfig)
    }

    companion object {
        /** [output] as it is when passthrough is allowed, else wrapped to take PCM only. */
        fun of(output: AudioOutputProvider, passthroughOff: Boolean): AudioOutputProvider =
            if (passthroughOff) PcmOnlyOutputProvider(output) else output
    }
}
