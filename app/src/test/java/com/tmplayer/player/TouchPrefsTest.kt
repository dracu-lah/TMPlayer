package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchPrefsTest {

    @Test
    fun `zones split the picture 35 30 35`() {
        assertEquals(TapZone.Left, TapZone.of(0f, 1000))
        assertEquals(TapZone.Left, TapZone.of(349f, 1000))
        assertEquals(TapZone.Centre, TapZone.of(351f, 1000))
        assertEquals(TapZone.Centre, TapZone.of(649f, 1000))
        assertEquals(TapZone.Right, TapZone.of(651f, 1000))
        assertEquals(TapZone.Centre, TapZone.of(10f, 0))
    }

    @Test
    fun `taps on one side inside the window add up`() {
        val counter = SeekCounter()
        assertEquals(1, counter.tap(TapZone.Right, 1_000))
        assertEquals(2, counter.tap(TapZone.Right, 1_500))
        assertEquals(3, counter.tap(TapZone.Right, 2_200))
        assertTrue(counter.isLive(2_900))
        assertFalse(counter.isLive(3_000))
    }

    @Test
    fun `a pause longer than the window starts a new run`() {
        val counter = SeekCounter()
        counter.tap(TapZone.Left, 0)
        counter.tap(TapZone.Left, 500)
        assertEquals(1, counter.tap(TapZone.Left, 500 + SeekCounter.WINDOW_MS + 1))
    }

    @Test
    fun `the other side starts a new run`() {
        val counter = SeekCounter()
        counter.tap(TapZone.Left, 0)
        counter.tap(TapZone.Left, 300)
        assertEquals(1, counter.tap(TapZone.Right, 600))
        assertEquals(TapZone.Right, counter.zone)
    }

    @Test
    fun `stored values off the list fall back to the defaults`() {
        assertEquals(TouchPrefs.DOUBLE_TAP_DEFAULT_MS, TouchPrefs.sanitiseDoubleTap(7_000))
        assertEquals(15_000L, TouchPrefs.sanitiseDoubleTap(15_000))
        assertEquals(TouchPrefs.DOUBLE_TAP_DEFAULT_MS, TouchPrefs.sanitiseDoubleTap(null))
        assertEquals(TouchPrefs.HOLD_DEFAULT, TouchPrefs.sanitiseHold(2.5f))
        assertEquals(0f, TouchPrefs.sanitiseHold(0f))
        assertEquals(TouchPrefs.TIMEOUT_DEFAULT_MS, TouchPrefs.sanitiseTimeout(1_234))
        assertEquals(0L, TouchPrefs.sanitiseTimeout(0))
    }

    @Test
    fun `steps clamp at both ends of a list`() {
        val choices = TouchPrefs.DOUBLE_TAP_CHOICES_MS
        assertEquals(5_000L, TouchPrefs.step(choices, 5_000L, -1))
        assertEquals(15_000L, TouchPrefs.step(choices, 10_000L, 1))
        assertEquals(30_000L, TouchPrefs.step(choices, 30_000L, 1))
    }

    @Test
    fun `labels read as a person would say them`() {
        assertEquals("Off", TouchPrefs.holdLabel(0f))
        assertEquals("2x", TouchPrefs.holdLabel(2f))
        assertEquals("1.5x", TouchPrefs.holdLabel(1.5f))
        assertEquals("Never", TouchPrefs.timeoutLabel(0))
        assertEquals("5 seconds", TouchPrefs.timeoutLabel(5_000))
        assertEquals("3.5 seconds", TouchPrefs.timeoutLabel(3_500))
    }
}
