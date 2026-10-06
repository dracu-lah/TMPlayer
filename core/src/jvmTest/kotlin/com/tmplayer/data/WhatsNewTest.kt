package com.tmplayer.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class WhatsNewTest {

    @Test
    fun `a fresh install remembers its version and shows nothing`() {
        assertEquals(WhatsNew.Launch(show = false, remember = "1.23.0"), WhatsNew.onLaunch("", "1.23.0", "1.23.0"))
    }

    @Test
    fun `an update that reaches the highlights shows them once`() {
        assertEquals(WhatsNew.Launch(show = true, remember = "1.23.0"), WhatsNew.onLaunch("1.22.1", "1.23.0", "1.23.0"))
        // The next launch of the same version: nothing.
        assertEquals(WhatsNew.Launch(show = false, remember = null), WhatsNew.onLaunch("1.23.0", "1.23.0", "1.23.0"))
    }

    @Test
    fun `skipping a version still shows the highlights reached`() {
        assertEquals(WhatsNew.Launch(show = true, remember = "1.24.0"), WhatsNew.onLaunch("1.21.0", "1.24.0", "1.23.0"))
    }

    @Test
    fun `a patch release with the same highlights stays quiet`() {
        assertEquals(WhatsNew.Launch(show = false, remember = "1.23.1"), WhatsNew.onLaunch("1.23.0", "1.23.1", "1.23.0"))
    }

    @Test
    fun `highlights for a version not yet installed wait`() {
        assertEquals(WhatsNew.Launch(show = false, remember = "1.22.2"), WhatsNew.onLaunch("1.22.1", "1.22.2", "1.23.0"))
    }

    @Test
    fun `a downgrade or a development build changes nothing`() {
        assertEquals(WhatsNew.Launch(show = false, remember = null), WhatsNew.onLaunch("1.24.0", "1.23.0", "1.23.0"))
        assertEquals(WhatsNew.Launch(show = false, remember = null), WhatsNew.onLaunch("1.22.1", "0", "1.23.0"))
        assertEquals(WhatsNew.Launch(show = false, remember = null), WhatsNew.onLaunch("1.22.1", "dev", "1.23.0"))
    }

    @Test
    fun `build suffixes and a leading v are read as the release`() {
        assertEquals("1.23.0", WhatsNew.release("1.23.0-promo"))
        assertEquals("1.23.0", WhatsNew.release("v1.23.0"))
        assertEquals(WhatsNew.Launch(show = true, remember = "1.23.0"), WhatsNew.onLaunch("1.22.1", "1.23.0-promo", "1.23.0"))
    }

    @Test
    fun `the bundled highlights read from the catalog`() {
        assertTrue(WhatsNew.highlights.size in 3..6)
        assertTrue(WhatsNew.highlights.all { it.isNotBlank() && !it.startsWith("whatsnew.") })
        assertTrue(WhatsNew.CHANGELOG.startsWith("https://"))
    }

    @Test
    fun `last seen version and the announced language persist`() = runBlocking {
        val file = Files.createTempDirectory("tm-whatsnew").resolve(SettingsStore.FILE_NAME).toFile()
        val settings = SettingsStore(SettingsStore.openDataStore(file))
        assertEquals("", settings.lastSeenVersion.first())
        assertEquals("", settings.languageAnnounced.first())
        settings.setLastSeenVersion("1.23.0")
        settings.setLanguageAnnounced("de")
        assertEquals("1.23.0", settings.lastSeenVersion.first())
        assertEquals("de", settings.languageAnnounced.first())
    }
}
