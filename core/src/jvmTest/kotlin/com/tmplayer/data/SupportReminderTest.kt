package com.tmplayer.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * The support ladder: three asks at most, at good moments only, with a 7 day grace, a 30 and a 90
 * day gap, "I already support" for good, counters that never count a re-open within the hour twice,
 * and the one switch that silences it all.
 */
class SupportReminderTest {

    private val dir = Files.createTempDirectory("tm-support").toFile()
    private val settings = SettingsStore(SettingsStore.openDataStore(dir.resolve(SettingsStore.FILE_NAME)))

    private val day = 24L * 60 * 60 * 1000
    private val hour = 60L * 60 * 1000
    private val start = 1_800_000_000_000L

    @Before
    fun fresh() {
        SupportReminder.resetSession()
    }

    @After
    fun reset() {
        SupportReminder.enabled = true
        SupportReminder.forcedRung = 0
        SupportReminder.resetSession()
    }

    private fun counters(
        days: Int = 20,
        watches: Int = 9,
        asked: Int = 0,
        snoozedUntil: Long = 0L,
        never: Boolean = false,
        supporter: Boolean = false,
    ) = SupportReminder.Counters(
        firstSeenAt = start - days * day,
        completedWatches = watches,
        asked = asked,
        snoozedUntil = snoozedUntil,
        neverAgain = never,
        supporter = supporter,
    )

    @Test
    fun `the first ask waits for seven days and five finished videos`() {
        assertEquals(0, SupportReminder.due(counters(days = 6, watches = 50), start))
        assertEquals(0, SupportReminder.due(counters(days = 30, watches = 4), start))
        assertEquals(1, SupportReminder.due(counters(days = 7, watches = 5), start))
        // Never counted at all: no first day, no card.
        assertEquals(0, SupportReminder.due(SupportReminder.Counters(completedWatches = 99), start))
    }

    @Test
    fun `later gaps are thirty and ninety days, and the ladder ends at three`() {
        val shownAt = start
        // After the first ask the second waits 30 days from that moment.
        val afterFirst = counters(days = 60, asked = 1, snoozedUntil = SupportReminder.snoozedUntil(shownAt, 1))
        assertEquals(shownAt + 30 * day, afterFirst.snoozedUntil)
        assertEquals(0, SupportReminder.due(afterFirst, shownAt + 30 * day - 1))
        assertEquals(2, SupportReminder.due(afterFirst, shownAt + 30 * day))
        // After the second the third waits 90 days, whatever the viewer watched meanwhile.
        val afterSecond = counters(days = 200, watches = 0, asked = 2, snoozedUntil = SupportReminder.snoozedUntil(shownAt, 2))
        assertEquals(shownAt + 90 * day, afterSecond.snoozedUntil)
        assertEquals(0, SupportReminder.due(afterSecond, shownAt + 90 * day - 1))
        assertEquals(3, SupportReminder.due(afterSecond, shownAt + 90 * day))
        // Three asked: never again.
        assertEquals(0, SupportReminder.due(counters(days = 900, asked = 3), start + 900 * day))
    }

    @Test
    fun `i already support and the old never are permanent`() {
        assertEquals(0, SupportReminder.due(counters(days = 400, watches = 400, supporter = true), start))
        assertEquals(0, SupportReminder.due(counters(days = 400, watches = 400, never = true), start))
        assertEquals(0, SupportReminder.due(counters(days = 400, asked = 1, supporter = true), start + 400 * day))
    }

    @Test
    fun `the switch turns it off, even when forced`() {
        SupportReminder.forcedRung = 2
        assertEquals(2, SupportReminder.due(SupportReminder.Counters(), start))
        SupportReminder.enabled = false
        assertEquals(0, SupportReminder.due(SupportReminder.Counters(), start))
        assertEquals(0, SupportReminder.due(counters(days = 100, watches = 100), start))
    }

