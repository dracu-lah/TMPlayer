package com.tmplayer.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** The flag the tour is gated on, on every device: unseen on a fresh install, and Settings can unsee it. */
class OverviewSeenTest {

    private fun store() = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-overview").resolve(SettingsStore.FILE_NAME).toFile()),
    )

    @Test
    fun `a fresh install has not seen the tour`() = runBlocking {
        assertFalse(store().overviewSeen.first())
    }

    @Test
    fun `finishing or skipping the tour marks it seen`() = runBlocking {
        val settings = store()
        settings.markOverviewSeen()
        assertTrue(settings.overviewSeen.first())
        // Marking twice (Start pressed twice in a row) changes nothing.
        settings.markOverviewSeen()
        assertTrue(settings.overviewSeen.first())
    }

    @Test
    fun `show the walkthrough again brings it back until it is finished again`() = runBlocking {
        val settings = store()
        settings.markOverviewSeen()
        settings.replayOverview()
        assertFalse(settings.overviewSeen.first())
        settings.markOverviewSeen()
        assertTrue(settings.overviewSeen.first())
    }

    @Test
    fun `the tour flag is separate from the language the tour picks`() = runBlocking {
        val settings = store()
        settings.setLanguage("es-419")
        settings.markOverviewSeen()
        settings.replayOverview()
        assertFalse(settings.overviewSeen.first())
        assertTrue(settings.language.first() == "es-419")
    }
}
