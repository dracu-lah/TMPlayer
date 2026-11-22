package com.tmplayer.desktop

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** The player pauses when the profile locks (HX88 low): the lock is a change from a profile to none. */
class PauseOnLockTest {
    private suspend fun run(vararg values: String?): Int {
        var paused = 0
        pauseWhenLocked(flowOf(*values)) { paused++ }
        return paused
    }

    @Test
    fun `a lock pauses once, an unlock or the first value never does`() = runBlocking {
        assertEquals(1, run("a", null))
        assertEquals(0, run(null))
        assertEquals(0, run(null, "a"))
        assertEquals(2, run("a", null, "a", null))
        assertEquals("a switch is a lock too", 1, run("a", null, "b"))
    }

    @Test
    fun `a pause that throws does not stop the watch`() = runBlocking {
        var tries = 0
        pauseWhenLocked(flowOf("a", null, "a", null)) {
            tries++
            error("engine gone")
        }
        assertEquals(2, tries)
    }
}
