package com.tmplayer.data

import com.tmplayer.i18n.L
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared work status (WP-91): the merge order, the words per state and what garbage comes to. */
class WorkStatusTest {

    private val now = 1_800_000_000_000L

    private val failed = WorkStatus.Failed("Telegram didn't answer.", keptAt = now - 60_000)
    private val limited = WorkStatus.RateLimited(now + 42_000)
    private val offline = WorkStatus.Offline(now - 1_000)
    private val partial = WorkStatus.Partial(okCount = 5, failedCount = 2, what = "2 chats could not be read for now.")
    private val loading = WorkStatus.Loading("Loading your chats")
    private val syncing = WorkStatus.Syncing()
    private val updated = WorkStatus.Updated(now)

    /** Strongest first, as the 3.10 spec orders them. */
    private val order = listOf(failed, limited, offline, partial, loading, syncing, updated, WorkStatus.Idle)

    @Test
    fun `the stronger of two statuses wins whichever side it is on`() {
        for ((i, a) in order.withIndex()) {
            for (b in order.drop(i + 1)) {
                assertEquals("$a over $b", a, WorkStatus.merge(a, b))
                assertEquals("$a over $b, swapped", a, WorkStatus.merge(b, a))
            }
        }
    }

    @Test
    fun `Idle with Idle is Idle and a list of none is Idle`() {
        assertEquals(WorkStatus.Idle, WorkStatus.merge(WorkStatus.Idle, WorkStatus.Idle))
        assertEquals(WorkStatus.Idle, WorkStatus.merge(emptyList()))
        assertEquals(failed, WorkStatus.merge(listOf(syncing, failed, updated)))
    }

    @Test
    fun `two of a kind are joined into one line`() {
        val counts = WorkStatus.merge(partial, WorkStatus.Partial(okCount = 1, failedCount = 3))
        assertEquals(WorkStatus.Partial(6, 5, partial.what), counts)
        assertEquals(
            WorkStatus.Failed(failed.message, keptAt = now - 120_000),
            WorkStatus.merge(failed, WorkStatus.Failed("other", keptAt = now - 120_000)),
        )
        // One source kept nothing: the merged line never claims to show what was there.
        val halfKept = WorkStatus.merge(failed, WorkStatus.Failed("other", keptAt = null))
        assertEquals(WorkStatus.Failed("other", keptAt = null), halfKept)
        assertFalse(WorkStatusWords.line(halfKept, now).contains("Showing what was here"))
        assertEquals(WorkStatus.Failed("other", keptAt = null), WorkStatus.merge(WorkStatus.Failed("other", keptAt = null), failed))
        assertEquals(WorkStatus.Offline(null), WorkStatus.merge(offline, WorkStatus.Offline(null)))
        assertEquals(WorkStatus.RateLimited(now + 90_000), WorkStatus.merge(limited, WorkStatus.RateLimited(now + 90_000)))
        assertEquals(WorkStatus.Updated(now), WorkStatus.merge(WorkStatus.Updated(now - 5), updated))
        // A total is added only when both sides know theirs: never a guessed bar.
        assertEquals(WorkStatus.Syncing(done = 3, total = 10), WorkStatus.merge(WorkStatus.Syncing(done = 1, total = 4), WorkStatus.Syncing(done = 2, total = 6)))
        assertEquals(WorkStatus.Syncing(done = 3, total = null), WorkStatus.merge(WorkStatus.Syncing(done = 1, total = 4), WorkStatus.Syncing(done = 2)))
        // Counts that would overflow stay at the largest number.
        val huge = WorkStatus.merge(WorkStatus.Partial(Int.MAX_VALUE, Int.MAX_VALUE), WorkStatus.Partial(5, 5))
        assertEquals(WorkStatus.Partial(Int.MAX_VALUE, Int.MAX_VALUE), huge)
    }

