package com.tmplayer.data

import com.tmplayer.data.SizeFilter.CEILING
import com.tmplayer.data.SizeFilter.FLOOR
import com.tmplayer.data.SizeFilter.GB
import com.tmplayer.data.SizeFilter.MB
import com.tmplayer.data.SizeFilter.STEP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SizeFilterTest {

    // Explicit bounds rather than the defaults, which now have no top for a remux to exceed.
    private val min = 400 * MB
    private val max = 2560 * MB

    @Test
    fun `defaults are 50 MB and no upper bound`() {
        assertEquals(50 * MB, SizeFilter.DEFAULT_MIN)
        assertEquals(CEILING, SizeFilter.DEFAULT_MAX)
        assertEquals(8 * GB, CEILING)
    }

    @Test
    fun `the defaults keep a trailer out and every episode size in`() {
        val lo = SizeFilter.DEFAULT_MIN
        val hi = SizeFilter.DEFAULT_MAX
        assertFalse(SizeFilter.matches(30 * MB, lo, hi))
        assertTrue(SizeFilter.matches(180 * MB, lo, hi))
        assertTrue(SizeFilter.matches(6 * GB, lo, hi))
        assertTrue(SizeFilter.matches(20 * GB, lo, hi))
    }

    @Test
    fun `a video inside the bounds is listed`() {
        assertTrue(SizeFilter.matches(1400 * MB, min, max))
    }

    @Test
    fun `a trailer below the floor is hidden`() {
        assertFalse(SizeFilter.matches(80 * MB, min, max))
    }

    @Test
    fun `a remux above the ceiling is hidden`() {
        assertFalse(SizeFilter.matches(6 * GB, min, max))
    }

    @Test
    fun `the bounds are inclusive`() {
        assertTrue(SizeFilter.matches(min, min, max))
        assertTrue(SizeFilter.matches(max, min, max))
    }

    @Test
    fun `a file of unknown size is never hidden`() {
        // Telegram reports zero for some documents; hiding those would lose real videos.
        assertTrue(SizeFilter.matches(0, min, max))
        assertTrue(SizeFilter.matches(-1, min, max))
    }

    @Test
    fun `the top of the slider means no upper bound at all`() {
        assertTrue(SizeFilter.matches(20 * GB, FLOOR, CEILING))
    }

    @Test
    fun `stepping snaps a default that is off the grid`() {
        // 2560 MB is not a multiple of 100 MB, so the first press lands on the grid rather
        // than carrying the odd 60 MB along forever.
        val up = SizeFilter.step(2560 * MB, 1)
        assertEquals(0L, up % STEP)
        assertTrue(up > 2560 * MB)

        val down = SizeFilter.step(2560 * MB, -1)
        assertEquals(0L, down % STEP)
        assertTrue(down < 2560 * MB)
    }

    @Test
    fun `stepping from the grid moves exactly one step`() {
        assertEquals(500 * MB, SizeFilter.step(400 * MB, 1))
        assertEquals(300 * MB, SizeFilter.step(400 * MB, -1))
    }

    @Test
    fun `stepping cannot leave the slider`() {
        assertEquals(FLOOR, SizeFilter.step(FLOOR, -1))
        assertEquals(CEILING, SizeFilter.step(CEILING, 1))
    }

    @Test
    fun `the bounds cannot cross over`() {
        // Pushing the floor up past the ceiling parks it one step below instead.
        assertEquals(max - STEP, SizeFilter.clampMin(max + GB, max))
        assertEquals(min + STEP, SizeFilter.clampMax(FLOOR, min))
    }

    @Test
    fun `labels read the way the value would be spoken`() {
        assertEquals("No minimum", SizeFilter.label(FLOOR))
        assertEquals("No limit", SizeFilter.label(CEILING))
        assertEquals("400 MB", SizeFilter.label(400 * MB))
        assertEquals("2 GB", SizeFilter.label(2 * GB))
        assertEquals("2.5 GB", SizeFilter.label(2560 * MB))
    }

    @Test
    fun `the thumb position spans the whole slider`() {
        assertEquals(0f, SizeFilter.fraction(FLOOR), 0.001f)
        assertEquals(1f, SizeFilter.fraction(CEILING), 0.001f)
        assertEquals(1f, SizeFilter.fraction(99 * GB), 0.001f)
        assertEquals(0f, SizeFilter.fraction(-1), 0.001f)
    }

    @Test
    fun `no minimum and the 50 MB default sit visibly apart`() {
        // On a linear track 50 MB was 0.6 per cent in, a few pixels from "No minimum".
        val noMinimum = SizeFilter.fraction(FLOOR)
        val default = SizeFilter.fraction(SizeFilter.DEFAULT_MIN)
        val hundred = SizeFilter.fraction(100 * MB)
        assertTrue(default - noMinimum >= 0.04f)
        // The first steps are spaced evenly: No minimum, 50 MB, 100 MB.
        assertEquals(default - noMinimum, hundred - default, 0.001f)
        // The first gigabyte, where the choices are, gets a good share of the track.
        assertTrue(SizeFilter.fraction(GB) >= 0.4f)
    }

    @Test
    fun `the scale only ever rises`() {
        var last = -1f
        var bytes = FLOOR
        while (bytes <= CEILING) {
            val f = SizeFilter.fraction(bytes)
            assertTrue("$bytes went backwards", f > last)
            last = f
            bytes += 10 * MB
        }
    }

    @Test
    fun `a position maps back to the size it shows`() {
        // Every value the slider can land on survives the trip to a position and back.
        val stops = buildList {
            add(FLOOR)
            add(SizeFilter.DEFAULT_MIN)
            var v = STEP
            while (v < CEILING) { add(v); v += STEP }
            add(CEILING)
        }
        for (v in stops) {
            assertEquals(SizeFilter.label(v), v, SizeFilter.snap(SizeFilter.bytesAt(SizeFilter.fraction(v))))
        }
        assertEquals(FLOOR, SizeFilter.bytesAt(0f))
        assertEquals(CEILING, SizeFilter.bytesAt(1f))
        assertEquals(FLOOR, SizeFilter.bytesAt(-0.5f))
        assertEquals(CEILING, SizeFilter.bytesAt(1.5f))
    }

    @Test
    fun `a finger near the start picks no minimum or 50 MB, not a guess`() {
        assertEquals(FLOOR, SizeFilter.snap(SizeFilter.bytesAt(0.01f)))
        assertEquals(SizeFilter.DEFAULT_MIN, SizeFilter.snap(SizeFilter.bytesAt(0.05f)))
        assertEquals(100 * MB, SizeFilter.snap(SizeFilter.bytesAt(0.10f)))
    }

    // describe() has one case per combination of open and closed ends.

    @Test
    fun `describes both bounds when both are set`() {
        assertEquals(
            "You'll see videos between 400 MB and 2.5 GB.",
            SizeFilter.describe(400 * MB, 2560 * MB),
        )
    }

    @Test
    fun `drops the floor from the sentence when there is no minimum`() {
        val text = SizeFilter.describe(FLOOR, 2560 * MB)
        assertEquals("You'll see videos up to 2.5 GB.", text)
        assertFalse(text.contains("No minimum"))
    }

    @Test
    fun `drops the ceiling from the sentence when there is no limit`() {
        val text = SizeFilter.describe(400 * MB, CEILING)
        assertEquals("You'll see videos from 400 MB upwards.", text)
        assertFalse(text.contains("No limit"))
    }

    @Test
    fun `says so plainly when nothing is being filtered`() {
        val text = SizeFilter.describe(FLOOR, CEILING)
        assertEquals("You'll see every video in the chat, whatever its size.", text)
        assertFalse(text.contains("No minimum"))
        assertFalse(text.contains("No limit"))
    }

    @Test
    fun `a dragged value snaps onto the step grid`() {
        assertEquals(SizeFilter.STEP * 5, SizeFilter.snap(SizeFilter.STEP * 5 + SizeFilter.STEP / 4))
        assertEquals(SizeFilter.STEP * 6, SizeFilter.snap(SizeFilter.STEP * 5 + SizeFilter.STEP * 3 / 4))
    }

    @Test
    fun `both ends of the range snap to themselves`() {
        // "No minimum" and "No limit" are the two values a finger is most likely to be aiming at,
        // and rounding either one onto the grid would put it just inside the range instead.
        assertEquals(SizeFilter.FLOOR, SizeFilter.snap(SizeFilter.FLOOR))
        assertEquals(SizeFilter.FLOOR, SizeFilter.snap(SizeFilter.STEP / 4))
        assertEquals(SizeFilter.CEILING, SizeFilter.snap(SizeFilter.CEILING))
        assertEquals(SizeFilter.CEILING, SizeFilter.snap(SizeFilter.CEILING - SizeFilter.STEP / 4))
    }

    @Test
    fun `the default minimum survives the slider`() {
        // The phone slider shows and writes snap() of what is stored. It used to round 50 MB
        // down to "No minimum" while the note said "from 50 MB upwards" and the filter hid clips.
        val shown = SizeFilter.snap(SizeFilter.DEFAULT_MIN)
        assertEquals(SizeFilter.DEFAULT_MIN, shown)
        assertEquals(SizeFilter.label(SizeFilter.DEFAULT_MIN), SizeFilter.label(shown))
        // The same thumb position as a float, which is what the RangeSlider hands back.
        assertEquals(SizeFilter.DEFAULT_MIN, SizeFilter.snap(SizeFilter.DEFAULT_MIN.toFloat().toLong()))
        assertEquals(SizeFilter.DEFAULT_MAX, SizeFilter.snap(SizeFilter.DEFAULT_MAX.toFloat().toLong()))
        // And dragged near it, rather than only resting on it.
        assertEquals(SizeFilter.DEFAULT_MIN, SizeFilter.snap(40 * MB))
        assertEquals(SizeFilter.DEFAULT_MIN, SizeFilter.snap(70 * MB))
        assertEquals(STEP, SizeFilter.snap(80 * MB))
        assertEquals(FLOOR, SizeFilter.snap(10 * MB))
    }

    @Test
    fun `the defaults read the same everywhere they are shown`() {
        val min = SizeFilter.DEFAULT_MIN
        val max = SizeFilter.DEFAULT_MAX
        assertTrue(SizeFilter.isDefault(min, max))
        assertEquals("50 MB", SizeFilter.label(SizeFilter.snap(min)))
        assertEquals("No limit", SizeFilter.label(SizeFilter.snap(max)))
        assertEquals("You'll see videos from 50 MB upwards.", SizeFilter.describe(min, max))
        // What the note promises is what the filter does.
        assertFalse(SizeFilter.matches(30 * MB, min, max))
        assertTrue(SizeFilter.matches(60 * MB, min, max))
        assertFalse(SizeFilter.isDefault(FLOOR, CEILING))
        assertFalse(SizeFilter.isDefault(min, 2 * GB))
    }
}
