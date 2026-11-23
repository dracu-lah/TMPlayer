package com.tmplayer.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** "Hide the controls after": a changed time is stored even from a store that holds none yet. */
class TouchPrefsStoreTest {

    private val dir = Files.createTempDirectory("tm-touch-prefs").toFile()
    private val settings = SettingsStore(SettingsStore.openDataStore(dir.resolve(SettingsStore.FILE_NAME)))

    @Test
    fun `a time copied into a fresh store is stored and read back`() = runBlocking {
        assertFalse(settings.touchPrefs.first().controlsTimeoutStored)
        settings.updateTouchPrefs { it.copy(controlsTimeoutMs = 2_000L) }
        val now = settings.touchPrefs.first()
        assertEquals(2_000L, now.controlsTimeoutMs)
        assertTrue(now.controlsTimeoutStored)
    }

    @Test
    fun `another touch setting does not freeze the default time`() = runBlocking {
        settings.updateTouchPrefs { it.copy(haptics = false) }
        assertFalse(settings.touchPrefs.first().controlsTimeoutStored)
    }
}
