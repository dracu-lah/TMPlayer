package com.tmplayer.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The Transcode route: planned only where remux cannot make the receiver play the file, and its target. */
class TranscodePlanTest {

    private fun video(codec: String, w: Int = 1920, h: Int = 1080, tenBit: Boolean = false, frameNs: Long = 0) =
        VideoTrack(1, codec, w, h, defaultDurationNs = frameNs, tenBit = tenBit)

    private fun audio(n: Int, codec: String, channels: Int = 2, default: Boolean = false) = AudioTrack(n, codec, channels = channels, isDefault = default)

    private val desktop = Ffmpeg.ability(setOf("libx264", "aac", "ac3"))!!
    private val phone = TranscodeAbility(
        videoDecoders = setOf(Codecs.H264, Codecs.HEVC, Codecs.MPEG4, Codecs.MPEG2, Codecs.VP9),
        audioDecoders = setOf(Codecs.AAC, Codecs.AC3, Codecs.MP3),
    )

    private fun plan(
        container: Container,
        v: VideoTrack?,
        a: List<AudioTrack>,
        caps: ReceiverCaps = ReceiverCaps.GOOGLE_TV,
        ability: TranscodeAbility? = desktop,
        convert: Boolean = false,
        link: Int? = null,
        sink: Set<Container>? = null,
    ) = CastFormats.plan(MediaProbe(container, 60_000, v, a), caps, null, convert, ability, link, sink)

    private fun CastPlan.transcode(): CastRoute.Transcode? = routes.filterIsInstance<CastRoute.Transcode>().firstOrNull()

    // ---- when -----------------------------------------------------------------------------------

    @Test
    fun `remux is enough for matroska with h264 and aac, no transcode`() {
        val p = plan(Container.Matroska, video(Codecs.H264), listOf(audio(2, Codecs.AAC)))
        assertNull(p.transcode())
        assertTrue(CastFormats.remuxEnough(MediaProbe(Container.Matroska, 0, video(Codecs.H264), listOf(audio(2, Codecs.AAC))), ReceiverCaps.GOOGLE_TV))
    }

    @Test
    fun `an mp4 that plays is sent as it is`() {
        val p = plan(Container.Mp4, video(Codecs.H264), listOf(audio(2, Codecs.AAC)), ReceiverCaps.LEGACY)
        assertEquals(listOf(CastRoute.Direct("video/mp4")), p.routes)
    }

    @Test
    fun `hevc on a first chromecast is transcoded for its codec`() {
        val t = plan(Container.Matroska, video(Codecs.HEVC), listOf(audio(2, Codecs.AAC)), ReceiverCaps.LEGACY).routes.single() as CastRoute.Transcode
        assertEquals(TranscodeReason.VideoCodec, t.reason)
        assertEquals(1920 to 1080, t.target.width to t.target.height)
        assertEquals(8_000, t.target.videoKbps)
        assertEquals(TranscodeAudio.Copy, t.target.audio)
    }

    @Test
    fun `ten bit on an eight bit receiver is transcoded and tone mapped`() {
        val caps = ReceiverCaps.GOOGLE_TV.copy(tenBit = Support.No)
        val t = plan(Container.Mp4, video(Codecs.HEVC, tenBit = true), listOf(audio(2, Codecs.AAC)), caps).routes.single() as CastRoute.Transcode
        assertEquals(TranscodeReason.TenBit, t.reason)
        assertTrue(t.target.toneMap)
    }

    @Test
    fun `4k on a 1080p stick is transcoded down for its size, the shape kept`() {
        val t = plan(Container.Matroska, video(Codecs.HEVC, 3832, 1600), listOf(audio(2, Codecs.AAC)), ReceiverCaps.FULL_HD_STICK).routes.single() as CastRoute.Transcode
        assertEquals(TranscodeReason.Resolution, t.reason)
        assertEquals(1920 to 802, t.target.width to t.target.height)
        assertEquals(8_000, t.target.videoKbps)
    }

    @Test
    fun `mpeg-ts and avi are transcoded where they were refused`() {
        val ts = plan(Container.MpegTs, null, emptyList())
        assertEquals(TranscodeReason.Container, (ts.routes.single() as CastRoute.Transcode).reason)
        assertFalse((ts.routes.single() as CastRoute.Transcode).target.copyVideo)
        assertEquals(CastRefusal.Container, plan(Container.Avi, null, emptyList(), ability = null).refusal)
        assertTrue(plan(Container.Avi, null, emptyList()).castable)
    }

