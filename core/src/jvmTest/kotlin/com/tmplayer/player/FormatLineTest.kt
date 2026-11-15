package com.tmplayer.player

import com.tmplayer.player.FormatLine.Facts
import com.tmplayer.player.FormatLine.Transfer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** EG-F37: the one line the error sheet says about a file that would not play. */
class FormatLineTest {

    @Test
    fun `the example from the plan`() {
        val line = FormatLine.line(
            Facts(
                videoMime = "video/hevc", videoCodecs = "hvc1.2.4.L150.B0", width = 3840, height = 2160,
                audioMime = "audio/eac3", channels = 6,
            ),
        )
        assertEquals("HEVC Main 10 L5.0, 3840 x 2160, E-AC-3 5.1", line)
    }

    @Test
    fun `a full line with rate, HDR, container and the notes`() {
        val line = FormatLine.line(
            Facts(
                videoMime = "video/hevc", videoCodecs = "hev1.2.4.L153.90", width = 3840, height = 2160, frameRate = 23.976f,
                transfer = Transfer.Hdr10, audioMime = "audio/true-hd", channels = 8, containerMime = "video/x-matroska",
            ),
            extras = listOf("read by FFmpeg", "software decoder"),
        )
        assertEquals("HEVC Main 10 L5.1, 3840 x 2160, 23.976 fps, HDR10, TrueHD 7.1, Matroska, read by FFmpeg, software decoder", line)
    }

    @Test
    fun `real codec strings`() {
        assertEquals("H.264 High L4.0", FormatLine.videoName("video/avc", "avc1.640028"))
        assertEquals("H.264 Main L3.1", FormatLine.videoName("video/avc", "avc1.4D401F"))
        assertEquals("H.264 High 10 L5.1", FormatLine.videoName("video/avc", "avc1.6E0033"))
        assertEquals("HEVC Main L4.0", FormatLine.videoName("video/hevc", "hvc1.1.6.L120.90"))
        assertEquals("AV1 Main L4.0 10-bit", FormatLine.videoName("video/av01", "av01.0.08M.10"))
        assertEquals("VP9 Profile 2 L1.0", FormatLine.videoName("video/x-vnd.on2.vp9", "vp09.02.10.10"))
        assertEquals("Dolby Vision profile 5", FormatLine.videoName("video/dolby-vision", "dvhe.05.06"))
        assertEquals("Dolby Vision profile 8", FormatLine.videoName("video/dolby-vision", "dvh1.08.06"))
        assertEquals("MPEG-2", FormatLine.videoName("video/mpeg2", null))
        assertEquals("ProRes", FormatLine.videoName("video/x-ffmpeg-prores", null))
        assertEquals("AAC LC", FormatLine.audioName("audio/mp4a-latm", "mp4a.40.2"))
        assertEquals("HE-AAC", FormatLine.audioName("audio/mp4a-latm", "mp4a.40.5"))
        assertEquals("AC-3", FormatLine.audioName("audio/ac3", null))
        assertEquals("E-AC-3 JOC (Atmos)", FormatLine.audioName("audio/eac3-joc", null))
        assertEquals("DTS-HD", FormatLine.audioName("audio/vnd.dts.hd", null))
        assertEquals("Opus", FormatLine.audioName("audio/opus", null))
        assertEquals("MP3", FormatLine.audioName("audio/mpeg", null))
        assertEquals("AAC LC", FormatLine.audioName(null, "mp4a.40.2"))
    }

    @Test
    fun `codec strings are read from the codecs field alone when the mime says nothing`() {
        assertEquals("HEVC Main 10 L4.0", FormatLine.videoName(null, "hvc1.2.4.L120.B0"))
        assertEquals("H.264 High L4.0", FormatLine.videoName("", "avc1.640028"))
    }

    @Test
    fun `no video track and an audio only file`() {
        assertEquals("FLAC stereo, Ogg", FormatLine.line(Facts(audioMime = "audio/flac", channels = 2, containerMime = "audio/ogg")))
        // A size or rate without a picture is dropped, not printed on its own.
        assertEquals("AAC LC stereo", FormatLine.line(Facts(audioMime = "audio/mp4a-latm", audioCodecs = "mp4a.40.2", channels = 2, width = 1920, height = 1080, frameRate = 24f)))
    }

    @Test
    fun `an error before any format is known gives no line`() {
        assertEquals("", FormatLine.line(Facts()))
        assertEquals("", FormatLine.line(Facts(videoMime = "", audioMime = "  ", containerMime = "\n")))
        assertEquals("", FormatLine.line(Facts(width = 1920, height = 1080, channels = 6)))
    }

    @Test
    fun `unknown formats keep a short readable name`() {
        assertEquals("cinepak, 640 x 480", FormatLine.line(Facts(videoMime = "video/x-ffmpeg-cinepak", width = 640, height = 480)))
        assertEquals("weird", FormatLine.videoName("something/weird", null))
        assertEquals("MPEG-2, 720 x 576", FormatLine.line(Facts(videoMime = "video/mpeg2", width = 720, height = 576)))
    }

