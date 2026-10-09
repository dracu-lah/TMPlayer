package com.tmplayer.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

/** The player's episode list remembers its next-up order and the intro's end per series. */
class EpisodePrefsTest {

    private fun store() = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-episodes").resolve(SettingsStore.FILE_NAME).toFile()),
    )

    @Test
    fun `the order is per series and episode number by default`() = runBlocking {
        val settings = store()
        assertEquals(EpisodeOrder.Number, settings.episodeOrder("Harbour Notes"))
        settings.setEpisodeOrder("Harbour Notes", EpisodeOrder.Upload)
        assertEquals(EpisodeOrder.Upload, settings.episodeOrder("harbour_notes"))
        assertEquals(EpisodeOrder.Number, settings.episodeOrder("Night Train"))
        settings.setEpisodeOrder("Harbour Notes", EpisodeOrder.Number)
        assertEquals(EpisodeOrder.Number, settings.episodeOrder("Harbour Notes"))
    }

    @Test
    fun `the intro end is per series and can be cleared`() = runBlocking {
        val settings = store()
        assertNull(settings.introEnd("Harbour Notes"))
        settings.setIntroEnd("Harbour Notes", 92_000)
        assertEquals(92_000L, settings.introEnd("Harbour.Notes"))
        assertNull(settings.introEnd("Night Train"))
        settings.setIntroEnd("Harbour Notes", null)
        assertNull(settings.introEnd("Harbour Notes"))
    }
}
