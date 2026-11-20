package com.tmplayer.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider

/**
 * Audio passthrough off (A2, research 4.1): the sound output, except that it says no to every
 * bitstream (Dolby, DTS) and yes to plain PCM as before. Media3 asks the output what it takes
 * before it picks a decoder, so a Dolby or DTS track the television or receiver would have taken
 * as it is goes to a decoder instead (the device's, or the app's FFmpeg one) and reaches the
 * output as PCM, which Android mixes down where the output has fewer channels.
 *
 * Why a wrapper and not Media3's fixed `AudioCapabilities`: the player builds its own
 * [androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider] (for the buffer sizes), and
 * Media3 1.11 only hands fixed capabilities to the output it builds itself.
 */
@UnstableApi
class PcmOnlyOutputProvider(output: AudioOutputProvider) : ForwardingAudioOutputProvider(output) {

    override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport =
        if (AudioPolicy.bitstream(formatConfig.format)) AudioOutputProvider.FormatSupport.UNSUPPORTED else super.getFormatSupport(formatConfig)

    companion object {
        /** [output] as it is when passthrough is allowed, else wrapped to take PCM only. */
        fun of(output: AudioOutputProvider, passthroughOff: Boolean): AudioOutputProvider =
            if (passthroughOff) PcmOnlyOutputProvider(output) else output
    }
}
