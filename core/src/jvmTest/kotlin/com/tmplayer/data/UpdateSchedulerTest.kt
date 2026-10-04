package com.tmplayer.data

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the update check runs and when the popup opens, against a clock the test moves by hand. */
class UpdateSchedulerTest {

    private class FakePrefs : UpdatePrefs {
        var notify = true
        var last = 0L
        var skipped = ""
        var snoozed = 0L
        var shown = ""
        override suspend fun notify() = notify
        override suspend fun lastCheck() = last
        override suspend fun setLastCheck(at: Long) { last = at }
        override suspend fun skippedVersion() = skipped
        override suspend fun setSkippedVersion(version: String) { skipped = version }
        override suspend fun snoozedUntil() = snoozed
        override suspend fun setSnoozedUntil(at: Long) { snoozed = at }
        override suspend fun popupShownFor() = shown
        override suspend fun setPopupShownFor(version: String) { shown = version }
    }

    private val prefs = FakePrefs()
    private var clock = 100 * HOUR
    private var answers = true
    private val checks = mutableListOf<Pair<Boolean, String>>()
    private var skippedState = 0

    private fun scheduler(now: () -> Long = { clock }) = UpdateScheduler(
        prefs = prefs,
        now = now,
        check = { quiet, skipped ->
            checks += quiet to skipped
            answers
        },
        onSkip = { skippedState++ },
    )

    @Test
    fun `the first launch checks, and a second within six hours does not`() = runTest {
        assertTrue(scheduler().checkIfDue())
        assertEquals(clock, prefs.last)
        clock += 5 * HOUR
        assertFalse(scheduler().checkIfDue())
        assertEquals(1, checks.size)
        clock += HOUR
        assertTrue(scheduler().checkIfDue())
        assertEquals(2, checks.size)
    }

    @Test
    fun `a failed check writes nothing, so the next launch tries again`() = runTest {
        answers = false
        assertFalse(scheduler().checkIfDue())
        assertEquals(0L, prefs.last)
        clock += 1
        scheduler().checkIfDue()
        assertEquals(2, checks.size)
    }

    @Test
    fun `the setting off stops the scheduled checks but not the button`() = runTest {
        prefs.notify = false
        scheduler().checkIfDue()
        assertTrue(checks.isEmpty())
        scheduler().checkNow()
        assertEquals(listOf(false to ""), checks)
    }

    @Test
    fun `the button ignores the throttle and passes the skipped version to be re-offered`() = runTest {
        prefs.skipped = "1.20.0"
        scheduler().checkIfDue()
        scheduler().checkNow()
        assertEquals(listOf(true to "1.20.0", false to "1.20.0"), checks)
    }

    @Test
    fun `a clock set back before the last check counts as due`() = runTest {
        prefs.last = clock + 10 * HOUR
        assertTrue(scheduler().checkIfDue())
    }

    @Test
    fun `runs ten seconds after launch and every six hours after that`() = runTest {
        val time = testScheduler
        val job = launch { scheduler(now = { time.currentTime }).run() }
        advanceTimeBy(UpdateScheduler.FIRST_CHECK_DELAY_MS - 1)
        runCurrent()
        assertTrue(checks.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, checks.size)
        advanceTimeBy(UpdateScheduler.REPEAT_MS)
        runCurrent()
        assertEquals(2, checks.size)
        job.cancel()
    }

    @Test
    fun `the popup opens once per version`() = runTest {
        val s = scheduler()
        assertTrue(s.shouldPopUp("1.20.0"))
        s.popupShown("1.20.0")
        assertFalse(s.shouldPopUp("1.20.0"))
        assertTrue(s.shouldPopUp("1.21.0"))
    }

    @Test
    fun `remind me later brings the popup back after a day`() = runTest {
        val s = scheduler()
        s.popupShown("1.20.0")
        s.remindLater()
        assertFalse(s.shouldPopUp("1.20.0"))
        clock += UpdateScheduler.SNOOZE_MS - 1
        assertFalse(s.shouldPopUp("1.20.0"))
        clock += 1
        assertTrue(s.shouldPopUp("1.20.0"))
    }

    @Test
    fun `a skipped version never pops up, a newer one does`() = runTest {
        val s = scheduler()
        s.skip("1.20.0")
        assertEquals(1, skippedState)
        assertFalse(s.shouldPopUp("1.20.0"))
        assertTrue(s.shouldPopUp("1.20.1"))
        // The scheduled check is told, so it passes over it.
        s.checkIfDue()
        assertEquals("1.20.0", checks.single().second)
    }

    private companion object {
        const val HOUR = 60 * 60_000L
    }
}
