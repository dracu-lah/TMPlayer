package com.tmplayer.data

import com.tmplayer.player.TouchPrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** WP-27: "Remember brightness" is off by default, keeps the level only while on, and a bad stored level reads as the system's. */
class BrightnessTest {

    private val dir = Files.createTempDirectory("tm-bright").toFile()
    private val settings = SettingsStore(SettingsStore.openDataStore(dir.resolve(SettingsStore.FILE_NAME)))

    @Test
    fun `off by default and nothing stored`() = runBlocking {
        assertFalse(settings.touchPrefsNow().rememberBrightness)
        settings.setPlayerBrightness(70)
        assertEquals("not stored while the switch is off", TouchPrefs.BRIGHTNESS_SYSTEM, settings.playerBrightnessNow())
    }

    @Test
    fun `kept while on, the system level forgets it, switching off forgets it`() = runBlocking {
        settings.updateTouchPrefs { it.copy(rememberBrightness = true) }
        settings.setPlayerBrightness(70)
        assertEquals(70, settings.playerBrightnessNow())
        settings.setPlayerBrightness(TouchPrefs.BRIGHTNESS_SYSTEM)
        assertEquals(TouchPrefs.BRIGHTNESS_SYSTEM, settings.playerBrightnessNow())
        settings.setPlayerBrightness(40)
        settings.updateTouchPrefs { it.copy(rememberBrightness = false) }
        assertEquals(TouchPrefs.BRIGHTNESS_SYSTEM, settings.playerBrightnessNow())
        assertTrue(settings.touchPrefs.first().let { !it.rememberBrightness })
    }

    @Test
    fun `a level out of range reads as the system's`() {
        assertEquals(TouchPrefs.BRIGHTNESS_SYSTEM, TouchPrefs.sanitiseBrightness(null))
        assertEquals(TouchPrefs.BRIGHTNESS_SYSTEM, TouchPrefs.sanitiseBrightness(0))
        assertEquals(TouchPrefs.BRIGHTNESS_SYSTEM, TouchPrefs.sanitiseBrightness(1))
        assertEquals(TouchPrefs.BRIGHTNESS_SYSTEM, TouchPrefs.sanitiseBrightness(101))
        assertEquals(TouchPrefs.BRIGHTNESS_SYSTEM, TouchPrefs.sanitiseBrightness(Long.MAX_VALUE))
        assertEquals(35, TouchPrefs.sanitiseBrightness(35))
    }

    @Test
    fun `the page steps by ten, from the system's level at fifty, held between ten and a hundred`() {
        assertEquals(60, TouchPrefs.stepBrightness(TouchPrefs.BRIGHTNESS_SYSTEM, up = true))
        assertEquals(40, TouchPrefs.stepBrightness(TouchPrefs.BRIGHTNESS_SYSTEM, up = false))
        assertEquals(40, TouchPrefs.stepBrightness(50, up = false))
        assertEquals(10, TouchPrefs.stepBrightness(10, up = false))
        assertEquals(100, TouchPrefs.stepBrightness(100, up = true))
        assertEquals(30, TouchPrefs.stepBrightness(37, up = false))
        assertEquals(40, TouchPrefs.stepBrightness(37, up = true))
    }
}
