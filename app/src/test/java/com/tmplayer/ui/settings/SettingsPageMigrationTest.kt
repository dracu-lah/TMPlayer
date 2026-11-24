package com.tmplayer.ui.settings

import com.tmplayer.data.SettingsGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** WP-08: every page name an earlier version stored opens a page of the new order, and sub-pages go back to Help. */
class SettingsPageMigrationTest {
    @Test
    fun `every old page name maps to a page`() {
        val old = listOf("Playback", "Subtitles", "Storage", "Notifications", "Appearance", "Account", "Library", "TvHome", "About", "SetupChoices")
        for (name in old) assertNotNull("$name maps to nothing", SettingsPage.of(name))
        assertEquals(SettingsPage.Privacy, SettingsPage.of("Account"))
        assertEquals(SettingsPage.Library, SettingsPage.of("TvHome"))
        assertEquals(SettingsPage.Accounts, SettingsPage.of("Accounts"))
        assertNull(SettingsPage.of("Nothing"))
    }

    @Test
    fun `the pages follow the groups and sub-pages go back to Help`() {
        assertEquals(SettingsGroup.entries.map { it.name }, SettingsPage.entries.map { it.name })
        assertEquals(SettingsPage.Help, SettingsPage.Troubleshooting.parent)
        assertEquals(SettingsPage.Help, SettingsPage.SetupChoices.parent)
        assertNull(SettingsPage.About.parent)
    }
}
