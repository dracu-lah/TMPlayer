package com.tmplayer.data

import com.tmplayer.data.SupportRegion.Method
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Which way to support TMPlayer comes first (D1): India (the language's region or the SIM) gets UPI
 * first, everywhere else Buy Me a Coffee, and nothing odd throws. The reminder keeps its cadence
 * whatever the region says.
 */
class SupportRegionTest {

    @After
    fun reset() {
        SupportReminder.enabled = true
        SupportReminder.resetSession()
    }

    @Test
    fun `India by the language region puts UPI first`() {
        val order = SupportRegion.order("IN", null)
        assertEquals(Method.Upi, order.first)
        assertEquals(Method.Coffee, order.second)
        assertFalse(order.secondIsQuiet)
    }

    @Test
    fun `India by the SIM alone puts UPI first, whatever the language says`() {
        assertEquals(Method.Upi, SupportRegion.order("US", "in").first)
        assertEquals(Method.Upi, SupportRegion.order(null, " IN ").first)
    }

    @Test
    fun `the United States and the rest put Buy Me a Coffee first and UPI as the quiet second`() {
        for (region in listOf("US", "GB", "DE", "BR", "PK", "us")) {
            val order = SupportRegion.order(region, null)
            assertEquals(region, Method.Coffee, order.first)
            assertEquals(Method.Upi, order.second)
            assertTrue(order.secondIsQuiet)
        }
    }

    @Test
    fun `no region at all is the default order`() {
        assertEquals(Method.Coffee, SupportRegion.order(null, null).first)
        assertEquals(Method.Coffee, SupportRegion.order("", "").first)
    }

    @Test
    fun `garbage never throws and never means India`() {
        val garbage = listOf(
            "India", "IND", "I", "I N", "419", "1N", "\u0000\u0000", "🇮🇳", "IN\n", "und", "i",
            "x".repeat(10_000), " ", "\t", "ÍN",
        )
        for (value in garbage) {
            val order = SupportRegion.order(value, value)
            // "IN\n" trims to IN: the one value here that is India after trimming.
            val expected = if (value.trim().equals("IN", ignoreCase = true)) Method.Upi else Method.Coffee
            assertEquals(value, expected, order.first)
        }
        assertNull(SupportRegion.clean("India"))
        assertEquals("IN", SupportRegion.clean(" in "))
    }

    @Test
    fun `the region does not change when the reminder may come, only what it offers`() = runBlocking {
        val day = 24L * 60 * 60 * 1000
        val now = 1_800_000_000_000L
        val used = SupportReminder.Counters(firstSeenAt = now - 20 * day, completedWatches = 9)
        // Due the same way for a viewer in India and one elsewhere: the cadence has no region in it.
        assertTrue(SupportReminder.nudgeDue(used, now, switchOn = true))
        // Thirty days between, and the switch and "Don't show again" are honoured.
        assertFalse(SupportReminder.nudgeDue(used.copy(nudgedAt = now - 29 * day), now, switchOn = true))
        assertTrue(SupportReminder.nudgeDue(used.copy(nudgedAt = now - 31 * day), now, switchOn = true))
        assertFalse(SupportReminder.nudgeDue(used, now, switchOn = false))
        assertFalse(SupportReminder.nudgeDue(used.copy(neverAgain = true), now, switchOn = true))
        assertFalse(SupportReminder.nudgeDue(used, now, switchOn = true, enabled = false))
        // A window shown yesterday holds the notification back for a week.
        assertFalse(SupportReminder.nudgeDue(used.copy(askedAt = now - day), now, switchOn = true))
        // Claimed once, so a second check the same hour sends nothing.
        val store = SettingsStore(
            SettingsStore.openDataStore(Files.createTempDirectory("tm-region").resolve(SettingsStore.FILE_NAME).toFile()),
        )
        store.noteSupportFirstSeen(now - 20 * day)
        repeat(9) { store.noteSupportCompleted("v$it", now - (9 - it) * 2 * day) }
        assertNotNull(SupportReminder.claimNudgeRung(store, now))
        assertNull(SupportReminder.claimNudgeRung(store, now + 60_000))
    }
}
