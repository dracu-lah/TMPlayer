package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The playback stats overlay's words and its once a second limit (PR5). */
class PlaybackStatsTextTest {

    private fun readout(
        engine: String = "ExoPlayer",
        decoder: String = "c2.mtk.hevc.decoder",
        hardware: Boolean? = true,
        dropped: Int = 3,
        buffer: Long = 8_400,
        wait: Long = 35,
        speed: Float = 1f,
    ) = PlaybackStatsText.Readout(engine, decoder, hardware, dropped, buffer, wait, speed)

    @Test
    fun twoLinesCarryEveryFigure() {
        val lines = PlaybackStatsText.lines(readout())
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("ExoPlayer") && lines[0].contains("c2.mtk.hevc.decoder") && lines[0].contains("hardware"))
        assertTrue(lines[1].contains("Dropped 3"))
        assertTrue(lines[1].contains("Buffer 8.4 s"))
        assertTrue(lines[1].contains("Wait 35 ms"))
        assertTrue(lines[1].endsWith("1.0x"))
    }

    @Test
    fun softwareAndAnUnknownDecoderAreSaidOrLeftOut() {
        assertTrue(PlaybackStatsText.lines(readout(hardware = false))[0].contains("software"))
        val unknown = PlaybackStatsText.lines(readout(decoder = "", hardware = null))[0]
        assertEquals("ExoPlayer", unknown)
    }

    @Test
    fun garbageFiguresStayInRange() {
        val lines = PlaybackStatsText.lines(readout(dropped = -5, buffer = -1, wait = Long.MAX_VALUE, speed = Float.NaN))
        assertTrue(lines[1].contains("Dropped 0"))
        assertTrue(lines[1].contains("Buffer 0.0 s"))
        assertTrue(lines[1].contains("Wait 9999999 ms"))
        assertTrue(lines[1].endsWith("1.0x"))
        assertEquals("1.5x", PlaybackStatsText.speed(1.5f))
        assertEquals("1.25x", PlaybackStatsText.speed(1.25f))
        assertEquals("16.0x", PlaybackStatsText.speed(1000f))
        assertEquals("123", PlaybackStatsText.seconds(123_456))
        assertEquals("3600", PlaybackStatsText.seconds(Long.MAX_VALUE))
    }

    @Test
    fun aLongDecoderNameIsCut() {
        val lines = PlaybackStatsText.lines(readout(decoder = "x".repeat(500)))
        assertTrue(lines[0].length < 140)
    }

    @Test
    fun theThrottleLetsOneUpdateThroughASecond() {
        val gate = PlaybackStatsText.Throttle()
        assertTrue(gate.allow(10_000))
        assertFalse(gate.allow(10_300))
        assertFalse(gate.allow(10_999))
        assertTrue(gate.allow(11_000))
        // A clock that went backwards lets one through rather than hanging for ever.
        assertTrue(gate.allow(500))
        gate.reset()
        assertTrue(gate.allow(500))
    }
}
