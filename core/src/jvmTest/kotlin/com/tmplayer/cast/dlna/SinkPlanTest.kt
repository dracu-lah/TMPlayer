package com.tmplayer.cast.dlna

import com.tmplayer.cast.AudioTrack
import com.tmplayer.cast.CastRoute
import com.tmplayer.cast.Codecs
import com.tmplayer.cast.Container
import com.tmplayer.cast.Ffmpeg
import com.tmplayer.cast.MediaProbe
import com.tmplayer.cast.TranscodeAbility
import com.tmplayer.cast.TranscodeContainer
import com.tmplayer.cast.VideoTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** What a DLNA TV's own Sink list does to the plan: fragmented MP4 or MPEG-TS for a transcode, by what the TV says it plays. */
class SinkPlanTest {

    private val tsOnly = "http-get:*:video/mpeg:DLNA.ORG_PN=MPEG_TS_SD_EU,http-get:*:video/vnd.dlna.mpeg-tts:DLNA.ORG_PN=AVC_TS_HD_50_AC3_ISO,http-get:*:audio/mpeg:*"
    private val mp4Only = "http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_MP_SD_AAC_MULT5,http-get:*:audio/mpeg:*"
    private val both = "$mp4Only,$tsOnly"

    /** An AVI nothing here rewraps: the transcode passes the H.264 picture through. */
    private val avi = MediaProbe(
        Container.Avi, 60_000,
        VideoTrack(1, Codecs.H264, 1280, 720),
        listOf(AudioTrack(2, Codecs.AAC, channels = 2, isDefault = true)),
    )

    private val desktop = Ffmpeg.ability(setOf("libx264", "aac"))!!

    /** A phone: Media3's muxer writes fragmented MP4 and nothing else. */
    private val phone = TranscodeAbility(
        videoDecoders = setOf(Codecs.H264, Codecs.MPEG4),
        audioDecoders = setOf(Codecs.AAC),
        containers = setOf(TranscodeContainer.Fmp4),
    )

    private fun container(sink: String?, ability: TranscodeAbility = desktop, probe: MediaProbe = avi): TranscodeContainer? =
        SinkCaps.parse(sink).plan(probe, transcoder = ability).routes.filterIsInstance<CastRoute.Transcode>().firstOrNull()?.target?.container

    @Test
    fun `a TV that lists TS and not MP4 gets MPEG-TS`() {
        assertEquals(TranscodeContainer.MpegTs, container(tsOnly))
        val route = SinkCaps.parse(tsOnly).plan(avi, transcoder = desktop).routes.single() as CastRoute.Transcode
        assertEquals("video/mp2t", route.target.mime)
    }

    @Test
    fun `a TV that lists MP4 gets fragmented MP4`() {
        assertEquals(TranscodeContainer.Fmp4, container(mp4Only))
        assertEquals("video/mp4", (SinkCaps.parse(mp4Only).plan(avi, transcoder = desktop).routes.single() as CastRoute.Transcode).target.mime)
    }

    @Test
    fun `a TV that lists both gets fragmented MP4, the one most TVs take`() {
        assertEquals(TranscodeContainer.Fmp4, container(both))
    }

    @Test
    fun `a missing or empty list gets the safe set, fragmented MP4`() {
        for (sink in listOf(null, "", "  ", "garbage", ",,,")) assertEquals("$sink", TranscodeContainer.Fmp4, container(sink))
    }

    @Test
    fun `a list that names neither wrapper keeps fragmented MP4`() {
        assertEquals(TranscodeContainer.Fmp4, container("http-get:*:video/x-matroska:*,http-get:*:video/webm:*"))
    }

    @Test
    fun `a phone that cannot write TS keeps fragmented MP4 even for a TS only TV`() {
        assertEquals(TranscodeContainer.Fmp4, container(tsOnly, phone))
    }

    @Test
    fun `a plan with no wrapper for the file is refused, not sent as HLS`() {
        val mkvLike = MediaProbe(Container.Matroska, 60_000, VideoTrack(1, Codecs.H264, 1280, 720), listOf(AudioTrack(2, Codecs.AAC, channels = 2)))
        val plan = SinkCaps.parse(mp4Only).plan(mkvLike)
        assertTrue(plan.routes.none { it is CastRoute.Hls })
    }

    @Test
    fun `the captured renderers`() {
        for ((renderer, expected) in listOf("rygel" to TranscodeContainer.Fmp4, "gmrender" to TranscodeContainer.Fmp4)) {
            val sink = (DlnaFixtures.soap(renderer, "GetProtocolInfo") as SoapResult.Ok)["Sink"]
            assertEquals(renderer, expected, container(sink))
        }
    }

    // ---- hostile or cut-short answers -----------------------------------------------------------

    @Test
    fun `a GetProtocolInfo cut at any point still plans`() {
        val sink = (DlnaFixtures.soap("gmrender", "GetProtocolInfo") as SoapResult.Ok)["Sink"]!!
        for (cut in 0..sink.length step 5) {
            val plan = SinkCaps.parse(sink.take(cut)).plan(avi, transcoder = desktop)
            val route = plan.routes.filterIsInstance<CastRoute.Transcode>().firstOrNull() ?: continue
            assertTrue("cut at $cut: ${route.target.container}", route.target.container == TranscodeContainer.Fmp4 || route.target.container == TranscodeContainer.MpegTs)
        }
    }

    @Test
    fun `a hostile answer never throws and always plans one of the two wrappers`() {
        val rnd = Random(11)
        val pieces = listOf("http-get", "*", "video/mpeg", "video/mp4", ":", ",", "DLNA.ORG_PN=", "AVC_TS_HD", "\u0000", "\uD800", "😀", "http-get:*:", "::::", "video/", ";", "\n")
        val inputs = buildList {
            add("x".repeat(500_000))
            add("http-get:*:video/mp4:*,".repeat(50_000))
            add(",".repeat(200_000))
            add("http-get:*:" + "v".repeat(100_000) + ":*")
            add("http-get:*:video/mpeg:DLNA.ORG_PN=" + "A".repeat(100_000))
            repeat(300) { add(buildString { repeat(rnd.nextInt(0, 40)) { append(pieces[rnd.nextInt(pieces.size)]) } }) }
        }
        for (text in inputs) {
            val plan = SinkCaps.parse(text).plan(avi, transcoder = desktop)
            for (route in plan.routes.filterIsInstance<CastRoute.Transcode>()) {
                assertTrue(route.target.mime == "video/mp4" || route.target.mime == "video/mp2t")
            }
        }
    }
}
