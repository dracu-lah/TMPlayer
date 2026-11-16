package com.tmplayer.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the sync file is written (Keep my place on all my devices): the few-minutes cap, the extra
 * write on leaving the player, a burst of saves as one write, nothing in the background on a low
 * end device, the viewer's own asks at once, and a FLOOD_WAIT waited out by the one call that got
 * it. The sync itself is a fake that counts the writes it was asked for, so no Telegram is needed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncSchedulerTest {

    private val minute = 60_000L

    private class Rig(
        scope: TestScope,
        var foreground: Boolean = true,
        lowEnd: Boolean = false,
        startedAt: Long = 0L,
    ) {
        val ran = mutableListOf<Pair<Long, SyncScheduler.Why>>()
        var lastRun = startedAt
        val scheduler = SyncScheduler(
            scope = scope.backgroundScope,
            clock = { scope.testScheduler.currentTime + EPOCH },
            foreground = { foreground },
            lowEnd = lowEnd,
            lastRunAt = { lastRun },
            job = { why -> ran += (scope.testScheduler.currentTime to why); true },
            onRan = { lastRun = it },
        )
    }

    private companion object {
        /** Virtual time starts at 0; the clock the scheduler reads is far from 0, as a real one is. */
        const val EPOCH = 1_800_000_000_000L
    }

    @Test
    fun aBurstOfSavesIsOneWriteAfterTheyHaveSettled() = runTest {
        val rig = Rig(this)
        repeat(10) {
            rig.scheduler.ask(SyncScheduler.Why.Settled)
            advanceTimeBy(5_000)
        }
        runCurrent()
        assertTrue(rig.ran.isEmpty())
        advanceTimeBy(SyncScheduler.PUSH_SETTLE_MS + 1)
        assertEquals(1, rig.ran.size)
    }

    @Test
    fun writesComeAtMostOnceInAFewMinutes() = runTest {
        val rig = Rig(this)
        val written = mutableListOf<Long>()
        // A save every 30 seconds for twenty minutes, each settling and each asking for a write.
        repeat(40) {
            rig.scheduler.ask(SyncScheduler.Why.Settled)
            advanceTimeBy(30_000)
            runCurrent()
        }
        advanceTimeBy(5 * minute)
        written += rig.ran.map { it.first }
        assertTrue("at least two writes in 25 minutes: $written", written.size >= 2)
        for ((a, b) in written.zipWithNext()) assertTrue("gap ${b - a}", b - a >= SyncScheduler.MIN_GAP_MS)
    }

    @Test
    fun aRunAsksAgainAfterTheGapNotBefore() = runTest {
        val rig = Rig(this)
        rig.scheduler.ask(SyncScheduler.Why.Start)
        advanceTimeBy(SyncScheduler.START_DELAY_MS + 1)
        assertEquals(1, rig.ran.size)
        // One minute later the player saves: it waits for the three minutes to pass.
        advanceTimeBy(minute)
        rig.scheduler.ask(SyncScheduler.Why.Settled)
        advanceTimeBy(SyncScheduler.PUSH_SETTLE_MS + 1)
        assertEquals(1, rig.ran.size)
        advanceTimeBy(2 * minute)
        assertEquals(2, rig.ran.size)
    }

    @Test
    fun leavingThePlayerWritesSoonButNotMoreThanTwiceAMinute() = runTest {
        val rig = Rig(this)
        rig.scheduler.ask(SyncScheduler.Why.Settled)
        advanceTimeBy(SyncScheduler.PUSH_SETTLE_MS + 1)
        assertEquals(1, rig.ran.size)
        val first = rig.ran.single().first
        // Closing the player ten seconds later: well before the three minutes, after the thirty seconds.
        advanceTimeBy(10_000)
        rig.scheduler.ask(SyncScheduler.Why.LeftPlayer)
        advanceTimeBy(SyncScheduler.LEAVE_SETTLE_MS + 1)
        assertEquals("not yet: under thirty seconds since the last one", 1, rig.ran.size)
        advanceTimeBy(30_000)
        assertEquals(2, rig.ran.size)
        assertTrue(rig.ran[1].first - first < SyncScheduler.MIN_GAP_MS)
        assertEquals(SyncScheduler.Why.LeftPlayer, rig.ran[1].second)
    }

    @Test
    fun theViewersOwnAsksGoAtOnceWhateverHoldsTheRest() = runTest {
        val rig = Rig(this, foreground = false, lowEnd = true)
        rig.scheduler.ask(SyncScheduler.Why.Settled)
        advanceTimeBy(SyncScheduler.PUSH_SETTLE_MS + 1)
        assertTrue("held in the background", rig.ran.isEmpty())
        rig.lastRun = EPOCH
        rig.scheduler.ask(SyncScheduler.Why.TurnedOn)
        runCurrent()
        rig.scheduler.ask(SyncScheduler.Why.Manual)
        runCurrent()
        assertEquals(listOf(SyncScheduler.Why.TurnedOn, SyncScheduler.Why.Manual), rig.ran.map { it.second })
    }

    @Test
    fun nothingRunsInTheBackgroundOnALowEndDeviceAndItGoesOnWhenTheAppIsBack() = runTest {
        val rig = Rig(this, foreground = false, lowEnd = true)
        rig.scheduler.ask(SyncScheduler.Why.Settled)
        advanceTimeBy(10 * minute)
        assertTrue(rig.ran.isEmpty())
        rig.foreground = true
        rig.scheduler.foregrounded()
        advanceTimeBy(SyncScheduler.PUSH_SETTLE_MS + 1)
        assertEquals(1, rig.ran.size)
    }

    @Test
    fun aStrongDeviceSyncsInTheBackgroundToo() = runTest {
        val rig = Rig(this, foreground = false, lowEnd = false)
        rig.scheduler.ask(SyncScheduler.Why.Settled)
        advanceTimeBy(SyncScheduler.PUSH_SETTLE_MS + 1)
        assertEquals(1, rig.ran.size)
    }

    @Test
    fun theAppGoingAwayDuringTheWaitHoldsTheRun() = runTest {
        val rig = Rig(this, lowEnd = true)
        rig.scheduler.ask(SyncScheduler.Why.Settled)
        advanceTimeBy(5_000)
        rig.foreground = false
        advanceTimeBy(minute)
        assertTrue(rig.ran.isEmpty())
        rig.foreground = true
        rig.scheduler.foregrounded()
        advanceTimeBy(SyncScheduler.PUSH_SETTLE_MS + 1)
        assertEquals(1, rig.ran.size)
    }

    @Test
    fun aRunThatDidNothingBecauseSyncIsOffDoesNotStartThePause() = runTest {
        var ranAt = 0L
        val scheduler = SyncScheduler(
            scope = backgroundScope,
            clock = { testScheduler.currentTime + EPOCH },
            foreground = { true },
            lowEnd = false,
            lastRunAt = { ranAt },
            job = { false },
            onRan = { ranAt = it },
        )
        scheduler.ask(SyncScheduler.Why.Manual)
        runCurrent()
        assertEquals(0L, ranAt)
    }

    @Test
    fun aFailingSyncNeverBreaksTheScheduler() = runTest {
        var calls = 0
        val scheduler = SyncScheduler(
            scope = backgroundScope,
            clock = { testScheduler.currentTime + EPOCH },
            foreground = { true },
            lowEnd = false,
            lastRunAt = { 0L },
            job = { calls++; error("TDLib is gone") },
        )
        scheduler.ask(SyncScheduler.Why.Manual)
        runCurrent()
        scheduler.ask(SyncScheduler.Why.Manual)
        runCurrent()
        assertEquals(2, calls)
    }

    // ---- FLOOD_WAIT ---------------------------------------------------------------------------

    private data class Answer(val flood: Int?, val ok: Boolean)

    @Test
    fun aFloodWaitIsWaitedOutByTheCallThatGotItAndRetriesStop() = runTest {
        val pauses = mutableListOf<Long>()
        val answers = ArrayDeque(listOf(Answer(12, false), Answer(3, false), Answer(null, true)))
        val result = waitingOutFlood(3, 60, { a: Answer -> a.flood }, { pauses += it }) { answers.removeFirst() }
        assertTrue(result.ok)
        assertEquals(listOf(12_250L, 3_250L), pauses)

        // Never ends: after three tries the failure is handed back, not retried for ever.
        var calls = 0
        val stuck = waitingOutFlood(3, 60, { a: Answer -> a.flood }, { }) { calls++; Answer(1, false) }
        assertFalse(stuck.ok)
        assertEquals(3, calls)

        // A wait longer than the limit is not waited for at all.
        calls = 0
        val long = waitingOutFlood(3, 60, { a: Answer -> a.flood }, { pauses += it }) { calls++; Answer(3_600, false) }
        assertEquals(3_600, long.flood)
        assertEquals(1, calls)
    }
}