    @Test
    fun `an mp4 with dts gets its sound made again, the picture passed through, the silent way behind it`() {
        val p = plan(Container.Mp4, video(Codecs.H264), listOf(audio(2, Codecs.DTS, channels = 6)), ReceiverCaps.LEGACY)
        val t = p.routes.first() as CastRoute.Transcode
        assertEquals(TranscodeReason.AudioCodec, t.reason)
        assertTrue(t.target.copyVideo)
        // A Chromecast passes Dolby on by default, and ffmpeg makes AC-3: 5.1 kept.
        assertEquals(TranscodeAudio.Ac3, t.target.audio)
        assertEquals(6, t.target.audioChannels)
        assertEquals(CastRoute.Direct("video/mp4"), p.routes[1])
        assertFalse(p.silent)
        // Without a transcoder it goes silent, as before.
        assertTrue(plan(Container.Mp4, video(Codecs.H264), listOf(audio(2, Codecs.DTS)), ReceiverCaps.LEGACY, ability = null).silent)
    }

    @Test
    fun `dts in matroska without a converter is transcoded ahead of the silent rewrap`() {
        val p = plan(Container.Matroska, video(Codecs.H264), listOf(audio(2, Codecs.DTS)), ReceiverCaps.LEGACY.copy(dolbyPassthrough = Support.No))
        val t = p.routes.first() as CastRoute.Transcode
        assertEquals(TranscodeAudio.Aac, t.target.audio)
        assertEquals(2, t.target.audioChannels)
        assertEquals(listOf(CastRoute.Hls(null, emptyList()), CastRoute.Remux(null)), p.routes.drop(1))
        assertFalse(p.silent)
    }

    @Test
    fun `dts in matroska with the phone's converter needs no transcode`() {
        val p = plan(Container.Matroska, video(Codecs.H264), listOf(audio(2, Codecs.DTS)), ReceiverCaps.LEGACY, ability = phone, convert = true)
        assertNull(p.transcode())
        assertTrue(p.routes.any { it is CastRoute.ConvertAudio })
    }

    @Test
    fun `a maybe picture is tried as it is first, the transcode behind`() {
        val p = plan(Container.Mp4, video(Codecs.HEVC), listOf(audio(2, Codecs.AAC)), ReceiverCaps.UNKNOWN)
        assertEquals(CastRoute.Direct("video/mp4"), p.routes.first())
        assertTrue(p.routes.last() is CastRoute.Transcode)
    }

    @Test
    fun `a phone that cannot decode the picture offers nothing`() {
        val p = plan(Container.Matroska, video(Codecs.VC1), listOf(audio(2, Codecs.AAC)), ReceiverCaps.LEGACY, ability = phone)
        assertFalse(p.castable)
        assertEquals(CastRefusal.VideoCodec, p.refusal)
    }

    @Test
    fun `a phone that cannot decode the sound sends the picture made again without it`() {
        val p = plan(Container.Mp4, video(Codecs.MPEG4, 720, 576), listOf(audio(2, Codecs.DTS)), ReceiverCaps.LEGACY, ability = phone)
        val t = p.routes.single() as CastRoute.Transcode
        assertEquals(TranscodeAudio.None, t.target.audio)
        assertNull(t.audioTrack)
        assertTrue(p.silent)
        // And it does not make one for the sound alone, which it could not decode.
        val mp4 = plan(Container.Mp4, video(Codecs.H264), listOf(audio(2, Codecs.DTS)), ReceiverCaps.LEGACY, ability = phone)
        assertEquals(listOf(CastRoute.Direct("video/mp4")), mp4.routes)
    }

    @Test
    fun `no route is ever a transcode without a transcoder`() {
        val random = Random(4)
        val codecs = listOf(Codecs.H264, Codecs.HEVC, Codecs.AV1, Codecs.VP9, Codecs.MPEG4, Codecs.MPEG2, Codecs.VC1, "other:x")
        val sounds = listOf(Codecs.AAC, Codecs.DTS, Codecs.TRUEHD, Codecs.AC3, Codecs.VORBIS, Codecs.PCM)
        val caps = listOf(ReceiverCaps.LEGACY, ReceiverCaps.ULTRA, ReceiverCaps.GOOGLE_TV, ReceiverCaps.UNKNOWN, ReceiverCaps.FULL_HD_STICK)
        repeat(500) {
            val v = video(codecs.random(random), random.nextInt(0, 5000), random.nextInt(0, 3000), random.nextBoolean())
            val a = List(random.nextInt(0, 3)) { audio(it + 2, sounds.random(random)) }
            val c = Container.entries.random(random)
            val p = plan(c, v, a, caps.random(random), ability = null, convert = random.nextBoolean())
            assertTrue(p.routes.none { it is CastRoute.Transcode })
        }
    }

