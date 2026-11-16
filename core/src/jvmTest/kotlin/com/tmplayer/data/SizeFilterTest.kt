package com.tmplayer.data

import com.tmplayer.data.SizeFilter.CEILING
import com.tmplayer.data.SizeFilter.CLIP_MIN
import com.tmplayer.data.SizeFilter.FLOOR
import com.tmplayer.data.SizeFilter.GB
import com.tmplayer.data.SizeFilter.MB
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R1, show every video by default: the table of what a stored minimum means, what the clip switch
 * hides, what Home and the alerts keep out, and how the hidden set is counted.
 */
class SizeFilterTest {

    private val max = CEILING

    @Test
    fun `the default lists every video`() {
        assertEquals(0L, SizeFilter.DEFAULT_MIN)
        assertEquals(CEILING, SizeFilter.DEFAULT_MAX)
        assertEquals(8 * GB, CEILING)
        for (size in listOf(1L, 5 * MB, 30 * MB, 180 * MB, 450 * MB, 6 * GB, 20 * GB)) {
            assertTrue("$size", SizeFilter.matches(size, SizeFilter.DEFAULT_MIN, SizeFilter.DEFAULT_MAX))
        }
        assertTrue(SizeFilter.isDefault(SizeFilter.DEFAULT_MIN, SizeFilter.DEFAULT_MAX))
    }

    @Test
    fun `a stored minimum reads as the apps read it`() {
        // Nothing stored, zero and the old 400 MB default are "not set".
        assertEquals(FLOOR, SizeFilter.effectiveMin(null))
        assertEquals(FLOOR, SizeFilter.effectiveMin(0))
        assertEquals(FLOOR, SizeFilter.effectiveMin(-5))
        assertEquals(FLOOR, SizeFilter.effectiveMin(400 * MB))
        // The clip switch's value is the switch on.
        assertEquals(CLIP_MIN, SizeFilter.effectiveMin(CLIP_MIN))
        // A floor chosen on purpose keeps working, whatever it is.
        assertEquals(100 * MB, SizeFilter.effectiveMin(100 * MB))
        assertEquals(GB, SizeFilter.effectiveMin(GB))
        assertEquals(2560 * MB, SizeFilter.effectiveMin(2560 * MB))
    }

    @Test
    fun `a stored maximum reads as no limit unless it was chosen`() {
        assertEquals(CEILING, SizeFilter.effectiveMax(null))
        assertEquals(CEILING, SizeFilter.effectiveMax(0))
        assertEquals(CEILING, SizeFilter.effectiveMax(CEILING))
        assertEquals(CEILING, SizeFilter.effectiveMax(CEILING * 4))
        assertEquals(2 * GB, SizeFilter.effectiveMax(2 * GB))
    }

    @Test
    fun `turning Hide small clips on keeps a custom floor and off removes it`() {
        val table = listOf(
            // stored, on -> written (null: nothing stored)
            Triple(null, true, CLIP_MIN),
            Triple(0L, true, CLIP_MIN),
            Triple(CLIP_MIN, true, CLIP_MIN),
            Triple(300 * MB, true, 300 * MB),
            Triple(10 * MB, true, 10 * MB),
            Triple(SizeFilter.OLD_DEFAULT_MIN, true, CLIP_MIN),
            Triple(null, false, null),
            Triple(300 * MB, false, null),
            Triple(CLIP_MIN, false, null),
        )
        for ((stored, on, written) in table) {
            assertEquals("stored=$stored on=$on", written, SizeFilter.clipSwitchFloor(stored, on))
        }
    }

    @Test
    fun `the clip switch reads on for any floor, a custom floor is told apart`() {
        assertTrue(SizeFilter.hidesSmallClips(CLIP_MIN))
        assertFalse(SizeFilter.hidesSmallClips(FLOOR))
        assertTrue(SizeFilter.hidesSmallClips(100 * MB))

        assertFalse(SizeFilter.isCustomMin(FLOOR))
        assertFalse(SizeFilter.isCustomMin(CLIP_MIN))
        assertTrue(SizeFilter.isCustomMin(100 * MB))
        assertTrue(SizeFilter.isCustomMin(GB))

        assertFalse(SizeFilter.hasCustomLimits(FLOOR, CEILING))
        assertFalse(SizeFilter.hasCustomLimits(CLIP_MIN, CEILING))
        assertTrue(SizeFilter.hasCustomLimits(700 * MB, CEILING))
        assertTrue(SizeFilter.hasCustomLimits(FLOOR, 2 * GB))
    }

