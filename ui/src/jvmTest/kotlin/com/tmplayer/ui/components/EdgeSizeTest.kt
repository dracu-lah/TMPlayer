package com.tmplayer.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How wide the Home carousel's picture is drawn for a source of each size, on each form factor's
 * frame in pixels (HX55): a source too small for the frame stands at its soft cap at the end
 * instead of being stretched across it.
 */
class EdgeSizeTest {

    /** A form factor's carousel frame: width, height in pixels. */
    private val tv1080 = 1920 to 560 // 280 dp at xhdpi-ish 2x
    private val desktop1280 = 1280 to 480
    private val desktop1920 = 1920 to 640
    private val desktop4k = 3840 to 1100
    private val phoneLand = 1900 to 822

    private fun width(frame: Pair<Int, Int>, picW: Int, picH: Int, standIn: Boolean = false, upright: Boolean = false): Int =
        edgeSize(frame.first, frame.second, frame.first, true, picW, picH, standIn, upright).first

    @Test
    fun `a backdrop wide enough for the frame is drawn across all of it`() {
        // The 1280 w1280 copy on a 1080p television is exactly the soft limit.
        assertEquals(1920, width(tv1080, 1280, 720))
        assertEquals(1280, width(desktop1280, 1280, 720))
        assertEquals(1920, width(desktop1920, 1920, 1080))
        assertEquals(1900, width(phoneLand, 1280, 720))
    }

    @Test
    fun `a source too small for the frame stands at its soft cap, not across the whole width`() {
        // 780 across 1920 would be 2.5 times: drawn at 1.5 times (1170) at the end instead.
        assertEquals(1170, width(tv1080, 780, 439))
        assertEquals(1500, width(tv1080, 1000, 562))
        // A 1920 original across a 4K window: 2880 of 3840, never blown up to 2 times.
        assertEquals(2880, width(desktop4k, 1920, 1080))
    }

    @Test
    fun `a thumbnail under the sharp minimum keeps its own two times cap`() {
        assertEquals(640, width(tv1080, 320, 180))
        assertEquals((479 * 2f).toInt(), width(desktop4k, 479, 270))
    }

    @Test
    fun `no size is ever drawn wider than the frame or taller than it`() {
        for (frame in listOf(tv1080, desktop1280, desktop1920, desktop4k, phoneLand)) {
            for (picW in listOf(1, 100, 320, 479, 480, 780, 1000, 1280, 1920, 4000)) {
                for (picH in listOf(1, 180, 562, 1080, 6000)) {
                    val (w, h) = edgeSize(frame.first, frame.second, frame.first, true, picW, picH, false, false)
                    assertTrue("$frame $picW x $picH: $w", w in 0..frame.first)
                    assertTrue("$frame $picW x $picH: $h", h in 0..frame.second)
                }
            }
        }
    }

    @Test
    fun `a portrait only picture stands at the frame's height, a tiny one at its cap`() {
        // The online poster (780 x 1170): whole, at the frame's height, over a soft copy of itself.
        assertEquals(560, edgeSize(1920, 560, 1920, true, 780, 1170, false, true).second)
        assertEquals(1920, edgeSize(1920, 560, 1920, true, 780, 1170, false, true).first)
        // Telegram's 180 x 320 thumbnail of an upright video is under the minimum: capped at twice its width.
        assertEquals(360, width(tv1080, 180, 320))
    }

    @Test
    fun `the stand in, a source of zero size and a frame of zero size do not crash`() {
        assertEquals(1920, width(tv1080, 1, 1, standIn = true))
        assertEquals(1920 to 560, edgeSize(1920, 560, 1920, true, 0, 0, false, false))
        assertEquals(0 to 0, edgeSize(0, 0, 0, true, 0, 0, false, false))
        assertEquals(0, edgeSize(0, 0, 0, true, 780, 439, false, false).first)
    }

    @Test
    fun `a narrow frame is not touched`() {
        assertEquals(411 to 800, edgeSize(411, 800, 411, false, 780, 439, false, false))
    }
}
