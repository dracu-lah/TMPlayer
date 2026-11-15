package com.tmplayer.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The codec decision table: what plays as it is, what is rewrapped, what is converted. */
class CastFormatsTest {

    private fun video(codec: String, tenBit: Boolean = false) = VideoTrack(1, codec, 1920, 1080, tenBit = tenBit)
    private fun audio(n: Int, codec: String, lang: String? = null, default: Boolean = false) = AudioTrack(n, codec, lang, isDefault = default)

    private fun plan(
        container: Container,
        v: VideoTrack?,
        a: List<AudioTrack>,
        caps: ReceiverCaps = ReceiverCaps.GOOGLE_TV,
        preferred: Int? = null,
        convert: Boolean = false,
        transcode: Boolean = false,
    ) = CastFormats.plan(MediaProbe(container, 0, v, a), caps, preferred, convert, if (transcode) TranscodeAbility() else null)

    @Test
    fun `mp4 with h264 and aac plays directly`() {
        val p = plan(Container.Mp4, video(Codecs.H264), listOf(audio(2, Codecs.AAC)), ReceiverCaps.LEGACY)
        assertEquals(listOf(CastRoute.Direct("video/mp4")), p.routes)
        assertFalse(p.silent)
    }

    @Test
    fun `webm plays directly`() {
        val p = plan(Container.WebM, video(Codecs.VP9), listOf(audio(2, Codecs.OPUS)))
        assertEquals(CastRoute.Direct("video/webm"), p.routes.first())
    }

    @Test
    fun `matroska that is webm in all but name plays as webm`() {
        val p = plan(Container.Matroska, video(Codecs.VP9), listOf(audio(2, Codecs.OPUS)))
        assertEquals(CastRoute.Direct("video/webm"), p.routes.first())
    }

    @Test
    fun `matroska with h264 and aac goes as hls, the rewrap behind it, on an older chromecast`() {
        val p = plan(Container.Matroska, video(Codecs.H264), listOf(audio(2, Codecs.AAC)), ReceiverCaps.LEGACY)
        assertEquals(listOf(CastRoute.Hls(2, listOf(HlsAudio(2, false))), CastRoute.Remux(2)), p.routes)
    }

    @Test
    fun `matroska goes as hls, then rewrapped, then tried as it is on a google tv`() {
        val p = plan(Container.Matroska, video(Codecs.H264), listOf(audio(2, Codecs.AAC)), ReceiverCaps.GOOGLE_TV)
        assertEquals(listOf(CastRoute.Hls(2, listOf(HlsAudio(2, false))), CastRoute.Remux(2), CastRoute.Direct("video/x-matroska")), p.routes)
    }

    @Test
    fun `dolby passes through in the rewrap`() {
        val p = plan(Container.Matroska, video(Codecs.HEVC), listOf(audio(2, Codecs.EAC3)), ReceiverCaps.ULTRA)
        assertEquals(CastRoute.Hls(2, listOf(HlsAudio(2, false))), p.routes.first())
        assertEquals(CastRoute.Remux(2), p.routes[1])
    }

    @Test
    fun `dts is converted to aac where the device can`() {
        val p = plan(Container.Matroska, video(Codecs.H264), listOf(audio(2, Codecs.DTS, default = true)), ReceiverCaps.LEGACY, convert = true)
        assertEquals(listOf(CastRoute.Hls(2, listOf(HlsAudio(2, true))), CastRoute.ConvertAudio(2)), p.routes)
    }

    @Test
    fun `dts without a converter picks another track that plays`() {
        val p = plan(
            Container.Matroska,
            video(Codecs.H264),
            listOf(audio(2, Codecs.DTS, "en", default = true), audio(3, Codecs.AC3, "en")),
            ReceiverCaps.LEGACY,
        )
        assertEquals(3, p.audioTrack)
        // The DTS track is left out of the renditions: nothing here turns it into AAC.
        assertEquals(listOf(CastRoute.Hls(3, listOf(HlsAudio(3, false))), CastRoute.Remux(3)), p.routes)
    }

    @Test
    fun `the viewer's track is kept and converted rather than swapped`() {
        val p = plan(
            Container.Matroska,
            video(Codecs.H264),
            listOf(audio(2, Codecs.AAC, "en", default = true), audio(3, Codecs.TRUEHD, "ja")),
            ReceiverCaps.LEGACY,
            preferred = 3,
            convert = true,
        )
        assertEquals(3, p.audioTrack)
        assertEquals(listOf(CastRoute.Hls(3, listOf(HlsAudio(2, false), HlsAudio(3, true))), CastRoute.ConvertAudio(3)), p.routes)
    }

    @Test
    fun `dts only with no converter sends the picture alone`() {
        val p = plan(Container.Matroska, video(Codecs.H264), listOf(audio(2, Codecs.DTS)), ReceiverCaps.LEGACY)
        assertEquals(listOf(CastRoute.Hls(null, emptyList()), CastRoute.Remux(null)), p.routes)
        assertTrue(p.silent)
    }