    @Test
    fun `a very long codec string is cut`() {
        val long = "hvc1." + "9".repeat(2_048)
        val name = FormatLine.videoName("video/hevc", long)
        assertNotNull(name)
        assertTrue(name.length < 80, name)
        val weird = FormatLine.line(Facts(videoMime = "video/" + "q".repeat(2_048), audioMime = "audio/" + "z".repeat(2_048), containerMime = "video/" + "c".repeat(2_048)))
        assertTrue(weird.length < 3 * (FormatLine.MAX_PART + 10), weird.length.toString())
        assertFalse(weird.contains('\n'))
    }

    @Test
    fun `absurd numbers are left out`() {
        assertEquals("H.264", FormatLine.line(Facts(videoMime = "video/avc", width = -1, height = 99_999_999, frameRate = Float.NaN, channels = -4)))
        assertEquals("H.264", FormatLine.line(Facts(videoMime = "video/avc", width = 0, height = 1080, frameRate = 1e9f)))
        assertEquals("H.264, 100 x 100", FormatLine.line(Facts(videoMime = "video/avc", width = 100, height = 100, frameRate = 0.5f)))
        assertEquals("Opus", FormatLine.line(Facts(audioMime = "audio/opus", channels = 40)))
        assertEquals("Opus 12 ch", FormatLine.line(Facts(audioMime = "audio/opus", channels = 12)))
    }

    @Test
    fun `frame rates read plainly`() {
        fun fps(value: Float) = FormatLine.line(Facts(videoMime = "video/avc", frameRate = value))
        assertEquals("H.264, 24 fps", fps(24f))
        assertEquals("H.264, 23.976 fps", fps(23.976f))
        assertEquals("H.264, 29.97 fps", fps(29.97f))
        assertEquals("H.264, 59.94 fps", fps(59.94f))
        assertEquals("H.264, 60 fps", fps(60f))
    }

    @Test
    fun `HLG and an unknown transfer`() {
        assertEquals("HEVC, HLG", FormatLine.line(Facts(videoMime = "video/hevc", transfer = Transfer.Hlg)))
        assertEquals("HEVC", FormatLine.line(Facts(videoMime = "video/hevc", transfer = Transfer.Sdr)))
        assertEquals("HEVC", FormatLine.line(Facts(videoMime = "video/hevc", transfer = Transfer.Unknown)))
    }

    @Test
    fun `the clipboard block names the decoders and nothing else`() {
        val block = FormatLine.copyBlock(
            "HEVC Main 10 L5.0, 3840 x 2160",
            listOf(
                FormatLine.Decoders("video/hevc", listOf("c2.mtk.hevc.decoder", "OMX.google.hevc.decoder", "c2.mtk.hevc.decoder")),
                FormatLine.Decoders("audio/eac3", emptyList()),
            ),
        )
        assertEquals(
            "Format: HEVC Main 10 L5.0, 3840 x 2160\nDecoders video/hevc: c2.mtk.hevc.decoder, OMX.google.hevc.decoder\nDecoders audio/eac3: none",
            block,
        )
        assertEquals("", FormatLine.copyBlock("", emptyList()))
    }

    @Test
    fun `garbage in never throws and never breaks the line`() {
        val random = Random(37)
        val texts = listOf(
            null, "", " ", "\u0000", "\n\t", "a".repeat(2_048), "😀".repeat(900), "hvc1.", "hvc1..", "hvc1.x.y.Lz", "avc1.", "avc1.zzzzzz",
            "av01", "av01.9.99Z.99", "vp09", "vp09.99.99", "dvhe.", "dvhe.xx", "video/", "/", "audio//", "mp4a.40.", "mp4a.40.999999999999",
            "hvc1.2.4.L2147483647.B0", "hvc1.2.4.L-5.B0", "avc1.640000", "avc1.64FFFF", "￿\uD800",
        ) + List(200) { String(CharArray(random.nextInt(0, 80)) { random.nextInt(0, 0x3000).toChar() }) }
        for (a in texts) for (b in texts.take(30)) {
            val line = FormatLine.line(
                Facts(
                    videoMime = a, videoCodecs = b, width = random.nextInt(-5, 30_000), height = random.nextInt(-5, 30_000),
                    frameRate = random.nextFloat() * 400f - 20f, audioMime = b, audioCodecs = a, channels = random.nextInt(-3, 50), containerMime = a,
                ),
                extras = listOf(a.orEmpty(), b.orEmpty()),
            )
            assertFalse(line.contains('\n'))
            assertTrue(line.length < 1_000, line.length.toString())
            FormatLine.copyBlock(line, listOf(FormatLine.Decoders(a.orEmpty(), listOfNotNull(b, a))))
        }
        assertNull(FormatLine.clean(null))
        assertNull(FormatLine.clean("   \n"))
    }
}
