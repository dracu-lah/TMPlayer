package com.tmplayer.data

import com.tmplayer.data.DecodeJudge.Verdict
import com.tmplayer.player.PlaybackWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HX2: one judgement for every file on every device, a copy choice that steers round the copies
 * this device cannot keep up with, and a watchdog that catches what the judgement missed.
 */
class DecodeJudgeTest {

    /** The 1 GB Mi TV: four Cortex-A53 cores at 1.4 GHz, a 32-bit system. */
    private val miTv = DecodeJudge.Cpu(cores = 4, maxGhz = 1.416f, is64Bit = false)

    /** A mid phone: eight cores, 2.4 GHz at the top, 64-bit. */
    private val phone = DecodeJudge.Cpu(cores = 8, maxGhz = 2.4f, is64Bit = true)

    private fun av1(depth: Int, w: Int, h: Int, fps: Float = 23.976f) = DecodeJudge.Picture(DecodeJudge.MIME_AV1, depth, w, h, fps)

    @Test
    fun `hardware wins over everything`() {
        assertEquals(Verdict.HARDWARE, DecodeJudge.judge(av1(10, 3840, 2160), hardware = true, cpu = miTv, remembered = false))
    }

    @Test
    fun `the Web-DL that lagged on the Mi TV is predicted too slow, and fits on a phone`() {
        val webDl = av1(10, 1920, 1080)
        assertEquals(Verdict.SOFTWARE_TOO_SLOW, DecodeJudge.judge(webDl, hardware = false, cpu = miTv))
        assertEquals(Verdict.SOFTWARE_FITS, DecodeJudge.judge(webDl, hardware = false, cpu = phone))
    }

    @Test
    fun `small pictures in software fit even on the stick`() {
        assertEquals(Verdict.SOFTWARE_FITS, DecodeJudge.judge(DecodeJudge.Picture(DecodeJudge.MIME_AVC, 8, 854, 480, 25f), false, miTv))
        assertEquals(Verdict.SOFTWARE_TOO_SLOW, DecodeJudge.judge(DecodeJudge.Picture(DecodeJudge.MIME_HEVC, 10, 1920, 1080, 24f), false, miTv))
    }

    @Test
    fun `what the device remembered beats the table`() {
        val webDl = av1(10, 1920, 1080)
        assertEquals(Verdict.SOFTWARE_FITS, DecodeJudge.judge(webDl, hardware = false, cpu = miTv, remembered = true))
        assertEquals(Verdict.SOFTWARE_TOO_SLOW, DecodeJudge.judge(webDl, hardware = false, cpu = phone, remembered = false))
    }

    @Test
    fun `nothing is assumed of an unknown codec or size`() {
        assertEquals(Verdict.UNKNOWN, DecodeJudge.judge(DecodeJudge.Picture("video/x-unknown", 8, 1920, 1080), false, miTv))
        assertEquals(Verdict.UNKNOWN, DecodeJudge.judge(DecodeJudge.Picture(DecodeJudge.MIME_AV1), false, miTv))
    }

    @Test
    fun `bit depth is read from every codecs string form`() {
        assertEquals(10, DecodeJudge.bitDepthOf("av01.0.08M.10"))
        assertEquals(8, DecodeJudge.bitDepthOf("av01.0.05M.08"))
        assertEquals(10, DecodeJudge.bitDepthOf("hvc1.2.4.L123.B0"))
        assertEquals(8, DecodeJudge.bitDepthOf("hev1.1.6.L120.90"))
        assertEquals(10, DecodeJudge.bitDepthOf("vp09.02.40.10"))
        assertEquals(10, DecodeJudge.bitDepthOf("avc1.6E0028"))
        assertEquals(8, DecodeJudge.bitDepthOf("avc1.640028"))
        assertNull(DecodeJudge.bitDepthOf(null))
        assertNull(DecodeJudge.bitDepthOf("mp4a.40.2"))
    }

