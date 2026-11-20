package com.tmplayer.online

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a growing subtitle may be swapped into the Android player (HX91 review): not while the
 * player buffers or the viewer scrubs, at the next quiet moment or when paused, and at most once
 * a minute. The swap rebuilds the media item, which rebuffers a streamed video.
 */
class SubtitleSwapTest {

    private val gap = SubtitleSwap.MIN_GAP_MS

    @Test
    fun `the first swap goes in at a quiet moment`() {
        assertTrue(SubtitleSwap.swapNow(1_000, 0, buffering = false, scrubbing = false, paused = false))
    }

    @Test
    fun `it waits while the player buffers or the viewer scrubs`() {
        assertFalse(SubtitleSwap.swapNow(1_000, 0, buffering = true, scrubbing = false, paused = false))
        assertFalse(SubtitleSwap.swapNow(1_000, 0, buffering = false, scrubbing = true, paused = false))
        assertFalse(SubtitleSwap.swapNow(1_000, 0, buffering = true, scrubbing = true, paused = false))
    }

    @Test
    fun `a paused player takes it, unless a scrub is under way`() {
        assertTrue(SubtitleSwap.swapNow(1_000, 0, buffering = true, scrubbing = false, paused = true))
        assertFalse(SubtitleSwap.swapNow(1_000, 0, buffering = false, scrubbing = true, paused = true))
    }

    @Test
    fun `never twice within a minute, whatever the player is doing`() {
        val last = 100_000L
        assertFalse(SubtitleSwap.swapNow(last + gap - 1, last, buffering = false, scrubbing = false, paused = false))
        assertFalse(SubtitleSwap.swapNow(last + 5, last, buffering = false, scrubbing = false, paused = true))
        assertTrue(SubtitleSwap.swapNow(last + gap, last, buffering = false, scrubbing = false, paused = false))
    }

    @Test
    fun `the deferred swap goes at the first quiet moment after the minute`() {
        val last = 10_000L
        // Busy buffering when the minute ends, quiet two ticks later.
        assertFalse(SubtitleSwap.swapNow(last + gap, last, buffering = true, scrubbing = false, paused = false))
        assertTrue(SubtitleSwap.swapNow(last + gap + 2 * SubtitleSwap.RECHECK_MS, last, buffering = false, scrubbing = false, paused = false))
    }

    @Test
    fun `a clock that went backwards does not block the swap for ever`() {
        assertTrue(SubtitleSwap.swapNow(5_000, 900_000, buffering = false, scrubbing = false, paused = false))
    }
}
