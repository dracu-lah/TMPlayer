package com.tmplayer.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoadingChipTest {

    @Test
    fun steadyPlaybackHidesIt() {
        assertFalse(LoadingChip.shows(shown = true, aheadMs = 45_000, realDownload = false))
        assertFalse(LoadingChip.shows(shown = false, aheadMs = 20_000, realDownload = false))
    }

    @Test
    fun runningShortBringsItBack() {
        assertTrue(LoadingChip.shows(shown = false, aheadMs = 4_000, realDownload = false))
        assertTrue(LoadingChip.shows(shown = false, aheadMs = 0, realDownload = false))
    }

    @Test
    fun itDoesNotBlinkAroundOneFigure() {
        // Up, it stays up until well ahead; down, it stays down until truly short.
        assertTrue(LoadingChip.shows(shown = true, aheadMs = 15_000, realDownload = false))
        assertFalse(LoadingChip.shows(shown = false, aheadMs = 15_000, realDownload = false))
    }

    @Test
    fun unknownKeepsItAsItIs() {
        assertTrue(LoadingChip.shows(shown = true, aheadMs = -1, realDownload = false))
        assertFalse(LoadingChip.shows(shown = false, aheadMs = -1, realDownload = false))
    }

    @Test
    fun aDownloadAlwaysShowsItsProgress() {
        assertTrue(LoadingChip.shows(shown = false, aheadMs = 60_000, realDownload = true))
    }

    @Test
    fun startingOrStalledAlwaysShowsIt() {
        assertTrue(LoadingChip.shows(shown = false, aheadMs = 45_000, realDownload = false, starting = true))
        assertTrue(LoadingChip.shows(shown = false, aheadMs = -1, realDownload = false, starting = true))
    }

    @Test
    fun aFullBufferUnderTheHideFigureStillHidesIt() {
        // The byte cap holds the buffer at 18 s: nothing more is coming, so nothing to say.
        assertFalse(LoadingChip.shows(shown = true, aheadMs = 18_000, realDownload = false, bufferFull = true))
        // Full but nearly empty is not smooth: it stays up.
        assertTrue(LoadingChip.shows(shown = true, aheadMs = 4_000, realDownload = false, bufferFull = true))
        // Still loading at 18 s: the hysteresis keeps it up.
        assertTrue(LoadingChip.shows(shown = true, aheadMs = 18_000, realDownload = false, bufferFull = false))
    }

    @Test
    fun aDownloadShowsEvenWithAFullBuffer() {
        assertTrue(LoadingChip.shows(shown = true, aheadMs = 18_000, realDownload = true, bufferFull = true))
    }

    @Test
    fun noFlickerAcrossTheBand() {
        // Walk the buffer through the band: it comes up once under 10 s and goes once at 30 s.
        var shown = false
        val seen = mutableListOf<Boolean>()
        for (ahead in listOf(40_000L, 20_000, 12_000, 9_000, 12_000, 25_000, 29_000, 31_000, 29_000, 12_000)) {
            shown = LoadingChip.shows(shown, ahead, realDownload = false)
            seen += shown
        }
        assertEquals(listOf(false, false, false, true, true, true, true, false, false, false), seen)
    }
}
