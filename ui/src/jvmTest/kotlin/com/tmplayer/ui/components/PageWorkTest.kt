package com.tmplayer.ui.components

import com.tmplayer.data.WorkStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One status for a page from its asked refresh and its view model (WP-93): the asked refresh speaks
 * while it runs, a failure beats "Up to date", automatic work shows only when nothing was asked.
 */
class PageWorkTest {

    private val now = 1_800_000_000_000L
    private val failed = WorkStatus.Failed("", keptAt = now - 60_000)

    @Test
    fun `the asked refresh speaks while it runs or waits`() {
        assertEquals(PageWork(WorkStatus.Syncing(), true), pageWork(WorkStatus.Syncing(), failed, now))
        val wait = WorkStatus.RateLimited(now + 30_000)
        assertEquals(PageWork(wait, true), pageWork(wait, WorkStatus.Idle, now))
    }

    @Test
    fun `a failure or a partial answer beats up to date`() {
        assertEquals(PageWork(failed, true), pageWork(WorkStatus.Updated(now), failed, now))
        val partial = WorkStatus.Partial(3, 2)
        assertEquals(PageWork(partial, true), pageWork(WorkStatus.Updated(now), partial, now))
    }

    @Test
    fun `a failure that comes by itself, or long after the press, is not an asked one`() {
        assertEquals(PageWork(failed, false), pageWork(WorkStatus.Idle, failed, now))
        assertEquals(PageWork(failed, false), pageWork(WorkStatus.Updated(now), failed, now + FAILED_PILL_MS + 1))
    }

    @Test
    fun `a refresh asked offline says so by itself, with nothing kept`() {
        val offline = WorkStatus.Failed("You are offline.")
        assertEquals(PageWork(offline, true), pageWork(offline, WorkStatus.Idle, now))
    }

    @Test
    fun `up to date lasts two seconds then automatic work shows quietly`() {
        assertEquals(PageWork(WorkStatus.Updated(now), true), pageWork(WorkStatus.Updated(now), WorkStatus.Idle, now + 500))
        assertEquals(PageWork(WorkStatus.Idle, false), pageWork(WorkStatus.Updated(now), WorkStatus.Idle, now + 2_500))
        assertEquals(PageWork(WorkStatus.Syncing(), false), pageWork(WorkStatus.Updated(now), WorkStatus.Syncing(), now + 2_500))
        assertEquals(PageWork(WorkStatus.Syncing(), false), pageWork(WorkStatus.Idle, WorkStatus.Syncing(), now))
    }

    @Test
    fun `a time box failure stands only while the page still works`() {
        val boxed = WorkStatus.Failed("", keptAt = now)
        assertEquals(PageWork(boxed, true), pageWork(boxed, WorkStatus.Syncing(), now))
        assertEquals(PageWork(WorkStatus.Idle, false), pageWork(boxed, WorkStatus.Idle, now))
    }

    @Test
    fun `the connection pill holds the foot slot alone`() {
        val syncing = PageWork(WorkStatus.Syncing(), true)
        assertEquals(PageWork(WorkStatus.Idle, false), syncing.unlessConnectionShown(true))
        assertEquals(syncing, syncing.unlessConnectionShown(false))
    }

    @Test
    fun `failure only keeps what must still be said`() {
        assertEquals(failed, failed.failureOnly())
        assertEquals(WorkStatus.RateLimited(now), WorkStatus.RateLimited(now).failureOnly())
        assertEquals(WorkStatus.Idle, WorkStatus.Syncing().failureOnly())
        assertEquals(WorkStatus.Idle, WorkStatus.Partial(1, 1).failureOnly())
        assertEquals(WorkStatus.Idle, WorkStatus.Offline(now).failureOnly())
    }
}