    @Test
    fun `the clip switch hides under 50 MB and nothing else`() {
        assertFalse(SizeFilter.matches(5 * MB, CLIP_MIN, max))
        assertFalse(SizeFilter.matches(CLIP_MIN - 1, CLIP_MIN, max))
        assertTrue("the bound is inclusive", SizeFilter.matches(CLIP_MIN, CLIP_MIN, max))
        assertTrue(SizeFilter.matches(300 * MB, CLIP_MIN, max))
        assertTrue(SizeFilter.matches(20 * GB, CLIP_MIN, max))
    }

    @Test
    fun `the hidden set is exactly what the switch left out`() {
        val sizes = listOf(5 * MB, 49 * MB, 50 * MB, 300 * MB, 0L, 2 * GB, 12 * MB)
        val split = SizeFilter.split(sizes, CLIP_MIN, max) { it }
        assertEquals(listOf(50 * MB, 300 * MB, 0L, 2 * GB), split.kept)
        assertEquals(3, split.hidden)
        // Off, nothing is hidden and nothing is counted.
        val all = SizeFilter.split(sizes, FLOOR, max) { it }
        assertEquals(sizes, all.kept)
        assertEquals(0, all.hidden)
    }

    @Test
    fun `a file of unknown size is never hidden`() {
        // Telegram reports zero for some documents; hiding those would lose real videos.
        assertTrue(SizeFilter.matches(0, CLIP_MIN, max))
        assertTrue(SizeFilter.matches(-1, 700 * MB, max))
    }

    @Test
    fun `a custom ceiling still works`() {
        assertFalse(SizeFilter.matches(6 * GB, FLOOR, 2 * GB))
        assertTrue(SizeFilter.matches(2 * GB, FLOOR, 2 * GB))
        assertTrue("the top means no upper bound", SizeFilter.matches(20 * GB, FLOOR, CEILING))
    }

    @Test
    fun `Home and the alerts keep a floor of their own`() {
        // The viewer's default (every video) does not let trailers into Home or ping.
        assertEquals(CLIP_MIN, SizeFilter.homeMin(FLOOR))
        assertEquals(CLIP_MIN, SizeFilter.notifyMin(FLOOR))
        assertEquals(CLIP_MIN, SizeFilter.homeMin(CLIP_MIN))
        assertEquals(SizeFilter.HOME_MIN, SizeFilter.homeMin(FLOOR))
        assertEquals(SizeFilter.NOTIFY_MIN, SizeFilter.notifyMin(FLOOR))
        // A custom floor higher than theirs is respected.
        assertEquals(700 * MB, SizeFilter.homeMin(700 * MB))
        assertEquals(700 * MB, SizeFilter.notifyMin(700 * MB))
        assertFalse(SizeFilter.matches(30 * MB, SizeFilter.homeMin(FLOOR), max))
        assertTrue(SizeFilter.matches(80 * MB, SizeFilter.homeMin(FLOOR), max))
    }

    @Test
    fun `labels and sentences read the way the value would be spoken`() {
        assertEquals("No minimum", SizeFilter.label(FLOOR))
        assertEquals("No limit", SizeFilter.label(CEILING))
        assertEquals("50 MB", SizeFilter.label(CLIP_MIN))
        assertEquals("400 MB", SizeFilter.label(400 * MB))
        assertEquals("2.5 GB", SizeFilter.label(2560 * MB))
        assertEquals("You'll see videos between 400 MB and 2.5 GB.", SizeFilter.describe(400 * MB, 2560 * MB))
        assertEquals("You'll see videos up to 2.5 GB.", SizeFilter.describe(FLOOR, 2560 * MB))
        assertEquals("You'll see videos from 700 MB upwards.", SizeFilter.describe(700 * MB, CEILING))
        assertEquals("You'll see every video in the chat, whatever its size.", SizeFilter.describe(FLOOR, CEILING))
    }

    @Test
    fun `a hundred thousand videos are split in one pass and the count is the hidden set`() {
        // One query for the hidden set: a single split over the pages as they are read, no second
        // pass over the chat. 100,000 videos, a third of them clips.
        val sizes = LongArray(100_000) { i -> if (i % 3 == 0) (1 + i % 49) * MB else (50 + i % 900) * MB }
        var calls = 0
        val start = System.nanoTime()
        val split = SizeFilter.split(sizes.toList(), CLIP_MIN, max) { calls++; it }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals("sizeOf is asked once per video", sizes.size, calls)
        assertEquals(sizes.count { it in 1 until CLIP_MIN }, split.hidden)
        assertEquals(sizes.size - split.hidden, split.kept.size)
        assertTrue("a hundred thousand videos split in $ms ms", ms < 2_000)
    }
}
