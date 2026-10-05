package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StillWatchingTest {

    @Test
    fun `autoplay asks once three episodes have started on their own`() {
        assertFalse(StillWatching.askBeforeAutoplay(0))
        assertFalse(StillWatching.askBeforeAutoplay(2))
        assertTrue(StillWatching.askBeforeAutoplay(3))
        assertTrue(StillWatching.askBeforeAutoplay(7))
    }

    @Test
    fun `two hours without input asks, a minute less does not`() {
        assertFalse(StillWatching.askAfterIdle(StillWatching.IDLE_LIMIT_MS - 60_000))
        assertTrue(StillWatching.askAfterIdle(2 * 60 * 60 * 1000L))
    }

    @Test
    fun `sleep timer lengths read as a person would say them`() {
        assertEquals(
            listOf("15 minutes", "30 minutes", "45 minutes", "1 hour", "1 h 30 min", "End of this video"),
            SleepTimer.CHOICES.map(SleepTimer::label),
        )
        assertEquals("2 hours", SleepTimer.label(120))
    }

    @Test
    fun `what is left rounds up and never reads zero`() {
        assertEquals("1 minute left", SleepTimer.remaining(0))
        assertEquals("1 minute left", SleepTimer.remaining(59_000))
        assertEquals("2 minutes left", SleepTimer.remaining(61_000))
        assertEquals("30 minutes left", SleepTimer.remaining(30 * 60_000L))
    }
}
