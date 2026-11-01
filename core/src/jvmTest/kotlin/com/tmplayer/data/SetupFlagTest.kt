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
    fun `only my folders is read once and forgotten`() = runBlocking {
        val settings = store()
        assertFalse(settings.takeOnlyFolders())
        settings.storeOnlyFoldersForTest(true)
        assertTrue(settings.takeOnlyFolders())
        assertFalse(settings.takeOnlyFolders())
    }
}