    @Test
    fun `never twice in one session`() {
        assertEquals(1, SupportReminder.due(counters(), start))
        assertEquals(0, SupportReminder.due(counters(), start, sessionShown = true))
    }

    @Test
    fun `a ladder walk with a fake clock`() = runBlocking {
        settings.noteSupportFirstSeen(start)
        // Five finished videos, each a different one, on day 2: nothing yet, it is too early.
        repeat(5) { settings.noteSupportCompleted("1_$it", start + 2 * day + it * 2 * hour) }
        assertEquals(0, SupportReminder.claim(settings, start + 3 * day))

        // Day 7: the first ask. Shown means asked, and the second waits 30 days.
        val first = start + 7 * day
        assertEquals(1, SupportReminder.claim(settings, first))
        assertEquals(1, settings.supportCountersNow().asked)
        assertEquals(first + 30 * day, settings.supportCountersNow().snoozedUntil)
        // Not again in the same run of the app, whatever the day.
        assertEquals(0, SupportReminder.claim(settings, first + 60 * day))

        // A new run, day 36: still inside the gap, so no. Day 37: the second ask.
        SupportReminder.resetSession()
        assertEquals(0, SupportReminder.claim(settings, first + 30 * day - 1))
        assertEquals(2, SupportReminder.claim(settings, first + 30 * day))
        val second = first + 30 * day
        assertEquals(second + 90 * day, settings.supportCountersNow().snoozedUntil)

        // Later (pressing nothing) simply leaves it there; the third ask comes 90 days after.
        SupportReminder.resetSession()
        assertEquals(0, SupportReminder.claim(settings, second + 90 * day - 1))
        assertEquals(3, SupportReminder.claim(settings, second + 90 * day))

        // Cap at three: no fourth, ever.
        SupportReminder.resetSession()
        assertEquals(0, SupportReminder.claim(settings, second + 2000 * day))
    }

    @Test
    fun `i already support stops the ladder for good and survives sign out`() = runBlocking {
        settings.noteSupportFirstSeen(start)
        repeat(6) { settings.noteSupportCompleted("1_$it", start + it * 2 * hour) }
        assertEquals(1, SupportReminder.claim(settings, start + 8 * day))
        settings.markSupporter()
        settings.clearEverything()
        val kept = settings.supportCountersNow()
        assertTrue(kept.supporter)
        assertEquals(start, kept.firstSeenAt)
        assertEquals(6, kept.completedWatches)
        assertEquals(1, kept.asked)
        SupportReminder.resetSession()
        assertEquals(0, SupportReminder.claim(settings, start + 1000 * day))
    }

    @Test
    fun `a forced card leaves the counters alone`() = runBlocking {
        SupportReminder.forcedRung = 3
        assertEquals(3, SupportReminder.claim(settings, start))
        assertEquals(3, SupportReminder.claim(settings, start + 1))
        assertEquals(SupportReminder.Counters(), settings.supportCountersNow())
    }

    @Test
    fun `a finished video counts once within the hour`() = runBlocking {
        settings.noteSupportCompleted("5_9", start)
        // Re-opened and finished again 30 minutes later, and again after 59: still one.
        settings.noteSupportCompleted("5_9", start + hour / 2)
        settings.noteSupportCompleted("5_9", start + hour - 1)
        assertEquals(1, settings.supportCountersNow().completedWatches)
        // An hour after the first finish it is a new watch.
        settings.noteSupportCompleted("5_9", start + hour)
        assertEquals(2, settings.supportCountersNow().completedWatches)
        // Another video is its own count.
        settings.noteSupportCompleted("5_10", start + hour + 1)
        assertEquals(3, settings.supportCountersNow().completedWatches)
    }