    @Test
    fun `hevc on a first chromecast needs the picture converted`() {
        val refused = plan(Container.Mp4, video(Codecs.HEVC), listOf(audio(2, Codecs.AAC)), ReceiverCaps.LEGACY)
        assertFalse(refused.castable)
        assertEquals(CastRefusal.VideoCodec, refused.refusal)
        val converted = plan(Container.Mp4, video(Codecs.HEVC), listOf(audio(2, Codecs.AAC)), ReceiverCaps.LEGACY, transcode = true)
        val only = converted.routes.single() as CastRoute.Transcode
        assertEquals(2, only.audioTrack)
        assertEquals(TranscodeReason.VideoCodec, only.reason)
        // AAC plays on the receiver: passed through, the picture made again.
        assertEquals(TranscodeAudio.Copy, only.target.audio)
        assertFalse(only.target.copyVideo)
        assertTrue(CastFormats.needsNote(converted.routes.first()))
    }

    @Test
    fun `hevc on an unnamed chromecast is tried and converted if it fails`() {
        val p = plan(Container.Mp4, video(Codecs.HEVC), listOf(audio(2, Codecs.AAC)), ReceiverCaps.UNKNOWN, transcode = true)
        assertEquals(CastRoute.Direct("video/mp4"), p.routes.first())
        assertEquals(2, p.routes.size)
        assertEquals(TranscodeReason.VideoCodec, (p.routes[1] as CastRoute.Transcode).reason)
    }

    @Test
    fun `ten bit hevc needs a receiver that shows ten bit`() {
        val ultra = plan(Container.Mp4, video(Codecs.HEVC, tenBit = true), listOf(audio(2, Codecs.AAC)), ReceiverCaps.ULTRA)
        assertEquals(CastRoute.Direct("video/mp4"), ultra.routes.first())
        val legacy = plan(Container.Mp4, video(Codecs.HEVC, tenBit = true), listOf(audio(2, Codecs.AAC)), ReceiverCaps.LEGACY)
        assertFalse(legacy.castable)
    }

    @Test
    fun `av1 only on the receivers that decode it`() {
        assertEquals(Support.Yes, CastFormats.videoSupport(video(Codecs.AV1), ReceiverCaps.STREAMER))
        assertEquals(Support.No, CastFormats.videoSupport(video(Codecs.AV1), ReceiverCaps.ULTRA))
        assertEquals(Support.Maybe, CastFormats.videoSupport(video(Codecs.AV1), ReceiverCaps.GOOGLE_TV))
    }

    @Test
    fun `mpeg4 part 2 and avi are refused without conversion`() {
        assertEquals(CastRefusal.VideoCodec, plan(Container.Matroska, video(Codecs.MPEG4), listOf(audio(2, Codecs.MP3))).refusal)
        assertEquals(CastRefusal.Container, plan(Container.Avi, null, emptyList()).refusal)
    }

    @Test
    fun `mp4 with a soundtrack the receiver cannot play still sends the picture, said so`() {
        val p = plan(Container.Mp4, video(Codecs.H264), listOf(audio(2, Codecs.DTS)), ReceiverCaps.LEGACY)
        assertEquals(listOf(CastRoute.Direct("video/mp4")), p.routes)
        assertTrue(p.silent)
    }

    @Test
    fun `audio support follows the cast documentation`() {
        for (codec in listOf(Codecs.AAC, Codecs.MP3, Codecs.OPUS, Codecs.VORBIS, Codecs.FLAC)) {
            assertEquals(codec, Support.Yes, CastFormats.audioSupport(codec, ReceiverCaps.LEGACY))
        }
        assertEquals(Support.Yes, CastFormats.audioSupport(Codecs.AC3, ReceiverCaps.LEGACY))
        assertEquals(Support.Yes, CastFormats.audioSupport(Codecs.EAC3, ReceiverCaps.LEGACY))
        for (codec in listOf(Codecs.DTS, Codecs.TRUEHD, Codecs.PCM, Codecs.ALAC)) {
            assertEquals(codec, Support.No, CastFormats.audioSupport(codec, ReceiverCaps.LEGACY))
        }
    }

    @Test
    fun `model names map to what the hardware decodes`() {
        assertEquals(ReceiverCaps.ULTRA, ReceiverCaps.forModel("Chromecast Ultra"))
        assertEquals(ReceiverCaps.STREAMER, ReceiverCaps.forModel("Google TV Streamer"))
        assertEquals(ReceiverCaps.GOOGLE_TV, ReceiverCaps.forModel("BRAVIA 4K VH2"))
        assertEquals(ReceiverCaps.UNKNOWN, ReceiverCaps.forModel("Chromecast"))
        assertEquals(ReceiverCaps.UNKNOWN, ReceiverCaps.forModel(null))
        assertEquals(ReceiverCaps.FULL_HD_STICK, ReceiverCaps.forModel("MiTV-AESP0"))
        assertEquals(ReceiverCaps.FULL_HD_STICK, ReceiverCaps.forModel("Chromecast HD"))
        assertEquals(Support.Maybe, ReceiverCaps.forModel("Some TV 55").uhd)
    }

