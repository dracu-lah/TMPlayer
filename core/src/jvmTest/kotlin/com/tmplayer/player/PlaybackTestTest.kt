package com.tmplayer.player

import com.tmplayer.player.PlaybackTest.Fetch
import com.tmplayer.player.PlaybackTest.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Playback test (L6): what a fetch needs, and what a run's numbers are called. */
class PlaybackTestTest {

    private val mb = 1024L * 1024L

    private fun samples(dropped: Int, underruns: Int = 0, hardware: Boolean = true, cpu: Int = 20, buffer: Long = 8_000, thermal: Int = 0, readWait: Long = 0) =
        (0 until 6).map { i ->
            TraceSample(
                atMs = 1_000_000L + i * 5_000L, intervalMs = 5_000L,
                droppedFrames = if (i == 0) dropped else 0, underruns = if (i == 0) underruns else 0,
                bufferAheadMs = buffer, hardware = hardware, cpuPercent = cpu, thermal = thermal, readWaitMs = readWait,
            )
        }

    @Test
    fun `a window is 30 MB, or the whole of a smaller file`() {
        assertEquals(30 * mb, PlaybackTest.windowBytes(2_000 * mb))
        assertEquals(5 * mb, PlaybackTest.windowBytes(5 * mb))
        assertEquals(30 * mb, PlaybackTest.windowBytes(0))
    }

    @Test
    fun `a fetch is planned from what is on the disk and what is free`() {
        assertEquals(Fetch.NotNeeded, PlaybackTest.fetchPlan(1_000 * mb, 30 * mb, 10 * mb))
        assertEquals(Fetch.NotNeeded, PlaybackTest.fetchPlan(1_000 * mb, 99 * mb, 0))
        assertEquals(Fetch.Needed(30 * mb, 1_000 * mb), PlaybackTest.fetchPlan(1_000 * mb, 0, 1_000 * mb))
        assertEquals(Fetch.Needed(20 * mb, 500 * mb), PlaybackTest.fetchPlan(1_000 * mb, 10 * mb, 500 * mb))
        // Free space must leave the reserve behind.
        assertEquals(Fetch.NoSpace(30 * mb, 100 * mb), PlaybackTest.fetchPlan(1_000 * mb, 0, 100 * mb))
        assertEquals(Fetch.Needed(30 * mb, 230 * mb), PlaybackTest.fetchPlan(1_000 * mb, 0, 230 * mb))
        assertEquals(Fetch.NoSpace(30 * mb, 229 * mb), PlaybackTest.fetchPlan(1_000 * mb, 0, 229 * mb))
        // Garbage in: a negative free figure or prefix does not make room or skip the fetch.
        assertEquals(Fetch.NoSpace(30 * mb, 0), PlaybackTest.fetchPlan(1_000 * mb, -5, -1))
    }

    @Test
    fun `a smooth run is clean`() {
        assertEquals(Verdict.CLEAN, PlaybackTest.verdict(samples(dropped = 3), 30_000, failed = false))
        assertEquals(Verdict.CLEAN, PlaybackTest.verdict(samples(dropped = 0), 30_000, failed = false))
    }

    @Test
    fun `dropped frames from the disk are the decoder or the render path, never the network`() {
        assertEquals(Verdict.DECODER, PlaybackTest.verdict(samples(dropped = 200, hardware = false), 30_000, failed = false))
        assertEquals(Verdict.DECODER, PlaybackTest.verdict(samples(dropped = 100, cpu = 95), 30_000, failed = false))
        assertEquals(Verdict.RENDER, PlaybackTest.verdict(samples(dropped = 20), 30_000, failed = false))
        assertEquals(Verdict.THERMAL, PlaybackTest.verdict(samples(dropped = 60, thermal = 4), 30_000, failed = false))
    }

    @Test
    fun `audio running dry with no drops is unclear, not the network`() {
        val v = PlaybackTest.verdict(samples(dropped = 0, underruns = 4, buffer = 200), 30_000, failed = false)
        assertEquals(Verdict.UNCLEAR, v)
    }

    @Test
    fun `an error and a short run say so before any number`() {
        assertEquals(Verdict.COULD_NOT_PLAY, PlaybackTest.verdict(samples(dropped = 500), 30_000, failed = true))
        assertEquals(Verdict.TOO_SHORT, PlaybackTest.verdict(samples(dropped = 500), 4_000, failed = false))
        assertEquals(Verdict.TOO_SHORT, PlaybackTest.verdict(emptyList(), 9_999, failed = false))
        assertEquals(Verdict.CLEAN, PlaybackTest.verdict(emptyList(), 30_000, failed = false))
    }

    @Test
    fun `the report lines carry the numbers and no title`() {
        val lines = PlaybackTest.reportLines(
            PlaybackTest.Result(Verdict.DECODER, 212, 0, 30_000, "c2.android.hevc.decoder", false, "video/hevc"),
            fetched = true,
        )
        assertEquals(3, lines.size)
        assertTrue("first 30 s" in lines[0] && "30 MB fetched first" in lines[0])
        assertTrue("verdict decoder, played 30 s, dropped 212" in lines[1])
        assertTrue("decoder c2.android.hevc.decoder software" in lines[2])
        assertFalse("fetched" in PlaybackTest.reportLines(PlaybackTest.Result(Verdict.CLEAN, 0, 0, 30_000, "", true, ""), false)[0])
        // Odd characters in a codec name never reach the file.
        val odd = PlaybackTest.reportLines(PlaybackTest.Result(Verdict.CLEAN, 0, 0, 1, "a\nb <c>", true, "video/x\n\u0000"), false)
        assertFalse(odd.any { '\n' in it || '<' in it })
    }
}
