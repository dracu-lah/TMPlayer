package com.tmplayer.data

import com.tmplayer.i18n.L
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three failures rule (WP-97, WP-100): a sync that failed three runs in a row stops running by
 * itself, says so with Try again and Send logs, and a run that goes through clears it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncStreakTest {

    private fun status(kind: SyncStatus.Kind, failures: Int = 0, detail: String = "") = SyncStatus(kind, at = 1L, detail = detail, failures = failures)

    /** What the store would hold after [kinds] ran one after the other. */
    private fun after(vararg kinds: SyncStatus.Kind): SyncStatus? {
        var held: SyncStatus? = null
        for (kind in kinds) {
            val now = status(kind)
            held = now.copy(failures = SyncStreak.next(held, now))
        }
        return held
    }

    @Test
    fun `three failed runs in a row stop it, two do not`() {
        val failed = SyncStatus.Kind.Failed
        assertFalse(SyncStreak.stopped(after(failed, failed)))
        assertTrue(SyncStreak.stopped(after(failed, failed, failed)))
        assertEquals(4, after(failed, failed, failed, failed)?.failures)
    }

    @Test
    fun `a run that went through puts the count back`() {
        val failed = SyncStatus.Kind.Failed
        val held = after(failed, failed, SyncStatus.Kind.Done, failed, failed)
        assertEquals(2, held?.failures)
        assertFalse(SyncStreak.stopped(held))
    }

    @Test
    fun `a run that did not try neither adds nor clears`() {
        val failed = SyncStatus.Kind.Failed
        val held = after(failed, failed, SyncStatus.Kind.NoConnection, SyncStatus.Kind.WaitingForUnmetered, SyncStatus.Kind.WaitingForFile, failed)
        assertEquals(3, held?.failures)
        assertTrue(SyncStreak.stopped(held))
        // And stays stopped through an offline try of the viewer's, until one goes through.
        val offline = after(failed, failed, failed, SyncStatus.Kind.NoConnection)
        assertTrue(SyncStreak.stopped(offline))
    }

    @Test
    fun `a file that needs the viewer is its own line and starts the count again`() {
        val failed = SyncStatus.Kind.Failed
        assertEquals(0, after(failed, failed, SyncStatus.Kind.Unreadable)?.failures)
        assertEquals(0, after(failed, SyncStatus.Kind.TooNew)?.failures)
        assertEquals(0, after(failed, SyncStatus.Kind.OtherAccount)?.failures)
    }

    @Test
    fun `no status yet is not stopped, and a garbage count is capped`() {
        assertFalse(SyncStreak.stopped(null))
        assertEquals(1, SyncStreak.next(null, status(SyncStatus.Kind.Failed)))
        assertEquals(SyncStreak.COUNT_MAX, SyncStreak.next(status(SyncStatus.Kind.Failed, failures = 5_000), status(SyncStatus.Kind.Failed)))
    }

    @Test
    fun `the count survives the stored line, and an older line reads as no failures`() {
        val line = status(SyncStatus.Kind.Failed, failures = 3, detail = "no room").encode()
        assertEquals(3, SyncStatus.decode(line)?.failures)
        // A line written before the count existed: five parts.
        val old = line.substringBeforeLast('\u001F')
        assertEquals(0, SyncStatus.decode(old)?.failures)
        // Wrong type or negative or absurd: no failures, or the cap.
        assertEquals(0, SyncStatus.decode("$old\u001Fnot a number")?.failures)
        assertEquals(0, SyncStatus.decode("$old\u001F-4")?.failures)
        assertEquals(SyncStreak.COUNT_MAX, SyncStatus.decode("$old\u001F99999")?.failures)
        assertNull(SyncStatus.decode(""))
    }

    @Test
    fun `the corner says Sync stopped with the way out, once per day, and only after the third`() {
        fun decide(stopped: Boolean, dismissed: Boolean = false, kind: SyncStatus.Kind? = SyncStatus.Kind.Failed) =
            GlobalStatusRules.decide(TelegramLink.Ready, 0, 0, kind, dismissed, syncStopped = stopped)
        assertEquals(GlobalLine.Idle, decide(stopped = false))
        assertEquals(GlobalLine.SyncNeedsYou(SyncStatus.Kind.Failed), decide(stopped = true))
        // Whatever the last run said (the viewer's own try found no connection), it stays up.
        assertEquals(GlobalLine.SyncNeedsYou(SyncStatus.Kind.Failed), decide(stopped = true, kind = SyncStatus.Kind.NoConnection))
        // Closed today: it stays closed.
        assertEquals(GlobalLine.Idle, decide(stopped = true, dismissed = true))
        // Filed as Failed for the once a day registry, whatever the last run said.
        assertEquals(SyncStatus.Kind.Failed, GlobalStatusRules.hintKind(status(SyncStatus.Kind.NoConnection, failures = 3)))
        assertEquals(SyncStatus.Kind.NoConnection, GlobalStatusRules.hintKind(status(SyncStatus.Kind.NoConnection, failures = 1)))
        assertNull(GlobalStatusRules.hintKind(null))
    }

    @Test
    fun `the words say it stopped and why`() {
        val stopped = SyncWords.status(status(SyncStatus.Kind.Failed, failures = 3, detail = "channel refuses"), 0L, false)
        assertTrue(stopped.startsWith(L.settingsResumeSyncStopped), stopped)
        assertTrue(stopped.contains("channel refuses"), stopped)
        val plain = SyncWords.status(status(SyncStatus.Kind.Failed, failures = 2, detail = "x"), 0L, false)
        assertEquals(L.settingsResumeSyncFailed("x"), plain)
    }

    @Test
    fun `the scheduler leaves what nobody asked for alone while stopped, and still runs the viewer's own`() = runTest {
        var stopped = true
        val ran = mutableListOf<SyncScheduler.Why>()
        val scheduler = SyncScheduler(
            scope = backgroundScope,
            clock = { testScheduler.currentTime + 1_800_000_000_000L },
            foreground = { true },
            lowEnd = false,
            lastRunAt = { 0L },
            job = { why -> ran += why; true },
            stopped = { stopped },
        )
        scheduler.ask(SyncScheduler.Why.Start)
        scheduler.ask(SyncScheduler.Why.Settled)
        scheduler.ask(SyncScheduler.Why.LeftPlayer)
        advanceTimeBy(5 * 60_000L)
        runCurrent()
        assertTrue(ran.isEmpty(), "nothing runs by itself: $ran")
        scheduler.ask(SyncScheduler.Why.Manual)
        runCurrent()
        assertEquals(listOf(SyncScheduler.Why.Manual), ran)
        scheduler.ask(SyncScheduler.Why.TurnedOn)
        runCurrent()
        assertEquals(2, ran.size)
        // A run that went through lifts it.
        stopped = false
        scheduler.ask(SyncScheduler.Why.Settled)
        advanceTimeBy(5 * 60_000L)
        runCurrent()
        assertEquals(3, ran.size)
    }
}
