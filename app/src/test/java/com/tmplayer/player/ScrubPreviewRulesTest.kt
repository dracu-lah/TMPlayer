package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrubPreviewRulesTest {

    @Test
    fun aNearbyMoveKeepsThePicture() {
        assertFalse(ScrubPreviewRules.farJump(600_000, 630_000, 7_200_000))
        // A twentieth of a two hour video is six minutes.
        assertFalse(ScrubPreviewRules.farJump(600_000, 950_000, 7_200_000))
        assertTrue(ScrubPreviewRules.farJump(600_000, 1_000_000, 7_200_000))
    }

    @Test
    fun aShortVideoStillAllowsTheFixedDistance() {
        assertFalse(ScrubPreviewRules.farJump(0, 80_000, 100_000))
        assertTrue(ScrubPreviewRules.farJump(0, 95_000, 100_000))
    }

    @Test
    fun noLengthOrNoPictureYet() {
        assertTrue(ScrubPreviewRules.farJump(-1, 0, 100_000))
        assertFalse(ScrubPreviewRules.farJump(10_000, 50_000, 0))
        assertTrue(ScrubPreviewRules.farJump(10_000, 200_000, -1))
    }

    @Test
    fun fractionAlongTheBar() {
        assertEquals(0.5f, ScrubPreviewRules.fraction(1_000, 0), 0f)
        assertEquals(0.5f, ScrubPreviewRules.fraction(1_000, -5), 0f)
        assertEquals(0.25f, ScrubPreviewRules.fraction(25, 100), 0f)
        assertEquals(1f, ScrubPreviewRules.fraction(500, 100), 0f)
        assertEquals(0f, ScrubPreviewRules.fraction(-500, 100), 0f)
    }

    @Test
    fun cardStaysInsideTheSafeArea() {
        // At the very start, with a cutout of 80 on the left.
        assertEquals(88f, ScrubPreviewRules.left(0f, 300, 2000, 8f, 80, 0), 0f)
        // At the very end, with a navigation bar of 120 on the right.
        assertEquals(2000f - 300 - 8 - 120, ScrubPreviewRules.left(2000f, 300, 2000, 8f, 0, 120), 0f)
        // In the middle, centred.
        assertEquals(850f, ScrubPreviewRules.left(1000f, 300, 2000, 8f, 0, 0), 0f)
        // A window narrower than the card.
        assertEquals(8f, ScrubPreviewRules.left(100f, 300, 200, 8f, 0, 0), 0f)
    }
}
