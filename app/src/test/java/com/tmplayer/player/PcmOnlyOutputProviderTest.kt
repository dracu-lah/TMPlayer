package com.tmplayer.player

import androidx.media3.common.Format
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
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

    private val aac: Format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setChannelCount(2).setSampleRate(48_000).build()
    private val trueHd: Format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_TRUEHD).setChannelCount(8).setSampleRate(48_000).build()

    @Test
    fun aDolbyOrDtsTrackNothingHereDecodesIsStillOfferedAsItIs() {
        // TrueHD on a stick with no decoder for it, the device's or FFmpeg's: refusing it would only be silence.
        val pcmOnly = PcmOnlyOutputProvider(TakesEverything()) { it.sampleMimeType != MimeTypes.AUDIO_TRUEHD }
        assertEquals(AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY, support(pcmOnly, trueHd))
        assertEquals(AudioOutputProvider.FORMAT_UNSUPPORTED, support(pcmOnly, ac3))
        // A decoder check that throws refuses nothing.
        assertEquals(AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY, support(PcmOnlyOutputProvider(TakesEverything()) { error("boom") }, ac3))
    }

    @Test
    fun aacAndOtherNonDolbyFormatsAreLeftToTheOutput() {
        val pcmOnly = PcmOnlyOutputProvider(TakesEverything()) { true }
        assertEquals(AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY, support(pcmOnly, aac))
        assertFalse(AudioPolicy.dolbyOrDts(aac))
        assertTrue(AudioPolicy.dolbyOrDts(trueHd))
        assertFalse(AudioPolicy.dolbyOrDts(null))
    }

    @Test
    fun aTvsStoredOffIsNotFollowedOnAPhoneOrTablet() {
        assertTrue(AudioPolicy.passthroughAllowed(stored = false, television = false))
        assertFalse(AudioPolicy.passthroughAllowed(stored = false, television = true))
        assertTrue(AudioPolicy.passthroughAllowed(stored = true, television = true))
        assertTrue(AudioPolicy.passthroughAllowed(stored = true, television = false))
    }

    private fun tracks(vararg groups: Tracks.Group) = Tracks(com.google.common.collect.ImmutableList.copyOf(groups))

    private fun soundGroup(format: Format, supported: Boolean, selected: Boolean) = Tracks.Group(
        TrackGroup(format),
        false,
        intArrayOf(if (supported) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_SUBTYPE),
        booleanArrayOf(selected),
    )

    private val joc: Format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3_JOC).setChannelCount(6).setSampleRate(48_000).build()
    private val dtsHd: Format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_DTS_HD).setChannelCount(8).setSampleRate(48_000).build()

    /** A device whose own decoders are [decoders], each type with the channels it takes. */
    private fun device(vararg decoders: Pair<String, Int>): (String) -> Int? = { type -> decoders.toMap()[type] }

    @Test
    fun atmosCountsAsDecodableOnAnEac3OnlyDeviceAsMediasRendererFallsBack() {
        val eac3Only = device(MimeTypes.AUDIO_E_AC3 to 8)
        assertTrue(AudioPolicy.deviceDecodes(joc, eac3Only))
        assertTrue(AudioPolicy.decodable(joc, eac3Only) { false })
    }

    @Test
    fun dtsHdCountsOnADtsOnlyDeviceAsMediasRendererPlaysItsCore() {
        val dtsOnly = device(MimeTypes.AUDIO_DTS to 8)
        assertTrue(AudioPolicy.deviceDecodes(dtsHd, dtsOnly))
        assertTrue(AudioPolicy.deviceDecodes(dts, dtsOnly))
        // An E-AC-3 decoder is no help for DTS-HD; FFmpeg's still makes it decodable.
        val eac3Only = device(MimeTypes.AUDIO_E_AC3 to 8)
        assertFalse(AudioPolicy.deviceDecodes(dtsHd, eac3Only))
        assertFalse(AudioPolicy.decodable(dtsHd, eac3Only) { false })
        assertTrue(AudioPolicy.decodable(dtsHd, eac3Only) { it == MimeTypes.AUDIO_DTS_HD })
    }

    @Test
    fun noDecoderTooFewChannelsAndAFailingLookupAllCountAsNot() {
        assertFalse(AudioPolicy.deviceDecodes(trueHd, device()))
        assertFalse(AudioPolicy.decodable(trueHd, device()) { false })
        // A stereo-only E-AC-3 decoder for a 5.1 track.
        assertFalse(AudioPolicy.deviceDecodes(EAC3, device(MimeTypes.AUDIO_E_AC3 to 2)))
        assertTrue(AudioPolicy.deviceDecodes(EAC3, device(MimeTypes.AUDIO_E_AC3 to 6)))
        assertFalse(AudioPolicy.deviceDecodes(EAC3) { error("query failed") })
        assertFalse(AudioPolicy.deviceDecodes(null, device(MimeTypes.AUDIO_E_AC3 to 8)))
        assertFalse(AudioPolicy.deviceDecodes(PCM.buildUpon().setSampleMimeType("video/avc").build(), device("video/avc" to 8)))
    }

    @Test
    fun theNoSoundLineIsSaidOncePerVideoAndNotOnAnotherEngine() {
        val silent = tracks(soundGroup(trueHd, supported = false, selected = false))
        val playing = tracks(soundGroup(ac3, supported = true, selected = true))
        val notice = SoundNotice()
        assertFalse("sound that plays does not use the line up", notice.shouldSay(playing, otherEngine = false))
        assertFalse("not on VLC", notice.shouldSay(silent, otherEngine = true))
        assertTrue(notice.shouldSay(silent, otherEngine = false))
        assertFalse("once per video", notice.shouldSay(silent, otherEngine = false))
        notice.newVideo()
        assertTrue(notice.shouldSay(silent, otherEngine = false))
    }

    @Test
    fun soundThatCannotPlayIsSaidAndSoundThatCanIsNot() {
        assertTrue(AudioPolicy.soundUnplayable(tracks(soundGroup(trueHd, supported = false, selected = false))))
        assertFalse(AudioPolicy.soundUnplayable(tracks(soundGroup(trueHd, supported = false, selected = false), soundGroup(ac3, supported = true, selected = true))))
        assertFalse(AudioPolicy.soundUnplayable(tracks(soundGroup(ac3, supported = true, selected = false))))
        // A video with no sound at all is not silent by fault.
        assertFalse(AudioPolicy.soundUnplayable(Tracks.EMPTY))
    }

    @Test
    fun bitstreamsAreRefusedAndPcmGoesThrough() {
        val inner = TakesEverything()
        val pcmOnly = PcmOnlyOutputProvider(inner) { true }
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
        val pcmOnly = DefaultAudioSink.Builder(context).setAudioOutputProvider(PcmOnlyOutputProvider(TakesEverything()) { true }).build()
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
