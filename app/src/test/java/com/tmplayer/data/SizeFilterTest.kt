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

    private val min = SizeFilter.DEFAULT_MIN
    private val max = SizeFilter.DEFAULT_MAX

    @Test
    fun `defaults are 400 MB and 2 and a half GB`() {
        assertEquals(400 * MB, SizeFilter.DEFAULT_MIN)
        assertEquals(2560 * MB, SizeFilter.DEFAULT_MAX)
        assertEquals(8 * GB, CEILING)
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
        assertEquals(0.5f, SizeFilter.fraction(4 * GB), 0.001f)
        assertEquals(1f, SizeFilter.fraction(CEILING), 0.001f)
        assertEquals(1f, SizeFilter.fraction(99 * GB), 0.001f)
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
}
