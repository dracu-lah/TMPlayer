package com.tmplayer.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The desktop transcode's command line and its output read back: no ffmpeg needed. */
class FfmpegTest {

    private val encodersOutput = """
        Encoders:
         V..... = Video
         A..... = Audio
         ------
         V....D h264_nvenc           NVIDIA NVENC H.264 encoder (codec h264)
         V....D libx264              libx264 H.264 / AVC / MPEG-4 AVC / MPEG-4 part 10 (codec h264)
         V....D h264_qsv             H.264 / AVC / MPEG-4 AVC / MPEG-4 part 10 (Intel Quick Sync Video acceleration) (codec h264)
         A....D aac                  AAC (Advanced Audio Coding)
         A....D ac3                  ATSC A/52A (AC-3)
         S..... srt                  SubRip subtitle
    """.trimIndent()

    private val source = object : CastSource {
        override val length = 0L
        override fun open(position: Long): CastReader = throw IOException()
    }

    private fun job(route: CastRoute.Transcode, startMs: Long = 0, audio: List<AudioTrack> = listOf(AudioTrack(3, Codecs.DTS), AudioTrack(5, Codecs.AC3))) =
        TranscodeJob(source, MediaProbe(Container.Matroska, 60_000, VideoTrack(1, Codecs.HEVC, 3840, 2160), audio), route, startMs, "v.mkv")

    private fun target(copy: Boolean = false, audio: TranscodeAudio = TranscodeAudio.Aac, container: TranscodeContainer = TranscodeContainer.Fmp4, toneMap: Boolean = false) =
        TranscodeTarget(copy, 1920, 1080, 8_000, 60, audio, 192, 2, container, toneMap)

    @Test
    fun `encoders are read from the listing`() {
        val e = Ffmpeg.encoders(encodersOutput)
        assertEquals(setOf("h264_nvenc", "libx264", "h264_qsv", "aac", "ac3", "srt"), e)
        assertEquals(listOf("h264_nvenc", "h264_qsv", "libx264"), Ffmpeg.h264Order(e))
    }

    @Test
    fun `the ability needs an h264 and an aac encoder`() {
        val a = Ffmpeg.ability(Ffmpeg.encoders(encodersOutput))!!
        assertTrue(a.hardware)
        assertTrue(a.ac3)
        assertNull(a.videoDecoders)
        assertTrue(TranscodeContainer.MpegTs in a.containers)
        assertNull(Ffmpeg.ability(setOf("aac")))
        assertNull(Ffmpeg.ability(setOf("libx264")))
        assertFalse(Ffmpeg.ability(setOf("libx264", "aac"))!!.hardware)
    }

    @Test
    fun `the command transcodes to fragmented mp4 on standard output`() {
        val a = Ffmpeg.args("ffmpeg", "http://127.0.0.1:1/t/v", job(CastRoute.Transcode(5, TranscodeReason.VideoCodec, target()), startMs = 61_005), "libx264")
        val line = a.joinToString(" ")
        assertEquals("ffmpeg", a.first())
        assertEquals("pipe:1", a.last())
        assertTrue(line, "-ss 61.005 -i http://127.0.0.1:1/t/v" in line)
        // Track 5 is the second soundtrack: ffmpeg counts audio streams from 0.
        assertTrue(line, "-map 0:v:0? -map 0:a:1?" in line)
        assertTrue(line, "-c:v libx264 -preset veryfast -profile:v high -level 4.1" in line)
        assertTrue(line, "-b:v 8000k -maxrate 12000k -bufsize 16000k" in line)
        // A software encoder is held to 30 fps whatever the target says, a keyframe every 2 s.
        assertTrue(line, "-fpsmax 30" in line && "-g 60" in line)
        assertTrue(line, "scale=1920:1080:force_original_aspect_ratio=decrease:force_divisible_by=2,format=yuv420p" in line)
        assertTrue(line, "-c:a aac -b:a 192k -ac 2" in line)
        assertTrue(line, "-f mp4 -movflags frag_keyframe+empty_moov+default_base_moof" in line)
        assertTrue(line, "-progress pipe:2" in line && "-nostdin" in line)
    }