    @Test
    fun `a muxer that cannot carry the sound or the picture as they are makes them again`() {
        val media3 = phone.copy(passVideo = setOf(Codecs.H264, Codecs.HEVC), passAudio = setOf(Codecs.AAC), audioDecoders = setOf(Codecs.AAC, Codecs.AC3, Codecs.DTS))
        // AC-3 plays on the receiver, but Media3's fMP4 muxer has no AC-3: AAC instead.
        val ac3 = plan(Container.Matroska, video(Codecs.MPEG2), listOf(audio(2, Codecs.AC3)), ReceiverCaps.LEGACY, ability = media3).transcode()!!
        assertEquals(TranscodeAudio.Aac, ac3.target.audio)
        // VP9 with DTS, the sound the trouble: the picture cannot pass through this muxer, so it is made again.
        val vp9 = plan(Container.Mp4, video(Codecs.VP9), listOf(audio(2, Codecs.DTS)), ReceiverCaps.GOOGLE_TV, ability = media3).routes.first() as CastRoute.Transcode
        assertEquals(TranscodeReason.AudioCodec, vp9.reason)
        assertFalse(vp9.target.copyVideo)
        val h264 = plan(Container.Mp4, video(Codecs.H264), listOf(audio(2, Codecs.DTS)), ReceiverCaps.GOOGLE_TV, ability = media3).routes.first() as CastRoute.Transcode
        assertTrue(h264.target.copyVideo)
    }

    // ---- what -----------------------------------------------------------------------------------

    @Test
    fun `a slow link steps the ladder down`() {
        val t = plan(Container.Mp4, video(Codecs.HEVC), listOf(audio(2, Codecs.AAC)), ReceiverCaps.LEGACY, link = 3_000).transcode()!!.target
        assertEquals(480, t.height)
        assertEquals(852, t.width)
        assertEquals(1_800, t.videoKbps)
    }

    @Test
    fun `a small source keeps its size and gets its own rung's rate`() {
        val t = plan(Container.Matroska, video(Codecs.MPEG4, 640, 360), emptyList(), ReceiverCaps.LEGACY).transcode()!!.target
        assertEquals(640 to 360, t.width to t.height)
        assertEquals(1_000, t.videoKbps)
        assertEquals(TranscodeAudio.None, t.audio)
    }

    @Test
    fun `a software encoder is held to 30 fps, a hardware one keeps the source's rate`() {
        val software = TranscodeAbility(hardware = false)
        val sixty = video(Codecs.HEVC, frameNs = 16_683_333)
        assertEquals(30, plan(Container.Mp4, sixty, emptyList(), ReceiverCaps.LEGACY, ability = software).transcode()!!.target.maxFps)
        assertEquals(60, plan(Container.Mp4, sixty, emptyList(), ReceiverCaps.LEGACY, ability = TranscodeAbility()).transcode()!!.target.maxFps)
        assertEquals(24, plan(Container.Mp4, video(Codecs.HEVC, frameNs = 41_708_333), emptyList(), ReceiverCaps.LEGACY, ability = TranscodeAbility()).transcode()!!.target.maxFps)
    }

    @Test
    fun `the encoder's own limits are kept`() {
        val small = TranscodeAbility(maxWidth = 1280, maxHeight = 720, maxVideoKbps = 3_000)
        val t = plan(Container.Mp4, video(Codecs.HEVC), emptyList(), ReceiverCaps.LEGACY, ability = small).transcode()!!.target
        assertEquals(1280 to 720, t.width to t.height)
        assertEquals(3_000, t.videoKbps)
    }

