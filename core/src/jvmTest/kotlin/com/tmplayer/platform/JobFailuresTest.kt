package com.tmplayer.platform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A background job that throws is logged and reported, and the scope carries on (HX10). */
class JobFailuresTest {

    private val reported = java.util.Collections.synchronizedList(mutableListOf<Throwable>())

    init {
        JobFailures.report = { reported += it }
    }

    @AfterTest
    fun reset() {
        JobFailures.report = null
    }

    @Test
    fun aFailedJobIsReportedAndItsSiblingsRunOn() = runBlocking {
        val scope = JobFailures.scope("test", Dispatchers.Default)
        val before = JobFailures.count
        scope.launch { error("TDLib answered something unexpected") }
        val sibling = scope.async {
            delay(50)
            "still here"
        }
        assertEquals("still here", withTimeout(5_000) { sibling.await() })
        withTimeout(5_000) { while (reported.isEmpty()) delay(5) }
        assertEquals("TDLib answered something unexpected", reported.single().message)
        assertTrue(JobFailures.count > before)
        // The scope still takes new work after the failure.
        assertEquals(2, withTimeout(5_000) { scope.async { 1 + 1 }.await() })
        scope.cancel()
    }

    @Test
    fun theSharedBackgroundScopeSurvivesAFailure() = runBlocking {
        Background.scope.launch { throw IllegalStateException("file gone") }
        withTimeout(5_000) { while (reported.none { it.message == "file gone" }) delay(5) }
        assertEquals(3, withTimeout(5_000) { Background.scope.async { 3 }.await() })
    }

    @Test
    fun cancellationIsNotAFailure() {
        val before = JobFailures.count
        JobFailures.handle("test", CancellationException("left the screen"))
        assertEquals(before, JobFailures.count)
        assertTrue(reported.isEmpty())
    }

    @Test
    fun aReporterThatThrowsIsIgnored() {
        JobFailures.report = { throw IllegalStateException("reporter down") }
        JobFailures.handle("test", RuntimeException("x"))
    }

    @Test
    fun aViewModelScopeWithoutAMainDispatcherStillContainsFailures() = runBlocking {
        val scope = JobFailures.viewModelScope("test")
        scope.launch(Dispatchers.Default) { throw IllegalArgumentException("bad page") }
        withTimeout(5_000) { while (reported.none { it.message == "bad page" }) delay(5) }
        scope.cancel()
    }
}