    @Test
    fun `a hardware encoder keeps 60 fps and has its own options`() {
        val line = Ffmpeg.args("ffmpeg", "u", job(CastRoute.Transcode(3, TranscodeReason.VideoCodec, target())), "h264_nvenc").joinToString(" ")
        assertTrue(line, "-fpsmax 60" in line && "-g 120" in line)
        assertTrue(line, "-c:v h264_nvenc -preset p4" in line)
        assertFalse(line, "-ss" in line)
    }

    @Test
    fun `the picture passed through, the sound copied or dropped, mpeg-ts, tone mapping`() {
        val copy = Ffmpeg.args("ffmpeg", "u", job(CastRoute.Transcode(3, TranscodeReason.AudioCodec, target(copy = true, audio = TranscodeAudio.Ac3))), "libx264").joinToString(" ")
        assertTrue(copy, "-c:v copy" in copy && "-vf" !in copy && "-b:v" !in copy)
        assertTrue(copy, "-map 0:a:0? " in copy && "-c:a ac3 -b:a 192k -ac 6" in copy)
        val silent = Ffmpeg.args("ffmpeg", "u", job(CastRoute.Transcode(null, TranscodeReason.VideoCodec, target(audio = TranscodeAudio.None))), "libx264").joinToString(" ")
        assertTrue(silent, " -an " in silent && "0:a" !in silent && "-c:a" !in silent)
        val passed = Ffmpeg.args("ffmpeg", "u", job(CastRoute.Transcode(3, TranscodeReason.VideoCodec, target(audio = TranscodeAudio.Copy))), "libx264").joinToString(" ")
        assertTrue(passed, "-c:a copy" in passed)
        val ts = Ffmpeg.args("ffmpeg", "u", job(CastRoute.Transcode(3, TranscodeReason.Container, target(container = TranscodeContainer.MpegTs))), "libx264").joinToString(" ")
        assertTrue(ts, "-f mpegts pipe:1" in ts && "movflags" !in ts)
        val hdr = job(CastRoute.Transcode(3, TranscodeReason.TenBit, target(toneMap = true)))
        assertTrue("tonemap=hable" in Ffmpeg.args("ffmpeg", "u", hdr, "libx264").joinToString(" "))
        assertFalse("tonemap" in Ffmpeg.args("ffmpeg", "u", hdr, "libx264", toneMap = false).joinToString(" "))
    }

    @Test
    fun `an unknown soundtrack number falls back to the first`() {
        val line = Ffmpeg.args("ffmpeg", "u", job(CastRoute.Transcode(9, TranscodeReason.Container, target()), audio = emptyList()), "libx264").joinToString(" ")
        assertTrue(line, "-map 0:a:0?" in line)
    }

    @Test
    fun `progress blocks are read and errors kept`() {
        val p = FfmpegProgress()
        val lines = """
            frame=120
            fps=29.97
            stream_0_0_q=28.0
            bitrate=4000.0kbits/s
            total_size=1048576
            out_time_us=4004000
            out_time_ms=4004000
            out_time=00:00:04.004000
            dup_frames=0
            drop_frames=0
            speed=1.53x
            progress=continue
            [h264_nvenc @ 0x55] No capable devices found
            out_time_us=N/A
            speed=N/A
            progress=end
        """.trimIndent().lines()
        val updates = lines.mapNotNull(p::feed)
        assertEquals(2, updates.size)
        assertEquals(FfmpegProgress.Update(4_004, 1.53f, end = false), updates[0])
        assertEquals(4_004L, updates[1].outMs)
        assertTrue(updates[1].end)
        assertEquals("[h264_nvenc @ 0x55] No capable devices found", p.lastError)
    }
}
