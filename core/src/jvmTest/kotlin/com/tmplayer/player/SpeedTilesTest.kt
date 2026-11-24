package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP-30: 2.5x and 3x are tiles, the speed memory keeps them, and the cycle still wraps. */
class SpeedTilesTest {

    @Test
    fun `the tiles end at 2_5 and 3`() {
        assertEquals(listOf(2f, 2.5f, 3f), PlaybackSpeed.CHOICES.takeLast(3))
        assertEquals(9, PlaybackSpeed.CHOICES.size)
        assertTrue(PlaybackSpeed.CHOICES.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun `a stored 2_5 or 3 is kept and the pill walks through them and wraps`() {
        assertEquals(2.5f, PlaybackSpeed.sanitise(2.5f), 0f)
        assertEquals(3f, PlaybackSpeed.sanitise(3f), 0f)
        assertEquals(2.5f, PlaybackSpeed.next(2f), 0f)
        assertEquals(3f, PlaybackSpeed.next(2.5f), 0f)
        assertEquals(PlaybackSpeed.CHOICES.first(), PlaybackSpeed.next(3f), 0f)
    }
}