    @Test
    fun `each state has its words`() {
        assertEquals("", WorkStatusWords.line(WorkStatus.Idle, now))
        assertEquals("Loading your chats", WorkStatusWords.line(loading, now))
        assertEquals(L.commonLoading, WorkStatusWords.line(WorkStatus.Loading(), now))
        assertEquals("Updating…", WorkStatusWords.line(syncing, now))
        assertEquals("Loading your chats: 312 so far", WorkStatusWords.line(WorkStatus.Syncing("Loading your chats: 312 so far", done = 312), now))
        assertEquals("Up to date", WorkStatusWords.line(updated, now))
        assertEquals(partial.what, WorkStatusWords.line(partial, now))
        assertEquals(L.chatsSomeNotLoaded, WorkStatusWords.line(WorkStatus.Partial(1, 1), now))
        val kept = WorkStatusWords.line(failed, now)
        assertTrue(kept, kept.startsWith("Could not update. Showing what was here at "))
        assertTrue(kept, kept.contains(L.messages.formatter.dateTime(now - 60_000)))
        assertEquals("Telegram didn't answer.", WorkStatusWords.line(WorkStatus.Failed("Telegram didn't answer."), now))
        assertEquals(Failures.DEFAULT, WorkStatusWords.line(WorkStatus.Failed(""), now))
        assertEquals(L.stateOffline, WorkStatusWords.line(offline, now))
        assertEquals("Telegram asked to wait. Trying again in 42 seconds", WorkStatusWords.line(limited, now))
        assertEquals("Telegram asked to wait. Trying again in 1 second", WorkStatusWords.line(WorkStatus.RateLimited(now + 400), now))
    }

    @Test
    fun `a known total is a count with the locale's grouping`() {
        val line = WorkStatusWords.line(WorkStatus.Syncing(done = 1_200, total = 20_000), now)
        assertEquals("Updating… 1,200 of 20,000", line)
        // A done past the total is read as the total, a total of zero as no total.
        assertEquals("Updating… 5 of 5", WorkStatusWords.line(WorkStatus.Syncing(done = 9, total = 5), now))
        assertEquals("Updating…", WorkStatusWords.line(WorkStatus.Syncing(done = 9, total = 0), now))
        assertEquals("Updating…", WorkStatusWords.line(WorkStatus.Syncing(done = -1, total = 5), now))
    }

    @Test
    fun `a kept time of zero or in the future is handled`() {
        // Zero: content is kept but there is no time to name, so the failure's own words.
        assertEquals("No answer", WorkStatusWords.line(WorkStatus.Failed("No answer", keptAt = 0), now))
        assertEquals("No answer", WorkStatusWords.line(WorkStatus.Failed("No answer", keptAt = -5), now))
        // In the future (a clock moved back): read as now, never a time that has not come.
        assertEquals(
            WorkStatusWords.line(WorkStatus.Failed("x", keptAt = now), now),
            WorkStatusWords.line(WorkStatus.Failed("x", keptAt = now + 86_400_000L * 400), now),
        )
    }

    @Test
    fun `a long wait is said in minutes and an ended one as one second`() {
        assertEquals(L.errorsFloodWait(wait = "5 minutes"), WorkStatusWords.line(WorkStatus.RateLimited(now + 300_000), now))
        assertEquals("Telegram asked to wait. Trying again in 1 second", WorkStatusWords.line(WorkStatus.RateLimited(now - 10_000), now))
        assertEquals(0, WorkStatus.RateLimited(now - 1).secondsLeft(now))
        assertEquals(1, WorkStatus.RateLimited(now + 1).secondsLeft(now))
        assertEquals(42, limited.secondsLeft(now))
        assertEquals(Int.MAX_VALUE, WorkStatus.RateLimited(now + 3_000_000_000_000L).secondsLeft(now))
        // A clock at either end of Long's range does not wrap round.
        assertEquals(Int.MAX_VALUE, limited.secondsLeft(Long.MIN_VALUE))
        assertEquals(0, limited.secondsLeft(Long.MAX_VALUE))
        assertEquals(Int.MAX_VALUE, WorkStatus.RateLimited(Long.MAX_VALUE).secondsLeft(Long.MIN_VALUE))
        assertNull(WorkStatus.rateLimited(60, Long.MAX_VALUE - 5))
    }

