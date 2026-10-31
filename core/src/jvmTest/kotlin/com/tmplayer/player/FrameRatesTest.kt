package com.tmplayer.player

import com.tmplayer.data.DecodeJudge
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** HX11 and HX37: a Matroska without a default duration says no frame rate and no bit rate. */
class FrameRatesTest {

    /** Whole millisecond times cannot tell 23.976 from 24 (0.1% apart over a two second window), so the tolerance is 0.05. */
    private fun near(expected: Float, actual: Float) = assertTrue(kotlin.math.abs(expected - actual) < 0.05f, "expected $expected, got $actual")

    // ---- plausible, pick, snap, forDisplay ----------------------------------------------------

    @Test
    fun `unknown and absurd rates are zero`() {
        for (bad in listOf(-1f, 0f, -24f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 4.9f, 240.1f, 1e9f, Float.MAX_VALUE, Float.MIN_VALUE)) {
            assertEquals(0f, FrameRates.plausible(bad), "plausible($bad)")
            assertEquals(0f, FrameRates.forDisplay(bad), "forDisplay($bad)")
            assertNull(FrameRates.snap(bad), "snap($bad)")
        }
        assertEquals(24f, FrameRates.plausible(24f))
        assertEquals(240f, FrameRates.plausible(240f))
    }

    @Test
    fun `the container wins, the measured rate fills the gap, nothing stays zero`() {
        assertEquals(25f, FrameRates.pick(25f, 30f))
        assertEquals(30f, FrameRates.pick(-1f, 30f))
        assertEquals(30f, FrameRates.pick(Float.NaN, 30f))
        assertEquals(0f, FrameRates.pick(-1f, 0f))
        assertEquals(0f, FrameRates.pick(-1f, Float.POSITIVE_INFINITY))
        assertEquals("container", FrameRates.source(25f, 30f))
        assertEquals("derived", FrameRates.source(-1f, 30f))
        assertEquals("unknown", FrameRates.source(-1f, 0f))
        assertEquals("unknown", FrameRates.source(Float.NaN, Float.NaN))
    }

    @Test
    fun `near rates snap to the standard ones and others stay as they are`() {
        near(24000f / 1001f, FrameRates.forDisplay(23.97f))
        near(24f, FrameRates.forDisplay(24.1f))
        near(25f, FrameRates.forDisplay(24.9f))
        near(60000f / 1001f, FrameRates.forDisplay(59.9f))
        assertEquals(15f, FrameRates.forDisplay(15f))
        assertEquals(37f, FrameRates.forDisplay(37f))
    }

    // ---- bitrate -------------------------------------------------------------------------------

