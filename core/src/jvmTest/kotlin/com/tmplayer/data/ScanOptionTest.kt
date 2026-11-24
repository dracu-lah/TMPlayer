package com.tmplayer.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Scan while holding, jump once when let go (WP-79): off by default, a television's row, a device key. */
class ScanOptionTest {

    private fun store() = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-scan").resolve(SettingsStore.FILE_NAME).toFile()),
    )

    @Test
    fun `it is off until chosen, and the choice is kept`() = runBlocking {
        val settings = store()
        assertFalse(settings.scanThenJump.first())
        assertFalse(settings.scanThenJumpNow())
        settings.setScanThenJump(true)
        assertTrue(settings.scanThenJump.first())
        settings.setScanThenJump(false)
        assertFalse(settings.scanThenJumpNow())
    }

    @Test
    fun `it is a row of Playback on a television only, and a device setting`() {
        val row = SettingsMap.row("scan_then_jump")!!
        assertEquals(SettingsGroup.Playback, row.group)
        assertEquals(setOf(DeviceForm.Tv), row.forms)
        assertFalse(AccountKeys.isAccount("scan_then_jump"))
    }
}
