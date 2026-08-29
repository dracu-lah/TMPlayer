package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SleepTimerTest {

    @Test
    fun `the cycle walks every step and comes back to off`() {
        val walked = generateSequence(SleepTimer.Off) { it.next() }
            .drop(1)
            .take(SleepTimer.entries.size)
            .toList()
        assertEquals(
            listOf(
                SleepTimer.Min15,
                SleepTimer.Min30,
                SleepTimer.Min45,
                SleepTimer.Min60,
                SleepTimer.Min90,
                SleepTimer.Off,
            ),
            walked,
        )
    }

    @Test
    fun `labels say what the press just chose`() {
        assertEquals("Sleep timer off", SleepTimer.Off.label)
        assertEquals("Sleep in 15 min", SleepTimer.Min15.label)
        assertEquals("Sleep in 45 min", SleepTimer.Min45.label)
        assertEquals("Sleep in 90 min", SleepTimer.Min90.label)
    }

    @Test
    fun `a step's deadline is its length from now`() {
        assertEquals(100_000L + 15 * 60_000L, SleepTimer.Min15.deadlineFrom(100_000L) ?: 0L)
        assertEquals(100_000L + 90 * 60_000L, SleepTimer.Min90.deadlineFrom(100_000L) ?: 0L)
    }

    @Test
    fun `off has no deadline at all`() {
        // Null rather than zero or "now", so an armed check can never mistake off for expired.
        assertNull(SleepTimer.Off.deadlineFrom(100_000L))
    }
}