    @Test
    fun `watch time follows the position and ignores seeks, pauses and reopens`() = runBlocking {
        val key = SettingsStore.progressKey(7, 42)
        val minute = 60_000L
        // A first report only starts the clock: no time yet.
        settings.saveResumePosition(7, 42, 10 * minute, 60 * minute)
        assertEquals(0L, settings.supportCountersNow().watchTimeMs)
        // Ten seconds of playing, reported ten seconds later.
        settings.saveResumePosition(7, 42, 10 * minute + 10_000, 60 * minute)
        assertTrue(settings.supportCountersNow().watchTimeMs < 1_000L)
        // The pure step: wall clock bounds what a report may add.
        var recent = emptyList<SupportReminder.Recent>()
        var added = 0L
        recent = SupportReminder.stepTime(recent, key, 10 * minute, start).first
        SupportReminder.stepTime(recent, key, 10 * minute + 10_000, start + 10_000).let { (r, ms) -> recent = r; added += ms }
        assertEquals(10_000L, added)
        // A jump forward of 30 minutes (a seek) in 10 seconds of wall time adds only 10 seconds.
        SupportReminder.stepTime(recent, key, 40 * minute, start + 20_000).let { (r, ms) -> recent = r; added += ms }
        assertEquals(20_000L, added)
        // Backwards (a seek back) adds nothing.
        SupportReminder.stepTime(recent, key, 5 * minute, start + 30_000).let { (r, ms) -> recent = r; added += ms }
        assertEquals(20_000L, added)
        // After the video is finished, a re-open within the hour adds no time at all.
        recent = SupportReminder.stepCompleted(recent, key, start + 40_000).first
        SupportReminder.stepTime(recent, key, 6 * minute, start + 50_000).let { (r, ms) -> recent = r; added += ms }
        assertEquals(20_000L, added)
        // More than a minute of wall time still adds at most a minute.
        SupportReminder.stepTime(emptyList(), key, 0L, start).first.let {
            assertEquals(60_000L, SupportReminder.stepTime(it, key, 30 * minute, start + 40 * 60_000L).second)
        }
    }

    @Test
    fun `the recent list survives its own encoding`() {
        val list = listOf(
            SupportReminder.Recent("1_2", 5L, 6L, true),
            SupportReminder.Recent("-3_4", 7L, 0L, false),
        )
        assertEquals(list, SupportReminder.decodeRecent(SupportReminder.encodeRecent(list)))
        assertEquals(emptyList<SupportReminder.Recent>(), SupportReminder.decodeRecent(null))
        assertEquals(emptyList<SupportReminder.Recent>(), SupportReminder.decodeRecent("garbage"))
    }

    @Test
    fun `hours are rounded and never below one`() {
        assertEquals(1, SupportReminder.hoursWatched(SupportReminder.Counters(watchTimeMs = 10 * 60_000L)))
        assertEquals(3, SupportReminder.hoursWatched(SupportReminder.Counters(watchTimeMs = 2 * hour + 40 * 60_000L)))
    }

    @Test
    fun `a good moment is taken once and goes stale`() {
        SupportReminder.watchFinished("1_1", now = start)
        assertTrue(SupportReminder.takeMoment(start + 1000))
        assertFalse(SupportReminder.takeMoment(start + 2000))
        SupportReminder.downloadFinished(now = start)
        assertFalse(SupportReminder.takeMoment(start + SupportReminder.MOMENT_LIFETIME_MS + 1))
    }

    @Test
    fun `the watched list reports a finished watch, a manual mark does not`() = runBlocking {
        val watched = WatchedStore(WatchedStore.openDataStore(dir.resolve("watched.preferences_pb")))
        val record = WatchedRecord(1, 2, 3, "Film", "Chat", 100, 60, start, manual = false)
        SupportReminder.resetSession()
        watched.markWatched(record.copy(manual = true))
        assertFalse(SupportReminder.takeMoment(System.currentTimeMillis()))
        watched.markWatched(record)
        assertTrue(SupportReminder.takeMoment(System.currentTimeMillis()))
    }
}
