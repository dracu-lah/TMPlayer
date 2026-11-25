package com.tmplayer.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Desktop integration (WP-52): tg:// is left to Telegram Desktop, so no installer or launcher entry
 * registers it; the launcher's actions are command line places; a drop is a link, a backup or
 * something TMPlayer does not play.
 */
class DesktopIntegrationTest {

    /** The desktop module's folder, which is where Gradle runs its tests. */
    private val module = File(System.getProperty("user.dir"))

    @Test
    fun `no installer or launcher entry takes the tg scheme`() {
        val files = listOf(File(module, "build.gradle.kts")) + File(module, "packaging").walkTopDown().filter { it.isFile }.toList()
        assertTrue("the module's files were found", files.size > 1)
        for (file in files) {
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            assertTrue("${file.name} registers tg://", !text.contains("x-scheme-handler/tg", ignoreCase = true))
            assertTrue("${file.name} names tg://", !text.contains("tg://", ignoreCase = true))
            assertTrue("${file.name} lists a URL scheme for Telegram", !Regex("""(?i)url[-_ ]?protocol[^\n]*\btg\b""").containsMatchIn(text))
        }
    }

    @Test
    fun `the launcher actions are command line places a launch and a second launch understand`() {
        val launcher = File(module, "packaging/linux/io.github.dracu_lah.TMPlayer.desktop").readText()
        for (place in LaunchGo.Place.entries) {
            assertTrue("${place.arg} has an action", launcher.contains("Exec=tmplayer --go=${place.arg}"))
            assertEquals(place, LaunchOptions.parse(listOf("--go=${place.arg}")).go)
        }
        assertTrue(launcher.contains("Actions=Resume;Downloads;Search;"))
        assertEquals(LaunchGo.Place.Downloads, LaunchOptions.parse(listOf("--TV", "--go=Downloads")).go)
        assertNull(LaunchOptions.parse(listOf("--go=elsewhere")).go)
        assertNull(LaunchOptions.parse(listOf("--tv")).go)
    }

    @Test
    fun `a dropped link opens, a backup leads to Restore, anything else says what TMPlayer plays`() {
        assertEquals(WindowDropKind.Link("https://t.me/c/123/45"), WindowDrop.classify(" https://t.me/c/123/45 ", emptyList()))
        assertEquals(WindowDropKind.Backup(File("/home/me/tmplayer-backup.json").path), WindowDrop.classify(null, listOf("file:///home/me/tmplayer-backup.json")))
        assertEquals(WindowDropKind.Backup("C:\\backups\\a.JSON"), WindowDrop.classify(null, listOf("C:\\backups\\a.JSON")))
        // A link that is not Telegram's, a picture, an empty drop, text that is no link.
        assertEquals(WindowDropKind.Unsupported, WindowDrop.classify("https://example.com/video", emptyList()))
        assertEquals(WindowDropKind.Unsupported, WindowDrop.classify(null, listOf("/home/me/cat.png")))
        assertEquals(WindowDropKind.Unsupported, WindowDrop.classify("", emptyList()))
        assertEquals(WindowDropKind.Unsupported, WindowDrop.classify("hello there", emptyList()))
    }

    @Test
    fun `a huge dropped text is read only so far and only its link goes on (SEC2-09)`() {
        val words = "a log line with nothing in it\n".repeat(40_000)
        assertTrue(words.length > 1_000_000)
        assertEquals(WindowDropKind.Unsupported, WindowDrop.classify(words, emptyList()))
        // A link inside the first part of a long text: the link alone, not the text around it.
        val withLink = "see https://t.me/c/123/45 here\n" + words
        assertEquals(WindowDropKind.Link("https://t.me/c/123/45"), WindowDrop.classify(withLink, emptyList()))
        // A link past what is read is not looked for.
        assertEquals(WindowDropKind.Unsupported, WindowDrop.classify(words + "https://t.me/c/123/45", emptyList()))
    }
}
