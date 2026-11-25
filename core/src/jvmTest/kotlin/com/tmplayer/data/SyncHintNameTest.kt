package com.tmplayer.data

import com.tmplayer.i18n.L
import com.tmplayer.i18n.Translator
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * One name for the sync switch (user, 2026-11-25): the sync stopped hint ("find X in Settings") names the
 * row by the very string that titles that row on every platform, and no English text still calls it
 * "Resume on your other devices". The web draws the same row from `syncplace.switch` (webapp settingsMap).
 */
class SyncHintNameTest {

    private val english: Map<String, String> = Translator.parse(File("src/commonMain/resources/i18n/en.json").readText())

    private val name get() = L.syncplaceSwitch

    @Test
    fun `the Settings row is titled by the same string on phone, TV and desktop`() {
        val row = assertNotNull(SettingsMap.row("resume_sync"))
        assertEquals("syncplace.switch", row.title)
        assertEquals(null, row.desktopTitle, "the desktop does not title it another way")
        assertEquals(english["syncplace.switch"], name)
        for (form in listOf(DeviceForm.Phone, DeviceForm.Tv, DeviceForm.Desktop)) {
            assertTrue(form in row.forms, "$form has the row")
        }
    }

    @Test
    fun `every sync hint names the row by that string`() {
        assertTrue(name in L.stateSyncStoppedTv(name = name))
        assertTrue(name in L.stateSyncNeedsYouTv(name = name))
        assertTrue(name in L.stateSyncOpenSettings(name = name))
        // The television's one line holds the name whole and ends with it.
        assertEquals("Sync stopped. Turn it on again in Settings: $name.", L.stateSyncStoppedTv(name = name))
    }

    @Test
    fun `the old names are gone from the english text and the older keys say the new one`() {
        assertEquals(name, english["settings.resume_sync"])
        assertEquals("$name?", english["settings.resume_sync_enable_title"])
        assertEquals("$name is on", english["settings.resume_sync_started"])
        for ((key, text) in english) {
            assertFalse("Resume on your other devices" in text, "$key still uses the old name")
        }
    }
}
