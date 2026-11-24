package com.tmplayer.ui.components

import com.tmplayer.data.WorkStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The status line's timing ladder (3.10 spec C), as plain arithmetic: no clock, no Compose. */
class WorkLadderTest {

    private val syncing = WorkStatus.Syncing()

    @Test
    fun `an asked refresh shows nothing at 300 ms, the indicator at 400 ms, the words at 1 s`() {
        assertFalse(WorkLadder.at(syncing, asked = true, elapsedMs = 300).visible)
        WorkLadder.at(syncing, asked = true, elapsedMs = 400).let {
            assertTrue(it.visible)
            assertFalse(it.labelled)
        }
        assertTrue(WorkLadder.at(syncing, asked = true, elapsedMs = 1_000).labelled)
        assertFalse(WorkLadder.at(syncing, asked = true, elapsedMs = 14_999).slow)
        assertTrue(WorkLadder.at(syncing, asked = true, elapsedMs = 15_000).slow)
    }

    @Test
    fun `automatic work stays quiet for 3 s and never says Up to date`() {
        assertFalse(WorkLadder.at(syncing, asked = false, elapsedMs = 2_999).visible)
        assertTrue(WorkLadder.at(syncing, asked = false, elapsedMs = 3_000).labelled)
        assertFalse(WorkLadder.at(WorkStatus.Updated(1), asked = false, elapsedMs = 0).visible)
        assertNull(WorkLadder.nextChangeMs(WorkStatus.Updated(1), asked = false, elapsedMs = 0))
    }

    @Test
    fun `Up to date shows for 2 s after an asked refresh, then goes`() {
        assertTrue(WorkLadder.at(WorkStatus.Updated(1), asked = true, elapsedMs = 1_999).visible)
        assertFalse(WorkLadder.at(WorkStatus.Updated(1), asked = true, elapsedMs = 2_000).visible)
        assertEquals(2_000L, WorkLadder.nextChangeMs(WorkStatus.Updated(1), asked = true, elapsedMs = 0))
    }

    @Test
    fun `a failure shows at once, Offline and Idle never, and nothing runs for them`() {
        for (status in listOf(WorkStatus.Failed("x", 1), WorkStatus.Partial(1, 1), WorkStatus.RateLimited(5))) {
            assertTrue(WorkLadder.at(status, asked = false, elapsedMs = 0).labelled)
            assertNull(WorkLadder.nextChangeMs(status, asked = false, elapsedMs = 0))
        }
        for (status in listOf(WorkStatus.Idle, WorkStatus.Offline(1))) {
            assertFalse(WorkLadder.at(status, asked = true, elapsedMs = 60_000).visible)
            assertNull(WorkLadder.nextChangeMs(status, asked = true, elapsedMs = 0))
        }
    }

    @Test
    fun `the steps come in order and end`() {
        var at = 0L
        val steps = mutableListOf<Long>()
        while (true) {
            at = WorkLadder.nextChangeMs(WorkStatus.Loading(), asked = false, elapsedMs = at) ?: break
            steps += at
        }
        assertEquals(listOf(400L, 1_000L, 15_000L), steps)
    }
}
