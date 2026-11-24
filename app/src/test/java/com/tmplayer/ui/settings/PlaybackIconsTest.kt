package com.tmplayer.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WP-30: the decoder, the play-in-chat-order and the tunneled playback rows of Settings, Playback each have
 * an icon of their own (Chip, Layers, Sync) instead of the Play triangle the autoplay row already uses.
 * Read from the source, as the rows are three composables over a store: the title line and the icon line
 * of a row sit within a few lines of each other.
 */
class PlaybackIconsTest {

    private fun source(name: String): String = listOf(
        File("src/main/java/com/tmplayer/ui/settings/$name"),
        File("app/src/main/java/com/tmplayer/ui/settings/$name"),
    ).first { it.isFile }.readText()

    /** The icon named within the lines after the row whose title is [title]. */
    private fun iconOf(text: String, title: String): String {
        val lines = text.lines()
        val at = lines.indexOfFirst { it.contains("title = $title,") }
        assertTrue("row $title not found", at >= 0)
        val icon = lines.drop(at).take(8).firstOrNull { it.contains("icon = TmIcons.") }
        assertTrue("row $title has no icon within its lines", icon != null)
        return icon!!.substringAfter("icon = TmIcons.").takeWhile { it.isLetterOrDigit() }
    }

    @Test
    fun `decoder, chat order and tunneled playback each have an icon of their own`() {
        val screen = source("SettingsScreen.kt")
        val playback = source("PlaybackSettings.kt")
        assertEquals("Chip", iconOf(screen, "s.settingsDecoder"))
        assertEquals("Layers", iconOf(screen, "s.settingsPlayChatOrder"))
        assertEquals("Sync", iconOf(playback, "s.settingsTunneled"))
        assertEquals("Play", iconOf(screen, "s.settingsAutoplay"))
    }
}
