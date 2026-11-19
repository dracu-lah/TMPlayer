package com.tmplayer.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** HX54: a thumbnail download waiting for a place is dropped without withdrawing anything. */
@OptIn(ExperimentalCoroutinesApi::class)
class ThumbnailSlotTest {

    @Test
    fun `a caller cancelled while queued withdraws nothing, one cancelled while running does`() = runTest {
        val gate = Semaphore(1)
        val withdrawn = mutableListOf<String>()
        val hold = CompletableDeferred<Unit>()
        val running = launch { Thumbnails.inSlot(gate, { withdrawn += "running" }) { hold.await() } }
        runCurrent()
        val queued = launch { Thumbnails.inSlot(gate, { withdrawn += "queued" }) { hold.await() } }
        runCurrent()
        queued.cancel()
        runCurrent()
        assertEquals(emptyList<String>(), withdrawn)
        running.cancel()
        runCurrent()
        assertEquals(listOf("running"), withdrawn)
        var ran = false
        Thumbnails.inSlot(gate, { }) { ran = true }
        assertEquals(true, ran)
    }
}
