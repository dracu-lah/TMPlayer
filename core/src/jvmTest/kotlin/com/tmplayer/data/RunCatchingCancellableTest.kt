package com.tmplayer.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunCatchingCancellableTest {

    @Test
    fun `a value comes back as a success`() = runTest {
        assertEquals(7, runCatchingCancellable { 7 }.getOrNull())
    }

    @Test
    fun `an ordinary failure is caught`() = runTest {
        val result = runCatchingCancellable<Int> { error("no") }
        assertNull(result.getOrNull())
        assertTrue(result.isFailure)
    }

    @Test
    fun `cancellation is rethrown and the loop around it stops`() = runTest {
        var asked = 0
        val job = launch {
            while (true) {
                runCatchingCancellable { delay(1_000); asked++ }.getOrNull()
            }
        }
        advanceTimeBy(2_500)
        runCurrent()
        job.cancel()
        job.join()
        val before = asked
        advanceTimeBy(10_000)
        assertEquals(before, asked)
        assertTrue(job.isCancelled)
    }

    @Test
    fun `a cancellation exception thrown inside is not a failure`() = runTest {
        var caught = false
        try {
            runCatchingCancellable<Int> { throw CancellationException("gone") }
        } catch (e: CancellationException) {
            caught = true
        }
        assertTrue(caught)
    }
}