    @Test
    fun `a copy's name says its codec, depth and size, or nothing`() {
        val webDl = DecodeJudge.pictureOfName("The.Film.2026.1080p.WEB-DL.AV1.10bit.mkv", 0, 0, 1080)!!
        assertEquals(DecodeJudge.MIME_AV1, webDl.mime)
        assertEquals(10, webDl.bitDepth)
        assertEquals(1920, webDl.width)
        assertEquals("AV1 10-bit", webDl.label)
        val rip = DecodeJudge.pictureOfName("The Film 2026 HQ HDRip - 720p - x264 - Hin + Mal.mkv", 1280, 720, 720)!!
        assertEquals(DecodeJudge.MIME_AVC, rip.mime)
        assertEquals(8, rip.bitDepth)
        val hdr = DecodeJudge.pictureOfName("Film.2160p.HDR.x265.mkv", 0, 0, 2160)!!
        assertEquals(10, hdr.bitDepth)
        assertNull(DecodeJudge.pictureOfName("The Film 2026 1080p.mkv", 1920, 1080, 1080))
    }

    @Test
    fun `memory keeps one result per kind, newest last, and stays small`() {
        val kind = av1(10, 1920, 1080)
        var memory = DecodeMemory.noted(emptyMap(), kind, keptUp = false)
        assertEquals("av1:10:1080:30", DecodeMemory.keyOf(kind))
        assertEquals(false, memory["av1:10:1080:30"])
        memory = DecodeMemory.noted(memory, kind, keptUp = true)
        assertEquals(1, memory.size)
        assertEquals(memory, DecodeMemory.decode(DecodeMemory.encode(memory)))
        // A 1920 by 800 scope film is the 1080p kind.
        assertEquals("av1:10:1080:30", DecodeMemory.keyOf(av1(10, 1920, 800)))
        var many = emptyMap<String, Boolean>()
        for (h in 1..100) many = DecodeMemory.noted(many, DecodeJudge.Picture("video/x$h", 8, 640, 360), true)
        assertEquals(DecodeMemory.MAX, many.size)
        assertTrue(DecodeMemory.decode("garbage;=1;a=2").isEmpty())
    }

    // ---- the copy choice --------------------------------------------------------------------

    private fun copy(message: Long, name: String) = MediaItem(
        chatId = 1, messageId = message, fileId = message.toInt(), title = name, sizeBytes = 2_000L shl 20,
        durationSec = 7_200, mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null,
        date = message.toInt(), fileName = name,
    )

    private val webDl = copy(20, "Paradise.2026.1080p.WEB-DL.AV1.mkv")
    private val rip = copy(10, "Paradise 2026 HQ HDRip - 720p - x264.mkv")
    private val slow: (MediaItem) -> Boolean = { it.id == webDl.id }

    @Test
    fun `the smooth copy plays without asking, and the heavy one is named`() {
        // Without the judgement the newest, the Web-DL, would play.
        assertEquals(webDl.id, VideoCopies.pick(listOf(rip, webDl))!!.id)
        assertEquals(rip.id, VideoCopies.pick(listOf(rip, webDl), tooSlow = slow)!!.id)
        assertEquals(webDl.id, VideoCopies.passedOver(listOf(rip, webDl), tooSlow = slow)!!.id)
    }

    @Test
    fun `the viewer's own pick is kept, heavy or not`() {
        assertEquals(webDl.id, VideoCopies.pick(listOf(rip, webDl), chosen = webDl.id, tooSlow = slow)!!.id)
        assertNull(VideoCopies.passedOver(listOf(rip, webDl), chosen = webDl.id, tooSlow = slow))
    }

    @Test
    fun `with only heavy copies the choice is as before`() {
        assertEquals(webDl.id, VideoCopies.pick(listOf(rip, webDl), tooSlow = { true })!!.id)
        assertNull(VideoCopies.passedOver(listOf(rip, webDl), tooSlow = { true }))
        assertNull(VideoCopies.passedOver(listOf(webDl), tooSlow = slow))
    }

    // ---- the watchdog -----------------------------------------------------------------------

    private fun run(watch: PlaybackWatch, seconds: Int, speed: Double, dropsPerSecond: Int = 0, underrunEvery: Int = 0): PlaybackWatch.Answer? {
        var answer: PlaybackWatch.Answer? = null
        for (s in 0..seconds) {
            answer = watch.sample(
                wallMs = s * 1000L,
                positionMs = (s * 1000L * speed).toLong(),
                playing = true,
                renderedTotal = s * 24,
                droppedTotal = s * dropsPerSecond,
                underrunTotal = if (underrunEvery > 0) s / underrunEvery else 0,
            ) ?: answer
            if (watch.done) break
        }
        return answer
    }

