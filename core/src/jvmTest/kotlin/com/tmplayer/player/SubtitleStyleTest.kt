package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleStyleTest {

    @Test
    fun `unknown or missing names fall back to the defaults`() {
        assertEquals(SubtitleStyle(), SubtitleStyle.from(null, null, null))
        assertEquals(SubtitleStyle(), SubtitleStyle.from("Huge", null, "Sideways"))
        assertEquals(
            SubtitleStyle(SubtitleSize.Large, box = true, position = SubtitlePosition.High),
            SubtitleStyle.from("Large", true, "High"),
        )
    }

    @Test
    fun `medium keeps the size both players used before the setting`() {
        assertEquals(0.065f, SubtitleSize.Medium.fraction, 0f)
        assertEquals(46, SubtitleSize.Medium.mpvFontSize)
    }

    @Test
    fun `size and position wrap`() {
        val style = SubtitleStyle(SubtitleSize.ExtraLarge, position = SubtitlePosition.High)
        assertEquals(SubtitleSize.Small, style.nextSize().size)
        assertEquals(SubtitlePosition.Bottom, style.nextPosition().position)
    }

    @Test
    fun `delays round trip and nothing is stored for none`() {
        assertNull(SyncDelays().encode())
        val delays = SyncDelays(subtitleMs = -300, audioMs = 1200)
        assertEquals(delays, SyncDelays.decode(delays.encode()))
        assertEquals(SyncDelays(), SyncDelays.decode("garbage"))
        assertEquals(SyncDelays(), SyncDelays.decode(null))
    }

    @Test
    fun `delays step by a tenth and stop at the limit`() {
        assertEquals(100L, SyncDelays.step(0, 1))
        assertEquals(-100L, SyncDelays.step(0, -1))
        assertEquals(SyncDelays.MAX_MS, SyncDelays.step(SyncDelays.MAX_MS, 1))
        assertEquals(-SyncDelays.MAX_MS, SyncDelays.decode("-99999,0").subtitleMs)
        assertEquals(300L, SyncDelays.clamp(251))
    }

    @Test
    fun `labels read as signed seconds`() {
        assertEquals("0 s", SyncDelays.label(0))
        assertEquals("+0.3 s", SyncDelays.label(300))
        assertEquals("-1.2 s", SyncDelays.label(-1200))
        assertEquals("+10.0 s", SyncDelays.label(10_000))
    }
}
