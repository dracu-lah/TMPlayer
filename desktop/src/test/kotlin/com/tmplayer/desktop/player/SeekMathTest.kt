package com.tmplayer.desktop.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekMathTest {

    @Test
    fun `seeks stay inside the video`() {
        assertEquals(0L, SeekMath.clampSeek(3_000, -10_000, 100_000))
        assertEquals(99_000L, SeekMath.clampSeek(95_000, 10_000, 100_000))
        assertEquals(60_000L, SeekMath.clampSeek(50_000, 10_000, 100_000))
        // Unknown length: only the floor applies.
        assertEquals(500_000L, SeekMath.clampSeek(490_000, 10_000, 0))
    }

    @Test
    fun `tenths need a known length`() {
        assertNull(SeekMath.tenth(0, 5))
        assertEquals(0L, SeekMath.tenth(7_200_000, 0))
        assertEquals(3_600_000L, SeekMath.tenth(7_200_000, 5))
        assertEquals(6_480_000L, SeekMath.tenth(7_200_000, 9))
    }

    @Test
    fun `hover time maps the cursor onto the bar`() {
        assertEquals(0L, SeekMath.timeAt(-5f, 200f, 60_000))
        assertEquals(30_000L, SeekMath.timeAt(100f, 200f, 60_000))
        assertEquals(60_000L, SeekMath.timeAt(400f, 200f, 60_000))
        assertEquals(0L, SeekMath.timeAt(100f, 0f, 60_000))
    }

    @Test
    fun `volume is clamped`() {
        assertEquals(100, SeekMath.volume(98, 5))
        assertEquals(0, SeekMath.volume(3, -5))
        assertEquals(55, SeekMath.volume(50, 5))
    }

    @Test
    fun `the wheel turns volume, or seeks with shift`() {
        assertEquals(PlayerAction.VolumeBy(5), SeekMath.wheel(-1f, seek = false))
        assertEquals(PlayerAction.VolumeBy(-5), SeekMath.wheel(1f, seek = false))
        assertEquals(PlayerAction.SeekBy(10_000), SeekMath.wheel(-1f, seek = true))
        assertEquals(PlayerAction.SeekBy(-10_000), SeekMath.wheel(2f, seek = true))
        assertNull(SeekMath.wheel(0f, seek = false))
    }

    @Test
    fun `fine speed steps round and clamp`() {
        assertEquals(1.1f, SeekMath.fineSpeed(1f, up = true))
        assertEquals(0.9f, SeekMath.fineSpeed(1f, up = false))
        assertEquals(SeekMath.MAX_SPEED, SeekMath.fineSpeed(4f, up = true))
        assertEquals(SeekMath.MIN_SPEED, SeekMath.fineSpeed(0.3f, up = false))
        assertEquals("1.1x", SeekMath.speedLabel(1.1f))
        assertEquals("1.25x", SeekMath.speedLabel(1.25f))
        assertEquals("2x", SeekMath.speedLabel(2f))
        assertEquals(1.25f, SeekMath.nextSpeedStop(1f))
    }

    @Test
    fun `remaining time`() {
        assertEquals("-1:00", SeekMath.remaining(60_000, 120_000))
    }

    @Test
    fun `track cycling treats subtitles off as a stop`() {
        fun sub(id: Int, selected: Boolean) = MediaTrack(id, TrackType.Subtitle, null, "en", "ass", null, selected, false, false)
        val none = listOf(sub(1, false), sub(2, false))
        assertEquals(1, none.cycle(TrackType.Subtitle, forward = true)?.id)
        val first = listOf(sub(1, true), sub(2, false))
        assertEquals(2, first.cycle(TrackType.Subtitle, forward = true)?.id)
        assertNull(first.cycle(TrackType.Subtitle, forward = false))
        val last = listOf(sub(1, false), sub(2, true))
        assertNull(last.cycle(TrackType.Subtitle, forward = true))
    }

    @Test
    fun `wheel actions follow the wheel seeks setting`() {
        // Default: vertical is volume, Shift (vertical or the swapped horizontal delta) seeks.
        assertEquals(PlayerAction.VolumeBy(5), SeekMath.wheelAction(0f, -1f, shift = false, wheelSeeks = false))
        assertEquals(PlayerAction.SeekBy(-10_000), SeekMath.wheelAction(0f, 1f, shift = true, wheelSeeks = false))
        assertEquals(PlayerAction.SeekBy(-10_000), SeekMath.wheelAction(1f, 0f, shift = true, wheelSeeks = false))
        // The setting swaps them.
        assertEquals(PlayerAction.SeekBy(10_000), SeekMath.wheelAction(0f, -1f, shift = false, wheelSeeks = true))
        assertEquals(PlayerAction.VolumeBy(-5), SeekMath.wheelAction(1f, 0f, shift = true, wheelSeeks = true))
        // A real horizontal wheel always seeks, right going forward.
        assertEquals(PlayerAction.SeekBy(10_000), SeekMath.wheelAction(1f, 0f, shift = false, wheelSeeks = false))
        assertEquals(PlayerAction.SeekBy(-10_000), SeekMath.wheelAction(-1f, 0f, shift = false, wheelSeeks = true))
        assertNull(SeekMath.wheelAction(0f, 0f, shift = false, wheelSeeks = false))
    }
}
