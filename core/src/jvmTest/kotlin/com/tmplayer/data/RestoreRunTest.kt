package com.tmplayer.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** WP-97: a restore runs once at a time, says it is running, and says the truth when it fails. */
class RestoreRunTest {

    @Test
    fun `a second apply while the first runs is ignored, the busy flag is true then false`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var runs = 0
        val first = async {
            RestoreRun.run {
                runs++
                gate.await()
            }
        }
        yield()
        assertTrue(RestoreRun.running.value)
        // The same confirm pressed again, and a third time: none starts a second restore.
        assertEquals(RestoreRun.Outcome.AlreadyRunning, RestoreRun.run { runs++ })
        assertEquals(RestoreRun.Outcome.AlreadyRunning, RestoreRun.run { runs++ })
        gate.complete(Unit)
        assertEquals(RestoreRun.Outcome.Done, first.await())
        assertEquals(1, runs)
        assertFalse(RestoreRun.running.value)
        // Free again afterwards.
        assertEquals(RestoreRun.Outcome.Done, RestoreRun.run { runs++ })
        assertEquals(2, runs)
    }

    @Test
    fun `a restore that throws says failed, never done, and frees the next one`() = runBlocking {
        assertEquals(RestoreRun.Outcome.Failed, RestoreRun.run { error("disk full") })
        assertFalse(RestoreRun.running.value)
        assertEquals(RestoreRun.Outcome.Done, RestoreRun.run { })
    }

    @Test
    fun `a cancelled restore frees the flag and passes the cancellation on`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val job = launch { RestoreRun.run { gate.await() } }
        yield()
        assertTrue(RestoreRun.running.value)
        job.cancel()
        job.join()
        assertFalse(RestoreRun.running.value)
        assertTrue(job.isCancelled)
    }
}