    @Test
    fun `a smooth play is kept up, once, and then the watch is done`() {
        val watch = PlaybackWatch()
        assertEquals(PlaybackWatch.Answer.KEPT_UP, run(watch, 20, speed = 1.0))
        assertTrue(watch.done)
        assertNull(watch.sample(30_000, 30_000, true, 0, 0, 0))
    }

    @Test
    fun `the Mi TV's Web-DL is caught, a third of real time with the sound running dry`() {
        assertEquals(PlaybackWatch.Answer.CANNOT_KEEP_UP, run(PlaybackWatch(), 20, speed = 0.36, underrunEvery = 2))
        assertEquals(PlaybackWatch.Answer.CANNOT_KEEP_UP, run(PlaybackWatch(), 20, speed = 1.0, dropsPerSecond = 6))
        assertEquals(PlaybackWatch.Answer.CANNOT_KEEP_UP, run(PlaybackWatch(), 20, speed = 1.0, underrunEvery = 3))
    }

    @Test
    fun `waiting for the stream is not the decoder's fault`() {
        val watch = PlaybackWatch()
        // Ten seconds stalled for bytes: not counted at all.
        for (s in 0..10) assertNull(watch.sample(s * 1000L, 0, playing = false, renderedTotal = 0, droppedTotal = 0, underrunTotal = 0))
        assertFalse(watch.done)
        var answer: PlaybackWatch.Answer? = null
        for (s in 11..30) answer = watch.sample(s * 1000L, (s - 11) * 1000L, true, (s - 11) * 24, 0, 0) ?: answer
        assertEquals(PlaybackWatch.Answer.KEPT_UP, answer)
    }

    @Test
    fun `a decoder made again mid watch does not read as frames lost`() {
        val watch = PlaybackWatch()
        var answer: PlaybackWatch.Answer? = null
        for (s in 0..20) {
            // The frame counter starts again from nought at second 6.
            val rendered = if (s < 6) s * 24 else (s - 6) * 24
            answer = watch.sample(s * 1000L, s * 1000L, true, rendered, 0, 0) ?: answer
        }
        assertEquals(PlaybackWatch.Answer.KEPT_UP, answer)
    }

    @Test
    fun `a seek starts the count again, and late frames count`() {
        val watch = PlaybackWatch()
        for (s in 0..8) watch.sample(s * 1000L, s * 1000L, true, s * 24, 0, 0)
        watch.reset()
        // A jump of ten minutes between samples is not playback.
        assertNull(watch.sample(9_000, 600_000, true, 0, 0, 0))
        assertFalse(watch.done)
        val late = PlaybackWatch()
        late.frameOffsets(totalOffsetUs = -60_000L * 300, frames = 300)
        assertEquals(PlaybackWatch.Answer.CANNOT_KEEP_UP, run(late, 20, speed = 1.0))
    }

    @Test
    fun `the light decode is for software too slow here, and stays on once it kept up`() {
        val webDl = av1(10, 1920, 1080)
        // HX2c: the Mi TV's Web-DL gets it, and keeps it after a play in it kept up.
        assertTrue(DecodeJudge.wantsLightDecode(webDl, hardware = false, cpu = miTv, remembered = null))
        assertTrue(DecodeJudge.wantsLightDecode(webDl, hardware = false, cpu = miTv, remembered = true))
        // A phone that keeps up decodes as before, unless it did not keep up here.
        assertFalse(DecodeJudge.wantsLightDecode(webDl, hardware = false, cpu = phone, remembered = null))
        assertFalse(DecodeJudge.wantsLightDecode(webDl, hardware = false, cpu = phone, remembered = true))
        assertTrue(DecodeJudge.wantsLightDecode(webDl, hardware = false, cpu = phone, remembered = false))
        // Never with a hardware decoder for it, whatever was remembered.
        assertFalse(DecodeJudge.wantsLightDecode(webDl, hardware = true, cpu = miTv, remembered = false))
        // Not without a size or a budget to judge by.
        assertFalse(DecodeJudge.wantsLightDecode(DecodeJudge.Picture(DecodeJudge.MIME_AV1, 10), hardware = false, cpu = miTv, remembered = null))
        assertFalse(DecodeJudge.wantsLightDecode(DecodeJudge.Picture("video/mp4v-es", 8, 1920, 1080), hardware = false, cpu = miTv, remembered = null))
    }
}
