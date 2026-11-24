package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

/** Player volume (#33) and Rotate picture (#34): the figures, their ends and a damaged setting. */
class PlayerVolumeTest {

    @Test
    fun `a stored figure out of range or missing reads as full, never as silence`() {
        assertEquals(100, PlayerVolume.sanitise(null))
        assertEquals(100, PlayerVolume.sanitise(-1))
        assertEquals(100, PlayerVolume.sanitise(101))
        assertEquals(100, PlayerVolume.sanitise(Long.MIN_VALUE))
        assertEquals(100, PlayerVolume.sanitise(Long.MAX_VALUE))
        assertEquals(0, PlayerVolume.sanitise(0))
        assertEquals(37, PlayerVolume.sanitise(37))
    }

    @Test
    fun `a step is one per cent and holds at both ends`() {
        assertEquals(49, PlayerVolume.step(50, up = false))
        assertEquals(51, PlayerVolume.step(50, up = true))
        assertEquals(0, PlayerVolume.step(0, up = false))
        assertEquals(100, PlayerVolume.step(100, up = true))
        assertEquals(100, PlayerVolume.step(Int.MAX_VALUE, up = true))
        assertEquals(0, PlayerVolume.step(Int.MIN_VALUE, up = false))
        assertFalse(PlayerVolume.canStep(0, up = false))
        assertFalse(PlayerVolume.canStep(100, up = true))
        assertTrue(PlayerVolume.canStep(1, up = false))
    }

    @Test
    fun `the gain is cubic, silent at 0 and the file's own at 100`() {
        assertEquals(0f, PlayerVolume.gain(0), 0f)
        assertEquals(1f, PlayerVolume.gain(100), 0f)
        assertEquals(1f, PlayerVolume.gain(500), 0f)
        assertEquals(0f, PlayerVolume.gain(-20), 0f)
        // About -18 dB at half, about -60 dB at a tenth, as mpv's volume.
        assertEquals(-18.06, 20 * log10(PlayerVolume.gain(50).toDouble()), 0.1)
        assertEquals(-60.0, 20 * log10(PlayerVolume.gain(10).toDouble()), 0.1)
        // Every step down from full is quieter than the one before.
        for (percent in 1..100) assertTrue(PlayerVolume.gain(percent - 1) < PlayerVolume.gain(percent))
    }

    @Test
    fun `a turn is one of four, clockwise, whatever it is handed`() {
        assertEquals(listOf(0, 90, 180, 270), PictureRotation.CHOICES)
        assertEquals(0, PictureRotation.normalise(360))
        assertEquals(270, PictureRotation.normalise(-90))
        assertEquals(90, PictureRotation.normalise(450))
        assertEquals(90, PictureRotation.normalise(80))
        assertEquals(0, PictureRotation.normalise(320))
        assertTrue(PictureRotation.normalise(Int.MIN_VALUE) in PictureRotation.CHOICES)
        assertTrue(PictureRotation.normalise(Int.MAX_VALUE) in PictureRotation.CHOICES)
        assertEquals(0, PictureRotation.next(270))
        assertEquals(90, PictureRotation.next(0))
        // Media3 counts the other way round.
        assertEquals(270, PictureRotation.counterClockwise(90))
        assertEquals(0, PictureRotation.counterClockwise(0))
        assertEquals(180, PictureRotation.counterClockwise(180))
        assertTrue(PictureRotation.sideways(270))
        assertFalse(PictureRotation.sideways(180))
        assertEquals(9.0 / 16.0, PictureRotation.turnedAspect(16.0 / 9.0, 90), 1e-9)
        assertEquals(16.0 / 9.0, PictureRotation.turnedAspect(16.0 / 9.0, 180), 1e-9)
        assertEquals(0.0, PictureRotation.turnedAspect(0.0, 90), 0.0)
    }

    @Test
    fun `muted by M the engine gets no sound, whatever the volume, and unmuted the usual gain (PB-06)`() {
        assertEquals(0f, PlayerVolume.gain(PlayerVolume.FULL, muted = true))
        assertEquals(PlayerVolume.gain(70), PlayerVolume.gain(70, muted = false))
    }
}
