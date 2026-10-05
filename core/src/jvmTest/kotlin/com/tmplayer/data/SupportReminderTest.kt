package com.tmplayer.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * The support card: 14 days and 10 plays before it appears, 60 days away after it has been shown,
 * "Don't ask again" for good, and the one switch that silences it with every donation link.
 */
class SupportReminderTest {

    private val dir = Files.createTempDirectory("tm-support").toFile()
    private val settings = SettingsStore(SettingsStore.openDataStore(dir.resolve(SettingsStore.FILE_NAME)))

    private val day = 24L * 60 * 60 * 1000
    private val start = 1_800_000_000_000L

    @After
    fun reset() {
        SupportReminder.enabled = true
        SupportReminder.forced = false
    }

    private fun counters(days: Int, plays: Int, snoozedUntil: Long = 0L, never: Boolean = false) =
        SupportReminder.Counters(firstSeenAt = start - days * day, plays = plays, snoozedUntil = snoozedUntil, neverAgain = never)

    @Test
    fun `waits for fourteen days and ten plays`() {
        assertFalse(SupportReminder.due(counters(days = 13, plays = 50), start))
        assertFalse(SupportReminder.due(counters(days = 30, plays = 9), start))
        assertTrue(SupportReminder.due(counters(days = 14, plays = 10), start))
        // Never counted at all: no install day, no card.
        assertFalse(SupportReminder.due(SupportReminder.Counters(plays = 99), start))
    }

    @Test
    fun `stays away while snoozed and for good after don't ask again`() {
        assertFalse(SupportReminder.due(counters(days = 40, plays = 40, snoozedUntil = start + 1), start))
        assertTrue(SupportReminder.due(counters(days = 40, plays = 40, snoozedUntil = start), start))
        assertFalse(SupportReminder.due(counters(days = 400, plays = 400, never = true), start))
    }

    @Test
    fun `the switch turns it off, even when forced`() {
        SupportReminder.forced = true
        assertTrue(SupportReminder.due(SupportReminder.Counters(), start))
        SupportReminder.enabled = false
        assertFalse(SupportReminder.due(SupportReminder.Counters(), start))
        assertFalse(SupportReminder.due(counters(days = 100, plays = 100), start))
    }

    @Test
    fun `shown once, then not again for sixty days`() = runBlocking {
        settings.noteSupportFirstSeen(start)
        // A later launch does not move the first day.
        settings.noteSupportFirstSeen(start + 5 * day)
        repeat(10) { settings.noteSupportPlay(start + day) }
        assertEquals(SupportReminder.Counters(firstSeenAt = start, plays = 10), settings.supportCountersNow())

        val at = start + 14 * day
        assertFalse(SupportReminder.claim(settings, at - 1))
        assertTrue(SupportReminder.claim(settings, at))
        assertEquals(at + 60 * day, settings.supportCountersNow().snoozedUntil)
        assertFalse(SupportReminder.claim(settings, at + 1))
        assertFalse(SupportReminder.claim(settings, at + 60 * day - 1))
        assertTrue(SupportReminder.claim(settings, at + 60 * day))
    }

    @Test
    fun `not now snoozes and don't ask again sticks through sign out`() = runBlocking {
        settings.noteSupportPlay(start)
        repeat(9) { settings.noteSupportPlay(start) }
        val at = start + 20 * day
        settings.snoozeSupport(at)
        assertFalse(SupportReminder.claim(settings, at + 59 * day))

        settings.neverAskSupport()
        settings.clearEverything()
        val kept = settings.supportCountersNow()
        assertTrue(kept.neverAgain)
        assertEquals(start, kept.firstSeenAt)
        assertEquals(10, kept.plays)
        assertFalse(SupportReminder.claim(settings, at + 365 * day))
    }

    @Test
    fun `a forced card leaves the counters alone`() = runBlocking {
        SupportReminder.forced = true
        assertTrue(SupportReminder.claim(settings, start))
        assertTrue(SupportReminder.claim(settings, start + 1))
        assertEquals(SupportReminder.Counters(), settings.supportCountersNow())
    }
}
