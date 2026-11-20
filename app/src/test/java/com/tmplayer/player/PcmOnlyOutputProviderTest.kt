package com.tmplayer.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Audio passthrough off (A2): the output says no to Dolby and DTS and yes to PCM, through Media3's
 * own sink, so its renderer picks a decoder instead of passing the bitstream through. The fake
 * output takes everything, so any refusal seen here is the wrapper's.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PcmOnlyOutputProviderTest {

    /** An output that takes every format as it is, as a receiver that lists them all would. */
    private class TakesEverything : AudioOutputProvider {
        var released = false
        override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.FormatSupport =
            AudioOutputProvider.FormatSupport.Builder().setFormatSupportLevel(AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY).build()
        override fun getOutputConfig(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.OutputConfig =
            throw UnsupportedOperationException("not opened in this test")
        override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput =
            throw UnsupportedOperationException("not opened in this test")
        override fun addListener(listener: AudioOutputProvider.Listener) = Unit
        override fun removeListener(listener: AudioOutputProvider.Listener) = Unit
        override fun release() {
            released = true
        }
    }

    private val dts: Format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_DTS).setChannelCount(6).setSampleRate(48_000).build()
    private val ac3: Format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3).setChannelCount(6).setSampleRate(48_000).build()

    private fun support(provider: AudioOutputProvider, format: Format) =
        provider.getFormatSupport(AudioOutputProvider.FormatConfig.Builder(format).build()).supportLevel

    @Test
    fun bitstreamsAreRefusedAndPcmGoesThrough() {
        val inner = TakesEverything()
        val pcmOnly = PcmOnlyOutputProvider(inner)
        for (format in listOf(EAC3, ac3, dts)) {
            assertEquals(format.sampleMimeType, AudioOutputProvider.FORMAT_UNSUPPORTED, support(pcmOnly, format))
            assertEquals(format.sampleMimeType, AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY, support(inner, format))
        }
        assertEquals(AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY, support(pcmOnly, PCM))
        // The rest is the output's own: released with it.
        pcmOnly.release()
        assertTrue(inner.released)
    }

    @Test
    fun passthroughAllowedKeepsTheOutputAsItIs() {
        val inner = TakesEverything()
        assertSame(inner, PcmOnlyOutputProvider.of(inner, passthroughOff = false))
        assertTrue(PcmOnlyOutputProvider.of(inner, passthroughOff = true) is PcmOnlyOutputProvider)
    }

    @Test
    fun mediaThreesSinkAsksTheOutputSoTheTrackIsDecodedInstead() {
        val context = RuntimeEnvironment.getApplication()
        val open = DefaultAudioSink.Builder(context).setAudioOutputProvider(TakesEverything()).build()
        val pcmOnly = DefaultAudioSink.Builder(context).setAudioOutputProvider(PcmOnlyOutputProvider.of(TakesEverything(), passthroughOff = true)).build()
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, open.getFormatSupport(EAC3))
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, pcmOnly.getFormatSupport(EAC3))
        assertFalse(pcmOnly.supportsFormat(dts))
        assertTrue(pcmOnly.supportsFormat(PCM))
    }

    @Test
    fun theSettingAndTheLaddersHopBothBuildPcmOnly() {
        assertFalse(DecoderPolicy.Default.passthroughOff)
        assertSame(DecoderPolicy.Default, DecoderPolicy.Default.allowingPassthrough(true))
        assertTrue(DecoderPolicy.Default.allowingPassthrough(false).passthroughOff)
        val hopped = DecoderPolicy.of(RemedyPath(passthroughOff = true), knownAsync = null, sdk = 34)
        assertTrue(hopped.passthroughOff)
        assertTrue("the setting on never turns a hop back", hopped.allowingPassthrough(true).passthroughOff)
        assertTrue(hopped.describe().contains("passthrough off"))
        assertEquals("async_default+pic_hw+snd_hw+tun_auto+out_0", DecoderPolicy.Default.shortClass())
        assertEquals("async_default+pic_hw+snd_hw+tun_auto+out_0+pt_off", hopped.shortClass())
        assertEquals(hopped.shortClass(), com.tmplayer.data.StutterReport.policyOf(hopped.shortClass()))
    }
}
