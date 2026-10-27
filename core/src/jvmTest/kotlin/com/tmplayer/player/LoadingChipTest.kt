package com.tmplayer.player

import kotlin.test.Test
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
}
