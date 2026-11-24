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
    fun `the Tips card is due until the tour is taken or dismissed, then never again`() = runBlocking {
        val settings = store()
        settings.armSetup()
        settings.markSetupDone()
        assertTrue(SettingsStore.tipsDue(settings.tourState.first()))
        // Dismissed for good: one tap, and a rerun of the setup does not bring it back.
        settings.markTourDone()
        assertFalse(SettingsStore.tipsDue(settings.tourState.first()))
        settings.replaySetup()
        settings.markSetupDone()
        assertFalse(SettingsStore.tipsDue(settings.tourState.first()))
        // Taking it is not due either.
        assertFalse(SettingsStore.tipsDue(SettingsStore.TourState.Pending))
    }

    @Test
    fun `running it again from Settings is a replay, a sign in is not`() = runBlocking {
        val settings = store()
        settings.armSetup()
        assertTrue(settings.setupPending.first())
        assertFalse(settings.setupReplay.first())
        settings.markSetupDone()
        settings.replaySetup()
        assertTrue(settings.setupPending.first())
        assertTrue(settings.setupReplay.first())
        // A sign in screen over a replay is a sign in again: the strict question.
        settings.armSetup()
        assertFalse(settings.setupReplay.first())
        settings.markSetupDone()
        assertFalse(settings.setupPending.first())
        assertFalse(settings.setupReplay.first())
    }

    @Test
    fun `a fresh install ends with streaming mode on and crash reports off`() = runBlocking {
        val settings = store()
        settings.armSetup()
        settings.markSetupDone()
        assertTrue("streaming mode (Save space) is on for a fresh install", settings.lowStorageModeNow())
        assertFalse("crash reports are off until Settings turns them on", settings.crashReportsNow())
        assertFalse(settings.crashReports.first())
        // Nothing in the setup writes the answer: it stays unanswered, so Settings asks once.
        assertFalse("the crash switch was never written", settings.crashReportsAsked.first())
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

    @Test
    fun `the retired mini player switch is removed on first read and never read again`() = runBlocking {
        val settings = store()
        // Nothing stored: nothing to do, no crash.
        settings.dropRetiredMiniPlayer()
        assertFalse(settings.hasMiniPlayerForTest())
        settings.storeMiniPlayerForTest(false)
        assertTrue(settings.hasMiniPlayerForTest())
        settings.dropRetiredMiniPlayer()
        assertFalse(settings.hasMiniPlayerForTest())
        settings.dropRetiredMiniPlayer()
        assertFalse(settings.hasMiniPlayerForTest())
    }

    // ---- WP-73: a new television install starts in Dark, nobody else's look changes ----------------

    @Test
    fun `a fresh television install starts in Dark`() = runBlocking {
        val settings = store()
        settings.armSetup(tvDarkByDefault = true)
        assertEquals(ThemeChoice.Dark, settings.themeChoice.first())
        // Arming again (every sign in step arms) and finishing leave it as it is.
        settings.armSetup(tvDarkByDefault = true)
        settings.markSetupDone()
        assertEquals(ThemeChoice.Dark, settings.themeChoice.first())
    }

    @Test
    fun `a phone, a tablet or a desktop first run keeps the system theme`() = runBlocking {
        val settings = store()
        settings.armSetup()
        assertEquals(ThemeChoice.System, settings.themeChoice.first())
    }

    @Test
    fun `an install that was already used keeps what it has, even a theme that was never written`() = runBlocking {
        // Signed in before the setup existed: not fresh, so the television default does not apply.
        val updated = store()
        updated.markSignedIn()
        updated.armSetup(tvDarkByDefault = true)
        assertEquals(ThemeChoice.System, updated.themeChoice.first())
        // Setup already seen: a second arm (a session that ended) is not a fresh install either.
        val seen = store()
        seen.armSetup()
        seen.markSetupDone()
        seen.armSetup(tvDarkByDefault = true)
        assertEquals(ThemeChoice.System, seen.themeChoice.first())
        // A theme that was chosen stays chosen.
        val chosen = store()
        chosen.setThemeChoice(ThemeChoice.Light)
        chosen.armSetup(tvDarkByDefault = true)
        assertEquals(ThemeChoice.Light, chosen.themeChoice.first())
    }

    @Test
    fun `a theme chosen or restored after the default wins`() = runBlocking {
        val settings = store()
        settings.armSetup(tvDarkByDefault = true)
        settings.setThemeChoice(ThemeChoice.Light)
        assertEquals(ThemeChoice.Light, settings.themeChoice.first())
    }
}
