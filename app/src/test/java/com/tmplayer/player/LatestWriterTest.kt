package com.tmplayer.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** One writer for the remedy memory: saves never land out of order, and the newest always lands last. */
@OptIn(ExperimentalCoroutinesApi::class)
class LatestWriterTest {

    @Test
    fun quickSavesLandInOrderWithTheNewestLast() = runTest {
        val written = mutableListOf<Int>()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val writer = LatestWriter<Int>(scope) { value ->
            // A slow disk: the first write is still running while the next snapshots come.
            delay(if (value == 1) 500 else 10)
            written += value
        }
        scope.advanceUntilIdle()
        for (value in 1..5) writer.offer(value)
        scope.advanceUntilIdle()
        assertEquals(5, written.last())
        assertTrue("never out of order", written.zipWithNext().all { (a, b) -> a < b })
        writer.offer(6)
        scope.advanceUntilIdle()
        assertEquals(6, written.last())
    }

    @Test
    fun aFailedWriteDoesNotStopTheNext() = runTest {
        val written = mutableListOf<Int>()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val writer = LatestWriter<Int>(scope) { value ->
            if (value == 1) error("disk full")
            written += value
        }
        writer.offer(1)
        scope.advanceUntilIdle()
        writer.offer(2)
        scope.advanceUntilIdle()
        assertEquals(listOf(2), written)
    }
}