    private val scope4k = VideoTrack(1, Codecs.HEVC, 3832, 1600)

    @Test
    fun `a 4k picture on a 1080p stick is refused for its size, not its codec`() {
        // The device test: a 3832x1600 HEVC Matroska on the Mi TV Stick, whose decoder would not open.
        val p = plan(Container.Matroska, scope4k, listOf(audio(2, Codecs.AAC)), ReceiverCaps.forModel("MiTV-AESP0"))
        assertFalse(p.castable)
        assertEquals(CastRefusal.Resolution, p.refusal)
        assertTrue(CastFormats.mayBeTooLarge(scope4k, ReceiverCaps.FULL_HD_STICK))
    }

    @Test
    fun `a 4k picture in a codec the stick lacks is refused for the codec`() {
        val p = plan(Container.Matroska, VideoTrack(1, Codecs.AV1, 3840, 2160), listOf(audio(2, Codecs.AAC)), ReceiverCaps.LEGACY)
        assertEquals(CastRefusal.VideoCodec, p.refusal)
    }

    @Test
    fun `a 4k picture plays on a 4k receiver and is tried on an unnamed one`() {
        assertTrue(plan(Container.Matroska, scope4k, listOf(audio(2, Codecs.AAC)), ReceiverCaps.GOOGLE_TV).castable)
        assertFalse(CastFormats.mayBeTooLarge(scope4k, ReceiverCaps.GOOGLE_TV))
        val unnamed = ReceiverCaps.forModel("Some TV 55")
        assertTrue(plan(Container.Matroska, scope4k, listOf(audio(2, Codecs.AAC)), unnamed).castable)
        assertTrue(CastFormats.mayBeTooLarge(scope4k, unnamed))
    }

    @Test
    fun `1080p is never held to the 4k limit`() {
        assertTrue(plan(Container.Matroska, video(Codecs.HEVC), listOf(audio(2, Codecs.AAC)), ReceiverCaps.FULL_HD_STICK).castable)
        assertFalse(CastFormats.mayBeTooLarge(video(Codecs.HEVC), ReceiverCaps.LEGACY))
        assertFalse(CastFormats.aboveFullHd(VideoTrack(1, Codecs.H264, 1920, 1088)))
    }

    @Test
    fun `hls offers every soundtrack the receiver takes, converting the rest where it can`() {
        val tracks = listOf(audio(1, Codecs.AAC), audio(2, Codecs.AC3), audio(3, Codecs.DTS), audio(4, Codecs.VORBIS), audio(5, Codecs.EAC3))
        assertEquals(
            // Dolby twice: as it is, and as AAC for a TV that turns it away (the Mi TV Stick did).
            listOf(HlsAudio(1, false), HlsAudio(2, false), HlsAudio(2, true), HlsAudio(3, true), HlsAudio(4, true), HlsAudio(5, false), HlsAudio(5, true)),
            CastFormats.hlsAudio(tracks, ReceiverCaps.GOOGLE_TV, canConvert = true),
        )
        assertEquals(listOf(HlsAudio(1, false), HlsAudio(2, false), HlsAudio(5, false)), CastFormats.hlsAudio(tracks, ReceiverCaps.GOOGLE_TV, canConvert = false))
        // No Dolby passthrough: AC-3 becomes AAC where it can, and goes otherwise.
        val noDolby = ReceiverCaps.GOOGLE_TV.copy(dolbyPassthrough = Support.No)
        assertEquals(listOf(HlsAudio(1, false), HlsAudio(2, true), HlsAudio(3, true), HlsAudio(4, true), HlsAudio(5, true)), CastFormats.hlsAudio(tracks, noDolby, canConvert = true))
        assertEquals(listOf(HlsAudio(1, false)), CastFormats.hlsAudio(tracks, noDolby, canConvert = false))
    }

    @Test
    fun `hls starts on the viewer's soundtrack`() {
        val p = plan(Container.Matroska, video(Codecs.HEVC), listOf(audio(2, Codecs.AC3, "en", default = true), audio(3, Codecs.AC3, "ja")), preferred = 3)
        assertEquals(CastRoute.Hls(3, listOf(HlsAudio(2, false), HlsAudio(3, false))), p.routes.first())
    }

    @Test
    fun `matroska with a picture mp4 cannot carry gets no hls`() {
        val p = plan(Container.Matroska, video(Codecs.VP9), listOf(audio(2, Codecs.AAC)), ReceiverCaps.GOOGLE_TV)
        assertTrue(p.routes.none { it is CastRoute.Hls })
    }
}
