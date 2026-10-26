package com.tmplayer.ui.browse

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home's TDLib budget (HX8): one run per row at a time, retries that stop, a flood wait that holds up its row alone. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeFetchesTest {

    @Test
    fun `a row asked again while it runs runs once more, never more`() = runTest {
        val fetches = HomeFetches(backgroundScope, parallel = 3, clock = { testScheduler.currentTime })
        var runs = 0
        val fetch: suspend () -> HomeFetches.Outcome = {
            runs++
            delay(1_000)
            HomeFetches.Outcome.Done
        }
        repeat(10) { fetches.ask("chat:1", fresh = true, fetch = fetch) }
        advanceTimeBy(100)
        repeat(10) { fetches.ask("chat:1", fresh = true, fetch = fetch) }
        advanceTimeBy(120_000)
        assertEquals(2, runs)
        // Answered: asking without fresh does nothing.
        fetches.ask("chat:1", fetch = fetch)
        advanceTimeBy(120_000)
        assertEquals(2, runs)
    }

    @Test
    fun `a failing row is retried a few times and then left alone`() = runTest {
        val fetches = HomeFetches(backgroundScope, parallel = 3, clock = { testScheduler.currentTime })
        var runs = 0
        fetches.ask("chat:1") {
            runs++
            HomeFetches.Outcome.Failed()
        }
        advanceTimeBy(10 * 60_000)
        assertEquals(1 + HomeFetches.RETRY_WAITS_MS.size, runs)
        assertTrue(fetches.isFailed("chat:1"))
        assertTrue(!fetches.busy.value)
    }

    @Test
    fun `a flood wait holds up its own row while the others go on`() = runTest {
        val fetches = HomeFetches(backgroundScope, parallel = 1, clock = { testScheduler.currentTime })
        val doneAt = HashMap<String, Long>()
        var flooded = false
        fetches.ask("chat:1") {
            if (!flooded) {
                flooded = true
                HomeFetches.Outcome.of(IllegalStateException("Too Many Requests: retry after 30"))
            } else {
                doneAt["chat:1"] = testScheduler.currentTime
                HomeFetches.Outcome.Done
            }
        }
        listOf("chat:2", "chat:3").forEach { key ->
            fetches.ask(key) {
                delay(200)
                doneAt[key] = testScheduler.currentTime
                HomeFetches.Outcome.Done
            }
        }
        advanceTimeBy(120_000)
        assertTrue(doneAt.getValue("chat:2") < 1_000 && doneAt.getValue("chat:3") < 1_000)
        assertTrue(doneAt.getValue("chat:1") >= 30_000)
    }
}
