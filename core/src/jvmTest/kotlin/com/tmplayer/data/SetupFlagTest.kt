package com.tmplayer.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** The flag the setup after sign in is gated on, on every device. */
class SetupFlagTest {

    private fun store() = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-setup").resolve(SettingsStore.FILE_NAME).toFile()),
    )

    @Test
    fun `an install that never saw a sign in screen is not asked`() = runBlocking {
        // Somebody signed in before the setup existed: no sign in screen, so nothing arms it.
        assertFalse(store().setupPending.first())
    }

    @Test
    fun `a sign in screen arms it and finishing or skipping ends it`() = runBlocking {
        val settings = store()
        settings.armSetup()
        assertTrue(settings.setupPending.first())
        // Arming twice (every sign in step arms) changes nothing.
        settings.armSetup()
        assertTrue(settings.setupPending.first())
        settings.markSetupDone()
        assertFalse(settings.setupPending.first())
    }

    @Test
    fun `signing out and in again asks again`() = runBlocking {
        val settings = store()
        settings.armSetup()
        settings.markSetupDone()
        settings.clearEverything()
        assertFalse("not before the sign in screen", settings.setupPending.first())
        settings.armSetup()
        assertTrue(settings.setupPending.first())
    }

    @Test
    fun `settings can run it again`() = runBlocking {
        val settings = store()
        settings.replaySetup()
        assertTrue(settings.setupPending.first())
        settings.markSetupDone()
        assertFalse(settings.setupPending.first())
    }

    @Test
    fun `the flag is separate from the choices the setup makes`() = runBlocking {
        val settings = store()
        settings.armSetup()
        settings.setLowStorageMode(true)
        settings.markSetupDone()
        settings.replaySetup()
        assertTrue(settings.lowStorageMode.first())
    }

    @Test
    fun `finishing the setup leaves the tour to be offered on its own`() = runBlocking {
        val settings = store()
        settings.armSetup()
        settings.markSetupDone()
        // No longer part of the setup: the shell offers it once, after the chat choice.
        assertEquals(SettingsStore.TourState.Never, settings.tourState.first())
        // Waiting to be shown from an earlier offer: put back to the offer, not marked done.
        settings.openTour()
        settings.replaySetup()
        settings.markSetupDone()
        assertEquals(SettingsStore.TourState.Never, settings.tourState.first())
    }

    @Test
    fun `a tour already taken or turned down stays done through the setup`() = runBlocking {
        val settings = store()
        settings.markTourDone()
        settings.replaySetup()
        settings.markSetupDone()
        assertEquals(SettingsStore.TourState.Done, settings.tourState.first())
    }

    @Test
    fun `Not now on the tour offer brings it back once after a week, then it is turned down`() = runBlocking {
        val settings = store()
        val day = 24L * 60 * 60 * 1000
        settings.armSetup()
        settings.markSetupDone()
        assertEquals(0L, settings.tourOfferAfter.first())
        assertTrue(SettingsStore.tourOfferDue(settings.tourState.first(), settings.tourOfferAfter.first(), now = 1_000L))

        // The first Not now: still not done, and held back for seven days.
        assertTrue(settings.putOffTourOffer(now = 1_000L))
        assertEquals(SettingsStore.TourState.Never, settings.tourState.first())
        val after = settings.tourOfferAfter.first()
        assertEquals(1_000L + SettingsStore.TOUR_OFFER_PAUSE_MS, after)
        assertFalse(SettingsStore.tourOfferDue(SettingsStore.TourState.Never, after, now = 1_000L + 6 * day))
        assertTrue(SettingsStore.tourOfferDue(SettingsStore.TourState.Never, after, now = 1_000L + 7 * day))

        // Offered again and turned down: now it stays done, and the setup rerun does not bring it back.
        assertFalse(settings.putOffTourOffer(now = 1_000L + 8 * day))
        assertEquals(SettingsStore.TourState.Done, settings.tourState.first())
        settings.replaySetup()
        settings.markSetupDone()
        assertEquals(SettingsStore.TourState.Done, settings.tourState.first())
        // Taking it, or having done it, is never due.
        assertFalse(SettingsStore.tourOfferDue(SettingsStore.TourState.Pending, 0L, now = 1L))
        assertFalse(SettingsStore.tourOfferDue(SettingsStore.TourState.Done, 0L, now = 1L))
    }

    @Test
    fun `only my folders is read once and forgotten`() = runBlocking {
        val settings = store()
        assertFalse(settings.takeOnlyFolders())
        settings.storeOnlyFoldersForTest(true)
        assertTrue(settings.takeOnlyFolders())
        assertFalse(settings.takeOnlyFolders())
    }

    @Test
    fun `a fresh install starts with Low storage mode on, an updated or answered one does not change`() = runBlocking {
        val fresh = store()
        assertFalse("off until a sign in screen", fresh.lowStorageModeNow())
        fresh.armSetup()
        assertTrue(fresh.lowStorageModeNow())
        // Turned off by the viewer: arming again (every sign in step arms) does not turn it back on.
        fresh.setLowStorageMode(false)
        fresh.armSetup()
        assertFalse(fresh.lowStorageModeNow())

        val signedIn = store()
        signedIn.markSignedIn()
        signedIn.armSetup()
        assertFalse("an install that was signed in before keeps off", signedIn.lowStorageModeNow())

        val answered = store()
        answered.setLowStorageMode(false)
        answered.armSetup()
        assertFalse(answered.lowStorageModeNow())
    }
}
