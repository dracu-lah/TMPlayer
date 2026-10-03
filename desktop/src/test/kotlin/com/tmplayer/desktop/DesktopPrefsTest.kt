package com.tmplayer.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DesktopPrefsTest {

    private val dir = Files.createTempDirectory("tm-prefs").toFile()
    private val file = dir.resolve("desktop.properties")

    @Test
    fun defaultsWithNoFile() {
        val prefs = DesktopPrefs(file)
        assertEquals(DesktopSettings(), prefs.now)
        assertFalse(file.exists())
    }

    @Test
    fun survivesARestart() {
        DesktopPrefs(file).update {
            it.copy(volume = 35, muted = true, downmix = true, wheelSeeks = true, softwareDecoding = false, lastUpdateCheck = 42L, dismissedRelease = "2.0.1")
        }
        val again = DesktopPrefs(file).now
        assertEquals(35, again.volume)
        assertTrue(again.muted)
        assertTrue(again.downmix)
        assertTrue(again.wheelSeeks)
        assertFalse(again.softwareDecoding)
        assertEquals(42L, again.lastUpdateCheck)
        assertEquals("2.0.1", again.dismissedRelease)
    }

    @Test
    fun overwritesAnExistingFile() {
        val prefs = DesktopPrefs(file)
        prefs.update { it.copy(volume = 10) }
        prefs.update { it.copy(volume = 20) }
        assertEquals(20, DesktopPrefs(file).now.volume)
        assertFalse(dir.resolve("desktop.properties.tmp").exists())
    }

    @Test
    fun volumeIsClamped() {
        val prefs = DesktopPrefs(file)
        prefs.update { it.copy(volume = 250) }
        assertEquals(100, prefs.now.volume)
        file.writeText("volume=-4\nmuted=maybe\n")
        val read = DesktopPrefs(file).now
        assertEquals(0, read.volume)
        assertFalse(read.muted)
    }

    @Test
    fun aBrokenFileFallsBackToDefaults() {
        file.writeBytes(byteArrayOf(0, 1, 2, 3))
        assertEquals(DesktopSettings().wheelSeeks, DesktopPrefs(file).now.wheelSeeks)
    }
}
