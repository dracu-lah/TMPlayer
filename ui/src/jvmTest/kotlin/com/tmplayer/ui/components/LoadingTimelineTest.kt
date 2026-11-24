package com.tmplayer.ui.components

import com.tmplayer.data.NetworkStatus
import com.tmplayer.data.WorkStatus
import com.tmplayer.data.WorkStatusWords
import com.tmplayer.i18n.L
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP-102, the loading half of the suite: a fake Td timeline (answers at 0.3 s, 2 s, 12 s, never,
 * a failure after content, a 42 s flood wait, offline) played through the status ladder and the
 * words the status line shows, stepped on a plain counter in 100 ms steps. No clock, no sleep: the
 * ladder is arithmetic. Every screen that has a status line (chat list, Home, chat grid, Add
 * chats, profile switch, restore, Sync now) goes through this same ladder, so these rows are the
 * spec's "at 0.3 s no label, at 1.1 s a label, at 15 s the slow line, never a spinner for ever".
 */
class LoadingTimelineTest {

    private val now = 1_800_000_000_000L

    /** What is on screen at [ms] when a [work] started at 0 is answered at [answerAt] (null: never). */
    private fun shown(work: WorkStatus, asked: Boolean, ms: Long, answerAt: Long?, after: WorkStatus = WorkStatus.Idle): ShownWork =
        if (answerAt != null && ms >= answerAt) WorkLadder.at(after, asked, ms - answerAt) else WorkLadder.at(work, asked, ms)

    private val syncing = WorkStatus.Syncing(what = "Loading your chats: 312 so far")

    @Test
    fun `an answer at 0_3 s shows nothing, the viewer never sees a flash`() {
        for (ms in 0L..20_000L step 100) {
            assertFalse("at $ms", shown(syncing, asked = true, ms, answerAt = 300).visible)
        }
        // A pressed refresh says Up to date for 2 s after, and then it is gone.
        assertTrue(shown(syncing, true, 300, 300, WorkStatus.Updated(now)).visible)
        assertTrue(shown(syncing, true, 2_299, 300, WorkStatus.Updated(now)).visible)
        assertFalse(shown(syncing, true, 2_300, 300, WorkStatus.Updated(now)).visible)
        // An automatic one never says Up to date at all.
        for (ms in 0L..5_000L step 100) assertFalse(shown(syncing, false, ms, 300, WorkStatus.Updated(now)).visible)
    }

    @Test
    fun `an answer at 2 s shows the indicator at 0_4 s, its words from 1 s and is gone at 2 s`() {
        assertFalse(shown(syncing, true, 399, 2_000).visible)
        shown(syncing, true, 400, 2_000).let {
            assertTrue(it.visible)
            assertFalse("the indicator alone first", it.labelled)
        }
        assertFalse(shown(syncing, true, 999, 2_000).labelled)
        assertTrue("a label at 1.1 s", shown(syncing, true, 1_100, 2_000).labelled)
        assertTrue(shown(syncing, true, 1_999, 2_000).visible)
        assertFalse(shown(syncing, true, 2_000, 2_000).visible)
        // Automatic work answered in 2 s was never shown.
        for (ms in 0L..3_000L step 100) assertFalse("at $ms", shown(syncing, false, ms, 2_000).visible)
    }

    @Test
    fun `an answer at 12 s keeps its label the whole way and is not slow yet`() {
        for (ms in 1_100L..11_900L step 100) {
            val s = shown(syncing, true, ms, 12_000)
            assertTrue("at $ms", s.visible && s.labelled)
            assertFalse("at $ms", s.slow)
        }
        assertFalse(shown(syncing, true, 12_000, 12_000).visible)
        // The words are the caller's own count, said as they are.
        assertEquals("Loading your chats: 312 so far", WorkStatusWords.line(syncing, now))
    }

    @Test
    fun `no answer says slow at 15 s and then nothing changes, the ladder ends and nothing keeps ticking`() {
        assertFalse(shown(syncing, true, 14_900, null).slow)
        assertTrue(shown(syncing, true, 15_000, null).slow)
        assertTrue(shown(syncing, true, 600_000, null).slow)
        for (work in listOf(WorkStatus.Loading(), syncing)) {
            for (asked in listOf(true, false)) {
                var at = 0L
                var steps = 0
                while (true) {
                    at = WorkLadder.nextChangeMs(work, asked, at) ?: break
                    steps++
                    assertTrue("a ladder of at most three steps", steps <= 3)
                }
                assertEquals(if (work is WorkStatus.Syncing && !asked) 2 else 3, steps)
                assertEquals(WorkLadder.SLOW_AFTER_MS, at)
            }
        }
        assertEquals(15_000L, WorkLadder.SLOW_AFTER_MS)
    }