    @Test
    fun `a dlna tv that lists mpeg-ts alone gets mpeg-ts from ffmpeg, mp4 from a phone`() {
        val sink = setOf(Container.MpegTs)
        assertEquals(TranscodeContainer.MpegTs, plan(Container.Avi, null, emptyList(), sink = sink).transcode()!!.target.container)
        assertEquals(TranscodeContainer.Fmp4, plan(Container.Avi, null, emptyList(), ability = phone, sink = sink).transcode()!!.target.container)
        assertEquals(TranscodeContainer.Fmp4, plan(Container.Avi, null, emptyList(), sink = setOf(Container.Mp4, Container.MpegTs)).transcode()!!.target.container)
    }

    @Test
    fun `sound is aac stereo where dolby is not taken or the phone has no ac-3`() {
        val six = audio(2, Codecs.TRUEHD, channels = 8)
        val noDolby = ReceiverCaps.LEGACY.copy(dolbyPassthrough = Support.No)
        assertEquals(TranscodeAudio.Aac, TranscodePlanner.audioFor(six, noDolby, desktop))
        assertEquals(TranscodeAudio.Ac3, TranscodePlanner.audioFor(six, ReceiverCaps.LEGACY, desktop))
        assertEquals(TranscodeAudio.Aac, TranscodePlanner.audioFor(six, ReceiverCaps.LEGACY, TranscodeAbility()))
        assertEquals(TranscodeAudio.Copy, TranscodePlanner.audioFor(audio(2, Codecs.AAC), noDolby, desktop))
        // Vorbis plays on the receiver but has no place in MP4.
        assertEquals(TranscodeAudio.Aac, TranscodePlanner.audioFor(audio(2, Codecs.VORBIS), noDolby, desktop))
        assertEquals(TranscodeAudio.None, TranscodePlanner.audioFor(null, noDolby, desktop))
    }

    @Test
    fun `garbage sizes and rates never throw and stay in bounds`() {
        val random = Random(20261115)
        val odd = listOf(Int.MIN_VALUE, -1, 0, 1, 15, 17, 1921, 65_535, Int.MAX_VALUE)
        val oddNs = listOf(Long.MIN_VALUE, -1L, 0L, 1L, 1_000L, Long.MAX_VALUE)
        repeat(2_000) {
            val v = if (random.nextInt(8) == 0) null else VideoTrack(
                1, Codecs.HEVC,
                if (random.nextBoolean()) odd.random(random) else random.nextInt(),
                if (random.nextBoolean()) odd.random(random) else random.nextInt(),
                defaultDurationNs = if (random.nextBoolean()) oddNs.random(random) else random.nextLong(),
                tenBit = random.nextBoolean(),
            )
            val a = AudioTrack(2, Codecs.DTS, channels = odd.random(random))
            val link = if (random.nextBoolean()) null else odd.random(random)
            val ability = TranscodeAbility(maxWidth = odd.random(random), maxHeight = odd.random(random), maxVideoKbps = odd.random(random), hardware = random.nextBoolean())
            val t = TranscodePlanner.target(MediaProbe(Container.Mp4, 0, v, listOf(a)), ReceiverCaps.LEGACY, ability, a, false, link)
            assertTrue("$t", t.width in 16..1920 && t.height in 16..1080 && t.width % 2 == 0 && t.height % 2 == 0)
            assertTrue("$t", t.videoKbps in 500..8_000)
            assertTrue("$t", t.maxFps in 1..60)
            assertTrue("$t", t.audioChannels in 0..8)
        }
    }

    // ---- guard ----------------------------------------------------------------------------------

    @Test
    fun `the guard stops at the heat and battery limits`() {
        assertNull(TranscodeGuard.check(DeviceHealth()))
        assertNull(TranscodeGuard.check(DeviceHealth(50, false, DeviceHealth.Thermal.Moderate)))
        assertEquals(TranscodeStop.TooHot, TranscodeGuard.check(DeviceHealth(80, true, DeviceHealth.Thermal.Severe)))
        assertEquals(TranscodeStop.BatteryLow, TranscodeGuard.check(DeviceHealth(14, false)))
        assertNull(TranscodeGuard.check(DeviceHealth(14, true)))
        assertNull(TranscodeGuard.check(DeviceHealth(14, null).copy(charging = true)))
        assertEquals(TranscodeStop.BatteryLow, TranscodeGuard.check(DeviceHealth(5, null)))
        // A reading that makes no sense says nothing.
        assertNull(TranscodeGuard.check(DeviceHealth(-1, false)))
    }
}
