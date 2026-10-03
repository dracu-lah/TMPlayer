package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSpeedTest {

    @Test
    fun `stepping runs up the list and wraps`() {
        assertEquals(1.25f, PlaybackSpeed.next(1f))
        assertEquals(PlaybackSpeed.CHOICES.first(), PlaybackSpeed.next(PlaybackSpeed.CHOICES.last()))
    }

    @Test
    fun `a stored speed off the list snaps onto it`() {
        assertEquals(1.25f, PlaybackSpeed.sanitise(1.3f))
        assertEquals(PlaybackSpeed.DEFAULT, PlaybackSpeed.sanitise(0f))
        assertEquals(PlaybackSpeed.DEFAULT, PlaybackSpeed.sanitise(Float.NaN))
    }

    @Test
    fun `labels drop the noise`() {
        assertEquals("1x", PlaybackSpeed.label(1f))
        assertEquals("2x", PlaybackSpeed.label(2f))
        assertEquals("1.25x", PlaybackSpeed.label(1.25f))
        assertEquals("0.5x", PlaybackSpeed.label(0.5f))
    }
}