    @Test
    fun `a failure after content shows at once with the time kept and never turns into a spinner`() {
        val kept = now - 120_000
        val failed = WorkStatus.ofFailure("Timeout", now, keptAt = kept, network = NetworkStatus.Online)
        for (ms in listOf(0L, 100L, 5_000L, 15_000L, 600_000L)) {
            val s = WorkLadder.at(failed, asked = false, elapsedMs = ms)
            assertTrue(s.visible && s.labelled)
            assertFalse("a failure is never marked slow", s.slow)
        }
        assertNull("no timer runs for a failure", WorkLadder.nextChangeMs(failed, asked = false, elapsedMs = 0))
        assertEquals(L.stateFailedKept(time = L.messages.formatter.dateTime(kept)), WorkStatusWords.line(failed, now))
        // Try again: the same screen says Syncing again, asked, and the failure line is replaced.
        assertTrue(WorkLadder.at(syncing, asked = true, elapsedMs = 1_100).labelled)
    }

    @Test
    fun `a 42 s flood wait shows from the first moment and its seconds tick, ending by themselves`() {
        val limited = WorkStatus.ofFailure("FLOOD_WAIT_42", now) as WorkStatus.RateLimited
        var lastWords = ""
        var lines = 0
        for (t in 0L..60L) {
            val at = now + t * 1_000
            val s = WorkLadder.at(limited, asked = false, elapsedMs = t * 1_000)
            assertTrue(s.visible && s.labelled)
            val words = WorkStatusWords.line(limited, at)
            if (t in 0..41) {
                assertEquals(L.stateRateLimited(seconds = 42 - t.toInt()), words)
                assertTrue("words change at $t", words != lastWords)
                lines++
            } else {
                // After the wait the words stop at one second; the screen's status goes with the wait.
                assertEquals(L.stateRateLimited(seconds = 1), words)
            }
            lastWords = words
        }
        assertEquals(42, lines)
        assertNull(WorkLadder.nextChangeMs(limited, asked = false, elapsedMs = 0))
    }

    @Test
    fun `offline shows nothing in the line at any time, the offline banner says it`() {
        val offline = WorkStatus.ofFailure("Timeout", now, keptAt = now - 5_000, network = NetworkStatus.Offline)
        assertTrue(offline is WorkStatus.Offline)
        for (ms in listOf(0L, 400L, 1_000L, 15_000L, 3_600_000L)) {
            assertFalse(WorkLadder.at(offline, asked = true, elapsedMs = ms).visible)
        }
        assertNull(WorkLadder.nextChangeMs(offline, asked = true, elapsedMs = 0))
    }

    @Test
    fun `catching up after offline, Loading with no content has its label at 1 s and automatic Syncing waits 3 s`() {
        val loading = WorkStatus.Loading("Loading your chats")
        assertFalse(WorkLadder.at(loading, asked = false, elapsedMs = 399).visible)
        assertTrue(WorkLadder.at(loading, asked = false, elapsedMs = 400).visible)
        assertTrue(WorkLadder.at(loading, asked = false, elapsedMs = 1_000).labelled)
        assertEquals("Loading your chats", WorkStatusWords.line(loading, now))
        assertFalse(WorkLadder.at(syncing, asked = false, elapsedMs = 2_999).visible)
        assertTrue(WorkLadder.at(syncing, asked = false, elapsedMs = 3_000).labelled)
    }

    @Test
    fun `a known total is a count and an unknown one is never invented`() {
        val counted = WorkStatus.Syncing(what = "Adding chats", done = 3, total = 3_000)
        val line = WorkStatusWords.line(counted, now)
        assertTrue(line, line.startsWith("Adding chats") && line.contains("3,000"))
        assertEquals("Adding chats", WorkStatusWords.line(WorkStatus.Syncing(what = "Adding chats", done = 3), now))
        // A total smaller than the count is held to the total: never "3,001 of 3,000".
        val over = WorkStatusWords.line(WorkStatus.Syncing(what = "Adding chats", done = 9_999, total = 3_000), now)
        assertFalse(over, over.contains("9,999"))
    }
}
