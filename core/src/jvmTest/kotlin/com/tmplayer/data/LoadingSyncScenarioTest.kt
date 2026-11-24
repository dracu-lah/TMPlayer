package com.tmplayer.data

import com.tmplayer.i18n.L
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * WP-102, the sync half of the loading and sync machine suite: whole timelines on a virtual clock
 * (no real sleep anywhere) through the pieces WP-91 to WP-101 built, so a later change cannot
 * bring back a silent sync or a corner that never goes away. The single rules have their own tests
 * ([GlobalStatusTest], [SyncStreakTest], [RestoreRunTest], [WorkStatusTest]); this checks that
 * they hold together: the corner's countdown from a 42 s flood wait, catching up from the first
 * second to "slow", a sync that fails three runs in a row, stops, says so once a day, is asked
 * again by the viewer and ends in a run that goes through, a restore pressed ten times, and the
 * failure kinds a screen shows after content. Prints nothing; every number asserted is a spec number.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoadingSyncScenarioTest {

    private val epoch = 1_800_000_000_000L

    /** The corner exactly as the host derives it each second ([com.tmplayer.ui.components.rememberGlobalLine]). */
    private fun corner(
        link: TelegramLink = TelegramLink.Ready,
        updatingForMs: Long = 0,
        waitUntil: Long = 0,
        now: Long = epoch,
        sync: SyncStatus? = null,
        dismissed: Boolean = false,
    ): GlobalLine {
        val waitSeconds = ((waitUntil - now + 999) / 1000).toInt().coerceAtLeast(0)
        return GlobalStatusRules.decide(
            link = link,
            updatingForMs = updatingForMs,
            waitSeconds = waitSeconds,
            sync = sync?.kind,
            syncDismissed = dismissed,
            syncStopped = SyncStreak.stopped(sync),
        )
    }

    // ---- the corner: flood wait and catching up -------------------------------------------------

    @Test
    fun `a 42 second flood wait counts 42 down to 11, goes quiet at 10 and ends by itself`() {
        val limited = WorkStatus.rateLimited(42, epoch) ?: error("42 s is a wait to show")
        val shown = mutableListOf<Int>()
        var lastWords = ""
        for (t in 0..60) {
            val now = epoch + t * 1_000L
            val line = corner(waitUntil = limited.untilMs, now = now)
            // The screen's status line and the corner read the same seconds.
            assertEquals(maxOf(0, 42 - t), limited.secondsLeft(now), "t=$t")
            if (line is GlobalLine.RateLimited) {
                assertEquals(42 - t, line.seconds, "t=$t")
                shown += line.seconds
                val words = WorkStatusWords.line(limited, now)
                assertEquals(L.stateRateLimited(seconds = 42 - t), words)
                assertNotEquals(lastWords, words, "the words tick at t=$t")
                lastWords = words
                assertTrue(GlobalStatusRules.ticks(line, TelegramLink.Ready), "t=$t ticks while shown")
            } else {
                assertEquals(GlobalLine.Idle, line, "t=$t")
                assertFalse(GlobalStatusRules.ticks(line, TelegramLink.Ready), "t=$t nothing ticks once it is gone")
            }
        }
        // 42 down to 11 is 32 lines, then quiet: it ends by itself, no longer than the wait.
        assertEquals((42 downTo 11).toList(), shown)
        // Spoken once at the start, not again as the seconds tick.
        assertEquals(L.stateRateLimited(seconds = 42), WorkStatusWords.spoken(limited, epoch))
    }

    @Test
    fun `a wait of 10 seconds or less never shows a line at all`() {
        for (seconds in listOf(1, 5, 10)) {
            assertEquals(null, WorkStatus.rateLimited(seconds, epoch), "$seconds")
            for (t in 0..seconds) assertEquals(GlobalLine.Idle, corner(waitUntil = epoch + seconds * 1_000L, now = epoch + t * 1_000L), "$seconds at $t")
        }
    }

    @Test
    fun `catching up shows nothing for 2 s, Updating until 60 s, then slow, and Ready ends it at any moment`() {
        val seen = mutableListOf<GlobalLine>()
        for (ms in 0..90_000 step 100) {
            val line = corner(TelegramLink.Updating, updatingForMs = ms.toLong())
            when {
                ms < 2_000 -> assertEquals(GlobalLine.Idle, line, "$ms ms")
                ms < 60_000 -> assertEquals(GlobalLine.Updating, line, "$ms ms")
                else -> assertEquals(GlobalLine.SlowAnswer, line, "$ms ms")
            }
            if (seen.lastOrNull() != line) seen += line
            // The moment it is Ready the corner is empty and nothing ticks.
            assertEquals(GlobalLine.Idle, corner(TelegramLink.Ready, updatingForMs = ms.toLong()))
            // It looks again a second later only while a threshold is still ahead.
            assertEquals(line != GlobalLine.SlowAnswer, GlobalStatusRules.ticks(line, TelegramLink.Updating), "$ms ms")
        }
        assertEquals(listOf(GlobalLine.Idle, GlobalLine.Updating, GlobalLine.SlowAnswer), seen)
    }

    @Test
    fun `a wait that arrives during a slow catch up wins, then the corner goes back to slow`() {
        val until = epoch + 30_000
        val during = corner(TelegramLink.Updating, updatingForMs = 70_000, waitUntil = until, now = epoch)
        assertEquals(GlobalLine.RateLimited(30), during)
        assertEquals(GlobalLine.RateLimited(11), corner(TelegramLink.Updating, 89_000, until, epoch + 19_000))
        assertEquals(GlobalLine.SlowAnswer, corner(TelegramLink.Updating, 90_000, until, epoch + 20_000))
        assertEquals(GlobalLine.Idle, corner(TelegramLink.Ready, 90_000, until, epoch + 20_000))
    }

    // ---- the sync: three failures, stop, once a day, the viewer's own run ------------------------

    /** A sync whose runs come from a script of outcomes, with the streak kept as [ResumeSync] keeps it. */
    private class SyncRig(scope: TestScope, script: List<ResumeSync.Outcome>) {
        private val queue = ArrayDeque(script)
        var held: SyncStatus? = null
        var lastRun = 0L
        val ran = mutableListOf<SyncScheduler.Why>()
        val scheduler = SyncScheduler(
            scope = scope.backgroundScope,
            clock = { scope.testScheduler.currentTime + EPOCH },
            foreground = { true },
            lowEnd = false,
            lastRunAt = { lastRun },
            job = { why ->
                ran += why
                val status = ResumeSync.statusOf(queue.removeFirst(), now = scope.testScheduler.currentTime + EPOCH)
                held = status.copy(failures = SyncStreak.next(held, status))
                true
            },
            onRan = { lastRun = it },
            stopped = { SyncStreak.stopped(held) },
        )

        companion object {
            const val EPOCH = 1_800_000_000_000L
        }
    }

    private val failed = ResumeSync.Outcome.Failed("channel refuses")
    private val done = ResumeSync.Outcome.Done(received = 2, wrote = true, dropped = 0)
    private val minute = 60_000L

    private suspend fun TestScope.settleAndWait(rig: SyncRig, why: SyncScheduler.Why = SyncScheduler.Why.Settled) {
        rig.scheduler.ask(why)
        advanceTimeBy(SyncScheduler.MIN_GAP_MS + why.settleMs + 1_000)
        runCurrent()
    }

    @Test
    fun `three failed runs in a row stop it, it says so once a day, the viewer's run goes, a run that goes through ends it`() = runTest {
        val rig = SyncRig(this, listOf(failed, failed, failed, failed, ResumeSync.Outcome.NoConnection, done, done))
        var hints = HintState()
        fun cornerNow(): GlobalLine = corner(sync = rig.held, dismissed = rig.held?.let { hints.seen(syncHint(it)) } ?: false)

        // Run 1 at start, 2 and 3 after the few-minutes gap: each says its reason, none is a line yet.
        rig.scheduler.ask(SyncScheduler.Why.Start)
        advanceTimeBy(SyncScheduler.START_DELAY_MS + 1)
        runCurrent()
        assertEquals(1, rig.held?.failures)
        assertEquals(GlobalLine.Idle, cornerNow())
        assertEquals(L.settingsResumeSyncFailed("channel refuses"), SyncWords.status(rig.held, 0L, false))
        settleAndWait(rig)
        assertEquals(2, rig.held?.failures)
        assertEquals(GlobalLine.Idle, cornerNow())
        settleAndWait(rig)
        assertEquals(3, rig.ran.size)

        // The third stops it: the corner says so, the Settings row says why.
        assertTrue(SyncStreak.stopped(rig.held))
        assertEquals(GlobalLine.SyncNeedsYou(SyncStatus.Kind.Failed), cornerNow())
        assertEquals(L.settingsResumeSyncStopped + " channel refuses", SyncWords.status(rig.held, 0L, false))

        // Once per day: closing it files the hint, the same day stays quiet, the next day asks again.
        hints = HintRegistry.raise(hints, syncHint(rig.held!!))
        assertEquals(GlobalLine.Idle, cornerNow())
        assertNotEquals(syncHint(rig.held!!), syncHint(rig.held!!, day = "20261125"))

        // Nothing by itself for an hour: every kind of ask that nobody pressed is left alone.
        repeat(12) {
            rig.scheduler.ask(SyncScheduler.Why.Settled)
            rig.scheduler.ask(SyncScheduler.Why.LeftPlayer)
            rig.scheduler.ask(SyncScheduler.Why.Start)
            advanceTimeBy(5 * minute)
            runCurrent()
        }
        assertEquals(3, rig.ran.size, "still stopped: ${rig.ran}")

        // Sync now: runs at once, fails again, stays stopped and the count keeps counting.
        rig.scheduler.ask(SyncScheduler.Why.Manual)
        runCurrent()
        assertEquals(4, rig.ran.size)
        assertEquals(4, rig.held?.failures)
        assertTrue(SyncStreak.stopped(rig.held))

        // A try by the viewer with no connection neither clears it nor adds to it.
        rig.scheduler.ask(SyncScheduler.Why.Manual)
        runCurrent()
        assertEquals(SyncStatus.Kind.NoConnection, rig.held?.kind)
        assertEquals(4, rig.held?.failures)
        assertTrue(SyncStreak.stopped(rig.held))
        assertEquals(SyncStatus.Kind.Failed, GlobalStatusRules.hintKind(rig.held))
        assertEquals(syncHint(rig.held!!), GlobalStatusRules.syncHintId(SyncStatus.Kind.Failed, "20261124"))

        // A run that goes through: the count is nothing, the corner is empty and the background resumes.
        rig.scheduler.ask(SyncScheduler.Why.Manual)
        runCurrent()
        assertEquals(0, rig.held?.failures)
        assertFalse(SyncStreak.stopped(rig.held))
        assertEquals(GlobalLine.Idle, corner(sync = rig.held))
        settleAndWait(rig)
        assertEquals(7, rig.ran.size)
        assertEquals(SyncStatus.Kind.Done, rig.held?.kind)
    }

    private fun syncHint(status: SyncStatus, day: String = "20261124"): String =
        GlobalStatusRules.syncHintId(GlobalStatusRules.hintKind(status) ?: status.kind, day)

    @Test
    fun `two failures with a run that did not try between them still count, and a good run in between does not`() = runTest {
        val counting = SyncRig(this, listOf(failed, ResumeSync.Outcome.NoConnection, ResumeSync.Outcome.WaitingForUnmetered, failed, failed))
        repeat(5) { settleAndWait(counting) }
        assertEquals(5, counting.ran.size)
        assertTrue(SyncStreak.stopped(counting.held), "3 failures with two waits between: ${counting.held}")
        val reset = SyncRig(this, listOf(failed, failed, done, failed, failed))
        repeat(5) { settleAndWait(reset) }
        assertEquals(2, reset.held?.failures)
        assertFalse(SyncStreak.stopped(reset.held))
    }

    @Test
    fun `a sync that needs you is its own line and never counts as a failed try`() = runTest {
        for ((outcome, kind) in listOf(
            ResumeSync.Outcome.Unreadable to SyncStatus.Kind.Unreadable,
            ResumeSync.Outcome.TooNew(version = 9) to SyncStatus.Kind.TooNew,
            ResumeSync.Outcome.OtherAccount to SyncStatus.Kind.OtherAccount,
        )) {
            val rig = SyncRig(this, listOf(failed, failed, outcome))
            repeat(3) { settleAndWait(rig) }
            assertEquals(kind, rig.held?.kind)
            assertEquals(0, rig.held?.failures, "$kind starts the count again")
            assertEquals(GlobalLine.SyncNeedsYou(kind), corner(sync = rig.held), "$kind")
            // The words are the sync's own, never the raw reason.
            assertTrue(SyncWords.status(rig.held, 0L, false).isNotBlank())
            // Once the viewer has seen today's line, it stays gone.
            assertEquals(GlobalLine.Idle, corner(sync = rig.held, dismissed = true), "$kind")
            // The viewer's run that goes through clears it.
            val ok = SyncRig(this, listOf(outcome, done))
            repeat(2) { settleAndWait(ok) }
            assertEquals(GlobalLine.Idle, corner(sync = ok.held))
        }
    }

    @Test
    fun `a sync stored by an older build reads as no failures and a stored count survives a restart`() {
        val older = listOf("failed", "5", "0", "0", "x").joinToString("\u001F")
        assertEquals(0, SyncStatus.decode(older)?.failures)
        val stored = SyncStatus(SyncStatus.Kind.Failed, at = 7, detail = "x", failures = 3)
        val back = SyncStatus.decode(stored.encode())
        assertEquals(stored, back)
        assertTrue(SyncStreak.stopped(back))
        // Garbage in the stored count is held to its limits, never a crash.
        val garbage = listOf("failed", "5", "0", "0", "x", "999999999999").joinToString("\u001F")
        assertEquals(0, SyncStatus.decode(garbage)?.failures)
        val huge = listOf("failed", "5", "0", "0", "x", "500").joinToString("\u001F")
        assertEquals(SyncStreak.COUNT_MAX, SyncStatus.decode(huge)?.failures)
    }

    // ---- restore: busy, once, honest ------------------------------------------------------------

    @Test
    fun `ten presses of the confirm run one restore, a failed one says failed and the next one runs`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var runs = 0
        val presses = (1..10).map { async { RestoreRun.run { runs++; gate.await() } } }
        runCurrent()
        assertTrue(RestoreRun.running.value, "the spinner shows for the whole run")
        gate.complete(Unit)
        val outcomes = presses.awaitAll()
        assertEquals(1, runs)
        assertEquals(1, outcomes.count { it == RestoreRun.Outcome.Done })
        assertEquals(9, outcomes.count { it == RestoreRun.Outcome.AlreadyRunning })
        assertFalse(RestoreRun.running.value, "the spinner is gone when it ends")

        assertEquals(RestoreRun.Outcome.Failed, RestoreRun.run { error("cut short") })
        assertFalse(RestoreRun.running.value, "a failure frees the next press")
        assertEquals(RestoreRun.Outcome.Done, RestoreRun.run { runs++ })
        assertEquals(2, runs)
    }

    // ---- failures on a screen that has content ----------------------------------------------------

    @Test
    fun `a failure after content keeps it with the time, a flood wait is a countdown, offline is the banner's`() {
        val kept = epoch - 90_000
        val failure = WorkStatus.ofFailure("Timeout", epoch, keptAt = kept, network = NetworkStatus.Online)
        assertEquals(WorkStatus.Failed(Failures.OFFLINE, kept), failure)
        assertEquals(L.stateFailedKept(time = L.messages.formatter.dateTime(kept)), WorkStatusWords.line(failure, epoch))
        // Two sources on one page: the failure wins the line while the other still syncs, and the
        // oldest kept time is the one named.
        val other = WorkStatus.Failed("x", keptAt = epoch - 600_000)
        assertEquals(WorkStatus.Failed(Failures.OFFLINE, epoch - 600_000), WorkStatus.merge(failure, other))
        assertEquals(failure, WorkStatus.merge(listOf(WorkStatus.Syncing(), failure, WorkStatus.Idle)))

        val wait = WorkStatus.ofFailure("FLOOD_WAIT_42", epoch, keptAt = kept)
        assertEquals(WorkStatus.RateLimited(epoch + 42_000), wait)
        // The wait runs out by itself: after it, a status built from the clock says one second at most.
        assertEquals(L.stateRateLimited(seconds = 1), WorkStatusWords.line(wait, epoch + 90_000))

        val offline = WorkStatus.ofFailure("Timeout", epoch, keptAt = kept, network = NetworkStatus.Offline)
        assertEquals(WorkStatus.Offline(kept), offline)
        assertEquals("", WorkStatusWords.spoken(offline, epoch), "the offline banner speaks, the line does not")
        // Failed outranks the wait, the wait outranks offline, offline outranks everything that runs.
        assertEquals(failure, WorkStatus.merge(failure, wait))
        assertEquals(wait, WorkStatus.merge(offline, wait))
        assertEquals(offline, WorkStatus.merge(offline, WorkStatus.Syncing()))
    }

    @Test
    fun `a flood wait inside a call is waited out by that call alone, then the work goes on`() = runTest {
        val pauses = mutableListOf<Long>()
        var calls = 0
        val result = waitingOutFlood(
            tries = 3,
            maxWaitS = 60,
            floodSeconds = { text: String -> Failures.floodWaitSeconds(text) },
            pause = { pauses += it },
        ) {
            calls++
            if (calls == 1) "FLOOD_WAIT_42" else "ok"
        }
        assertEquals("ok", result)
        assertEquals(listOf(42_250L), pauses)
        // A wait past the cap is handed back at once, for the screen to say and count down.
        calls = 0
        val over = waitingOutFlood(3, 60, { text: String -> Failures.floodWaitSeconds(text) }, { pauses += it }) { calls++; "FLOOD_WAIT_900" }
        assertEquals("FLOOD_WAIT_900", over)
        assertEquals(1, calls)
    }
}