    @Test
    fun `a flood wait over ten seconds is a wait, and garbage is a plain failure`() {
        assertEquals(WorkStatus.RateLimited(now + 42_000), WorkStatus.ofFailure("FLOOD_WAIT_42", now))
        assertEquals(WorkStatus.RateLimited(now + 7_200_000), WorkStatus.ofFailure("Too Many Requests: retry after 7200", now))
        // Short enough to wait out quietly: the failure's words, which name the wait.
        val short = WorkStatus.ofFailure("FLOOD_WAIT_5", now, keptAt = now - 1)
        assertTrue(short is WorkStatus.Failed)
        assertEquals(now - 1, (short as WorkStatus.Failed).keptAt)
        for (garbage in listOf(null, "", "FLOOD_WAIT_", "FLOOD_WAIT_abc", "FLOOD_WAIT_-5", "FLOOD_WAIT_99999999999999", "FLOOD_WAIT_99999999", "retry after", "\u0000", "a".repeat(100_000))) {
            val status = WorkStatus.ofFailure(garbage, now)
            assertTrue("$garbage gave $status", status is WorkStatus.Failed)
            assertFalse((status as WorkStatus.Failed).message.isBlank())
        }
        assertNull(WorkStatus.rateLimited(10, now))
        assertNull(WorkStatus.rateLimited(0, now))
        assertNull(WorkStatus.rateLimited(-3, now))
        assertNull(WorkStatus.rateLimited(Int.MAX_VALUE, now))
        assertEquals(WorkStatus.RateLimited(now + 11_000), WorkStatus.rateLimited(11, now))
    }

    @Test
    fun `a failure of the line is Offline only when the device is settled offline`() {
        for (text in listOf("Timeout", "Connection closed", "Network is unreachable")) {
            assertEquals(text, WorkStatus.Offline(now - 1), WorkStatus.ofFailure(text, now, keptAt = now - 1, network = NetworkStatus.Offline))
            // Online or not known: the banner is not up, so the line says it, with Try again.
            for (network in listOf(NetworkStatus.Online, NetworkStatus.Unknown)) {
                assertEquals(WorkStatus.Failed(Failures.OFFLINE, keptAt = null), WorkStatus.ofFailure(text, now, network = network))
            }
        }
        // Not failures of the line, even offline: their own words, never the raw text.
        assertEquals(WorkStatus.Failed(L.errorsSignedOut), WorkStatus.ofFailure("Unauthorized: AUTH_KEY_UNREGISTERED", now, network = NetworkStatus.Offline))
        assertEquals(WorkStatus.Failed(Failures.DEFAULT), WorkStatus.ofFailure("SOMETHING_ODD_123", now, network = NetworkStatus.Offline))
        assertEquals(WorkStatus.RateLimited(now + 42_000), WorkStatus.ofFailure("FLOOD_WAIT_42", now, network = NetworkStatus.Offline))
        // A thrown I/O failure is one of the line whatever it says.
        assertEquals(WorkStatus.Offline(5), WorkStatus.ofFailure(java.io.IOException("socket reset"), now, keptAt = 5, network = NetworkStatus.Offline))
        assertEquals(WorkStatus.Failed(Failures.OFFLINE), WorkStatus.ofFailure(java.io.IOException("socket reset"), now))
        assertEquals(WorkStatus.Failed(Failures.DEFAULT), WorkStatus.ofFailure(IllegalStateException("x"), now))
        assertEquals(WorkStatus.Failed(Failures.DEFAULT), WorkStatus.ofFailure(null as Throwable?, now))
        assertTrue(Failures.isNetwork(" timeout "))
        assertFalse(Failures.isNetwork("FLOOD_WAIT_42"))
        assertFalse(Failures.isNetwork(null))
    }

    @Test
    fun `what is spoken leaves out Idle and Offline`() {
        assertEquals("", WorkStatusWords.spoken(WorkStatus.Idle, now))
        assertEquals("", WorkStatusWords.spoken(offline, now))
        assertEquals("Up to date", WorkStatusWords.spoken(updated, now))
    }
}
