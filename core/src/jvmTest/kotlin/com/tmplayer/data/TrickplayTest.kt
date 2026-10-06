package com.tmplayer.data

import com.tmplayer.data.Trickplay.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrickplayTest {

    private val gib = 1_073_741_824L

    // A 44 minute episode of 1.32 GB: half a megabyte a second.
    private val duration = 2_640_000L
    private val size = 1_320_000_000L
    private fun byteAt(ms: Long) = ms * size / duration

    @Test
    fun `off under the memory line and on low ram devices`() {
        // What a 1 GB stick and the 1 GB TV emulator profile report.
        assertFalse(Trickplay.available(enabled = true, totalMemoryBytes = 960_000_000L, lowRamDevice = false))
        assertFalse(Trickplay.available(enabled = true, totalMemoryBytes = gib, lowRamDevice = false))
        // A 1.5 GB stick reports a little under 1.5 GiB.
        assertTrue(Trickplay.available(enabled = true, totalMemoryBytes = 1_450_000_000L, lowRamDevice = false))
        assertTrue(Trickplay.available(enabled = true, totalMemoryBytes = 2 * gib, lowRamDevice = false))
        // Android's own low memory flag wins over the total.
        assertFalse(Trickplay.available(enabled = true, totalMemoryBytes = 2 * gib, lowRamDevice = true))
        // And the setting wins over everything.
        assertFalse(Trickplay.available(enabled = false, totalMemoryBytes = 4 * gib, lowRamDevice = false))
    }

    @Test
    fun `a finished file is covered everywhere`() {
        assertTrue(Trickplay.covered(2_000_000, duration, size, emptyList(), complete = true))
    }

    @Test
    fun `thumbnails only inside the downloaded prefix`() {
        val prefix = listOf(Span(0, byteAt(1_200_000))) // the first 20 minutes
        assertTrue(Trickplay.covered(60_000, duration, size, prefix, complete = false))
        assertTrue(Trickplay.covered(1_100_000, duration, size, prefix, complete = false))
        // Inside the prefix, but too near its end for the keyframe slack.
        assertFalse(Trickplay.covered(1_197_000, duration, size, prefix, complete = false))
        assertFalse(Trickplay.covered(1_800_000, duration, size, prefix, complete = false))
    }

    @Test
    fun `a window further on counts only with the head of the file`() {
        val window = Span(byteAt(1_500_000), byteAt(2_000_000))
        // The middle alone: no header to decode with.
        assertFalse(Trickplay.covered(1_700_000, duration, size, listOf(window), complete = false))
        val withHead = listOf(Span(0, Trickplay.HEAD_BYTES), window)
        assertTrue(Trickplay.covered(1_700_000, duration, size, withHead, complete = false))
        // Between the head and the window there is nothing.
        assertFalse(Trickplay.covered(600_000, duration, size, withHead, complete = false))
        // Too close to the window's edge for the keyframe slack.
        assertFalse(Trickplay.covered(1_502_000, duration, size, withHead, complete = false))
    }

    @Test
    fun `touching spans join`() {
        val merged = Trickplay.merge(listOf(Span(500, 900), Span(0, 500), Span(950, 950), Span(800, 1_000)))
        assertEquals(listOf(Span(0, 1_000)), merged)
        val joined = listOf(Span(0, byteAt(1_000_000)), Span(byteAt(1_000_000), byteAt(2_000_000)))
        assertTrue(Trickplay.covered(1_000_000, duration, size, joined, complete = false))
    }

    @Test
    fun `nothing without a length or a size`() {
        val all = listOf(Span(0, size))
        assertFalse(Trickplay.covered(10_000, 0, size, all, complete = false))
        assertFalse(Trickplay.covered(10_000, duration, 0, all, complete = false))
    }

    @Test
    fun `positions share a slot per step`() {
        assertEquals(0L, Trickplay.bucketMs(9_999))
        assertEquals(10_000L, Trickplay.bucketMs(10_000))
        assertEquals(0L, Trickplay.bucketMs(-5))
    }
}