    @Test
    fun `bitrate comes from the format, else from size over length, else zero`() {
        assertEquals(4500, FrameRates.bitrateKbps(4_500_000, 0, 0))
        // 100 MB over 1000 s is 800 kbit/s.
        assertEquals(800, FrameRates.bitrateKbps(-1, 100_000_000L, 1000))
        assertEquals(0, FrameRates.bitrateKbps(-1, 0, 1000))
        assertEquals(0, FrameRates.bitrateKbps(-1, 100_000_000L, 0))
        assertEquals(0, FrameRates.bitrateKbps(0, -5L, -5))
        assertEquals(0, FrameRates.bitrateKbps(Int.MIN_VALUE, Long.MIN_VALUE, Int.MIN_VALUE))
        // Absurd sizes and tiny lengths are zero, not an overflowed figure.
        assertEquals(0, FrameRates.bitrateKbps(-1, Long.MAX_VALUE, 1))
        assertEquals(0, FrameRates.bitrateKbps(-1, Long.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(2_000_000, FrameRates.bitrateKbps(Int.MAX_VALUE, 0, 0))
        // Under 1 kbit/s rounds to nothing known.
        assertEquals(0, FrameRates.bitrateKbps(-1, 10L, 100))
    }

    // ---- the estimator -------------------------------------------------------------------------

    /** Matroska times: whole milliseconds, as a container writes them. */
    private fun feed(estimator: FrameRateEstimator, frames: Int, fps: Double, startUs: Long = 0, wholeMs: Boolean = true): Float? {
        var found: Float? = null
        for (i in 0 until frames) {
            val exact = startUs + (i * 1_000_000.0 / fps)
            val us = if (wholeMs) (exact / 1000).toLong() * 1000 else exact.toLong()
            estimator.add(us)?.let { found = it }
        }
        return found
    }

    @Test
    fun `a film's rate is found from whole millisecond times`() {
        near(24000f / 1001f, assertNotNull(feed(FrameRateEstimator(), 60, 24000.0 / 1001)))
        near(24f, assertNotNull(feed(FrameRateEstimator(), 60, 24.0)))
        near(25f, assertNotNull(feed(FrameRateEstimator(), 60, 25.0)))
        near(30000f / 1001f, assertNotNull(feed(FrameRateEstimator(), 60, 30000.0 / 1001)))
        near(60f, assertNotNull(feed(FrameRateEstimator(), 130, 60.0)))
        near(50f, assertNotNull(feed(FrameRateEstimator(), 130, 50.0)))
    }

    @Test
    fun `it answers once and then stops`() {
        val estimator = FrameRateEstimator()
        assertNotNull(feed(estimator, 60, 24.0))
        assertTrue(estimator.finished)
        assertNull(feed(estimator, 60, 30.0))
        near(24f, estimator.fps)
    }

    @Test
    fun `it needs a whole window and ignores repeats and backwards times`() {
        val estimator = FrameRateEstimator()
        assertNull(feed(estimator, 30, 24.0))
        assertEquals(0f, estimator.fps)
        // The same time twice and a time going back add nothing.
        estimator.add(5_000_000)
        estimator.add(5_000_000)
        estimator.add(1_000)
        assertEquals(0f, estimator.fps)
    }

    @Test
    fun `a seek in the middle of a window throws that window away, not the whole measure`() {
        val estimator = FrameRateEstimator()
        feed(estimator, 20, 24.0)
        // A jump of ten minutes: a gap, not a frame step.
        val found = feed(estimator, 80, 24.0, startUs = 600_000_000L)
        near(24f, assertNotNull(found))
    }

    @Test
    fun `a rate that is not steady is given up on after a few windows`() {
        val estimator = FrameRateEstimator()
        val random = Random(7)
        var t = 0L
        repeat(48 * 5) {
            t += if (random.nextBoolean()) 10_000L else 90_000L
            estimator.add(t)
        }
        assertEquals(0f, estimator.fps)
        assertTrue(estimator.gaveUp)
        assertTrue(estimator.finished)
        assertNull(estimator.add(t + 40_000))
    }

    @Test
    fun `garbage times never throw and never give an absurd rate`() {
        val random = Random(11)
        repeat(50) {
            val estimator = FrameRateEstimator()
            repeat(600) {
                val us = when (random.nextInt(6)) {
                    0 -> Long.MAX_VALUE
                    1 -> Long.MIN_VALUE
                    2 -> -1L
                    3 -> 0L
                    else -> random.nextLong(0, 5_000_000_000L)
                }
                estimator.add(us)
            }
            val fps = estimator.fps
            assertTrue(fps == 0f || fps in FrameRates.MIN_FPS..FrameRates.MAX_FPS, "fps $fps")
        }
    }

    @Test
    fun `a time beyond a week of film and a flat run of zeros stay unknown`() {
        val estimator = FrameRateEstimator()
        repeat(200) { estimator.add(0L) }
        assertEquals(0f, estimator.fps)
        repeat(200) { estimator.add(Long.MAX_VALUE - it) }
        assertEquals(0f, estimator.fps)
    }

    @Test
    fun `reset starts a new measure`() {
        val estimator = FrameRateEstimator()
        feed(estimator, 60, 24.0)
        estimator.reset()
        assertEquals(0f, estimator.fps)
        assertTrue(!estimator.finished)
        near(50f, assertNotNull(feed(estimator, 130, 50.0)))
    }

    // ---- DecodeJudge with a frame rate that is not known ---------------------------------------

    private val tvCpu = DecodeJudge.Cpu(cores = 4, maxGhz = 1.4f, is64Bit = false)

    private fun hevc(fps: Float) = DecodeJudge.Picture(DecodeJudge.MIME_HEVC, 8, 1920, 1080, fps)

    @Test
    fun `an unknown frame rate is judged as the assumed one, whatever form unknown takes`() {
        val assumed = DecodeJudge.judge(hevc(0f), hardware = false, cpu = tvCpu)
        for (unknown in listOf(-1f, Float.NaN, -30f, 0.5f)) {
            assertEquals(assumed, DecodeJudge.judge(hevc(unknown), hardware = false, cpu = tvCpu), "fps $unknown")
            assertEquals(hevc(0f).pixelRate, hevc(unknown).pixelRate, "pixelRate $unknown")
        }
        assertEquals(DecodeJudge.load(hevc(0f), tvCpu), DecodeJudge.load(hevc(-1f), tvCpu))
    }

    @Test
    fun `an absurd frame rate does not make a light picture look impossible`() {
        val small = DecodeJudge.Picture(DecodeJudge.MIME_AVC, 8, 854, 480, Float.POSITIVE_INFINITY)
        assertEquals(DecodeJudge.Verdict.SOFTWARE_FITS, DecodeJudge.judge(small, hardware = false, cpu = tvCpu))
        val huge = DecodeJudge.Picture(DecodeJudge.MIME_AVC, 8, 854, 480, 1e12f)
        assertEquals(DecodeJudge.Verdict.SOFTWARE_FITS, DecodeJudge.judge(huge, hardware = false, cpu = tvCpu))
        assertTrue(huge.pixelRate.isFinite())
    }

    @Test
    fun `hardware stays hardware with no frame rate, and a derived rate changes the table`() {
        assertEquals(DecodeJudge.Verdict.HARDWARE, DecodeJudge.judge(hevc(-1f), hardware = true, cpu = tvCpu))
        // 60 fps is heavier than the assumed 30: the derived rate is what the table should see.
        assertTrue(hevc(60f).pixelRate > hevc(0f).pixelRate)
        assertEquals(hevc(0f).pixelRate * 24 / 30, hevc(24f).pixelRate, 1.0)
    }
}
