package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** WP-35: the one 35 / 30 / 35 zone map that taps and level drags both read. */
class TapZoneTest {

    @Test
    fun edgesOfTheSides() {
        assertEquals(TapZone.Left, TapZone.of(0f, 1000))
        assertEquals(TapZone.Left, TapZone.of(349f, 1000))
        assertEquals(TapZone.Centre, TapZone.of(350f, 1000))
        assertEquals(TapZone.Centre, TapZone.of(650f, 1000))
        assertEquals(TapZone.Right, TapZone.of(651f, 1000))
        assertEquals(TapZone.Right, TapZone.of(999f, 1000))
    }

    @Test
    fun theOldFortyThreePercentLineIsNowTheMiddle() {
        assertEquals(TapZone.Centre, TapZone.of(420f, 1000))
        assertEquals(TapZone.Centre, TapZone.of(580f, 1000))
    }

    @Test
    fun noWidthIsTheMiddle() {
        assertEquals(TapZone.Centre, TapZone.of(5f, 0))
    }
}
