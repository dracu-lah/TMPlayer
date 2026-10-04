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
            it.copy(volume = 35, muted = true, downmix = true, wheelSeeks = true, softwareDecoding = false)
        }
        val again = DesktopPrefs(file).now
        assertEquals(35, again.volume)
        assertTrue(again.muted)
        assertTrue(again.downmix)
        assertTrue(again.wheelSeeks)
        assertFalse(again.softwareDecoding)
    }

    @Test
    fun updateSettingsMoveOutOnce() = kotlinx.coroutines.runBlocking {
        file.writeText("volume=40\ncheck_for_updates=false\nlast_update_check=42\ndismissed_release=2.0.1\n")
        val prefs = DesktopPrefs(file)
        val moved = mutableListOf<LegacyUpdatePrefs>()
        prefs.migrateUpdatePrefs { moved += it }
        prefs.migrateUpdatePrefs { moved += it }
        assertEquals(listOf(LegacyUpdatePrefs(notify = false, lastCheck = 42L, dismissed = "2.0.1")), moved)
        assertFalse(file.readText().contains("last_update_check"))
        assertEquals(40, DesktopPrefs(file).now.volume)
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
