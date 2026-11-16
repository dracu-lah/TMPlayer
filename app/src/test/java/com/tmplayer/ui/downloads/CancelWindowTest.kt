package com.tmplayer.ui.downloads

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Undo on a cancelled download (R2, 7.2): the window, its tokens and what ends it. */
@OptIn(ExperimentalCoroutinesApi::class)
class CancelWindowTest {

    @Test
    fun `a window ends with the part file dropped even when the page that began it is gone`() = runTest {
        val window = CancelWindow()
        val dropped = mutableListOf<Int>()
        val pageScope = TestScope(testScheduler)
        val processScope = TestScope(testScheduler)
        val token = window.begin(7)
        // The expiry is handed the process scope, so the page going away (rotation, leaving) does not touch it.
        window.schedule(processScope, 5_000, 7, token) { dropped += it }
        pageScope.cancel(CancellationException("rotated"))
        advanceTimeBy(4_999)
        runCurrent()
        assertTrue("still inside the window, card hidden", window.isPending(7))
        assertTrue(dropped.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(7), dropped)
        assertFalse(window.isPending(7))
    }

    @Test
    fun `an undone window drops nothing when its timer runs out`() = runTest {
        val window = CancelWindow()
        val dropped = mutableListOf<Int>()
        val token = window.begin(7)
        window.schedule(TestScope(testScheduler), 5_000, 7, token) { dropped += it }
        assertTrue(window.undo(7, token))
        advanceUntilIdle()
        assertTrue(dropped.isEmpty())
    }

    @Test
    fun `the card is let go even when the drop fails`() = runTest {
        val window = CancelWindow()
        val token = window.begin(7)
        val scope = TestScope(testScheduler)
        window.schedule(scope, 1, 7, token) { error("disk gone") }
        runCatching { advanceUntilIdle() }
        assertFalse(window.isPending(7))
    }


    @Test
    fun `a cancel opens a window that hides the download until it ends`() {
        val window = CancelWindow()
        assertTrue(window.pending.isEmpty())
        val token = window.begin(7)
        assertTrue(window.isPending(7))
        assertEquals(setOf(7), window.pending)
        assertFalse(window.isPending(8))
        assertTrue(window.isCurrent(7, token))
    }

    @Test
    fun `Undo ends the window once, and the timer then does nothing`() {
        val window = CancelWindow()
        val token = window.begin(7)
        assertTrue("the caller resumes the download", window.undo(7, token))
        assertFalse(window.isPending(7))
        // Undo twice, or the timer running out after it: nothing to do.
        assertFalse(window.undo(7, token))
        assertFalse(window.isCurrent(7, token))
        assertFalse(window.expire(7, token))
    }

    @Test
    fun `the timer ends the window and an Undo after it is too late`() {
        val window = CancelWindow()
        val token = window.begin(7)
        assertTrue(window.isCurrent(7, token))
        assertTrue(window.expire(7, token))
        assertFalse(window.isPending(7))
        assertFalse("the toast's Undo pressed after the part file went", window.undo(7, token))
    }

    @Test
    fun `an Undo and a second Cancel of the same download never end one another's window`() {
        val window = CancelWindow()
        val first = window.begin(7)
        assertTrue(window.undo(7, first))
        // Cancelled again straight away: a new window with its own token.
        val second = window.begin(7)
        assertFalse("the first timer is still running", window.isCurrent(7, first))
        assertFalse(window.expire(7, first))
        assertTrue("the second window is untouched", window.isPending(7))
        assertTrue(window.expire(7, second))
    }

    @Test
    fun `two downloads have windows of their own`() {
        val window = CancelWindow()
        val a = window.begin(1)
        val b = window.begin(2)
        assertEquals(setOf(1, 2), window.pending)
        assertTrue(window.undo(1, a))
        assertEquals(setOf(2), window.pending)
        assertTrue(window.expire(2, b))
        assertTrue(window.pending.isEmpty())
    }
}
