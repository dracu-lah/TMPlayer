package com.tmplayer.data

import com.tmplayer.i18n.Translator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every way into Settings from outside it (U20), read off the sources so a new one cannot slip
 * past: a page named in code outside the settings screens (a card's button, a menu) is one a
 * [SettingsLink] names, so its row is checked by SettingsMapTest; the web app's `/settings/<page>`
 * links name a page and an anchor its Settings has; and every line of help text that says
 * "Settings, <place>" names a page, a section or a row that is there, in English as drawn.
 */
class SettingsLinksSourceTest {

    private val root = listOf(File(".."), File(".")).first { File(it, "settings.gradle.kts").isFile }

    private fun sources(dir: String, vararg ext: String): Sequence<File> =
        File(root, dir).walkTopDown().filter { it.isFile && it.extension in ext && "/build/" !in it.path }

    private val english: Map<String, String> =
        Translator.parse(File(root, "core/src/commonMain/resources/i18n/en.json").readText())

    /** The files that are Settings itself, where every page is named as a matter of course. */
    private val settingsFiles = setOf("SettingsScreen.kt", "Settings.kt", "SettingsPages.kt", "SetupChoicesPage.kt")

    @Test
    fun `a page named outside Settings is one a link names`() {
        val linked = SettingsLink.entries.map { it.group.name }.toSet()
        val pattern = Regex("""\bSettings(?:Page|Category)\.([A-Z][A-Za-z]+)\b""")
        val found = (sources("app/src/main", "kt") + sources("desktop/src/main", "kt") + sources("ui/src/shared", "kt"))
            .filter { it.name !in settingsFiles }
            .flatMap { file -> pattern.findAll(file.readText()).map { file.name to it.groupValues[1] } }
            .filter { (_, name) -> name !in setOf("entries", "of", "valueOf", "Companion") }
            .toList()
        val unknown = found.filter { (_, name) -> SettingsGroup.of(name) == null }
        assertEquals("pages that do not exist: $unknown", emptyList<Pair<String, String>>(), unknown)
        val unlinked = found.filter { (_, name) -> name !in linked }
        assertEquals("pages opened from outside without a SettingsLink: $unlinked", emptyList<Pair<String, String>>(), unlinked)
    }

    @Test
    fun `the web app's links land on a page and an anchor it has`() {
        // The page is drawn by Settings.tsx and the files beside it in screens/settings (the page
        // list and the rows' anchors); an old address (library) lands on the page that took its place.
        val settings = (listOf(File(root, "webapp/src/screens/Settings.tsx")) +
            File(root, "webapp/src/screens/settings").listFiles { f -> f.extension == "tsx" }.orEmpty().sortedBy { it.name })
            .joinToString("\n") { it.readText() }
        val map = File(root, "webapp/src/ui/settingsMap.ts").readText()
        val oldPages = Regex("""OLD_PAGES[^=]*=\s*\{([^}]*)\}""").find(map)?.groupValues?.get(1).orEmpty()
            .let { Regex("""([a-z]+)\s*:""").findAll(it).map { m -> m.groupValues[1] }.toSet() }
        val pages = Regex("""\{\s*id:\s*'([a-z]+)'""").findAll(settings).map { it.groupValues[1] }.toSet() + oldPages
        assertTrue("the page list was read: $pages", "home" in pages && "library" in pages)
        val anchors = Regex("""id="([a-z-]+)"""").findAll(settings).map { it.groupValues[1] }.toSet()
        val links = sources("webapp/src", "ts", "tsx")
            .filter { it.name != "Settings.tsx" && it.parentFile.name != "settings" && ".test." !in it.name }
            .flatMap { Regex("""/settings/([a-z]+)(?:#([a-z-]+))?""").findAll(it.readText()).map { m -> it.name to m } }
            .toList()
        assertTrue("found the links in: $links", links.isNotEmpty())
        for ((file, link) in links) {
            assertTrue("$file: /settings/${link.groupValues[1]} is no page of ${pages.sorted()}", link.groupValues[1] in pages)
            link.groupValues[2].takeIf { it.isNotEmpty() }?.let { anchor -> assertTrue("$file: #$anchor is no anchor", anchor in anchors) }
        }
    }

    @Test
    fun `help text that names a place in Settings names one that is there`() {
        // Pages, the sections inside them, and the rows: what "Settings, <place>" can point at.
        val sections = listOf(
            "settings.lists", "metadata.title", "online.title", "lock.title", "backup.section", "settings.help",
            "settings.version", "settings.sound", "settings.privacy", "settings.account_signed_in", "settings.home_rows",
        )
        val places = (
            SettingsGroup.entries.map { it.title } + sections +
                SettingsMap.rows.flatMap { row -> DeviceForm.entries.flatMap { row.titlesOn(it) } }
            ).mapNotNull { english[it] }.toSet()
        // Not ours: another app's Settings, or a line no code shows any more.
        val elsewhere = mapOf(
            "login.qr_step_devices" to "Telegram's Settings",
            "lock.forgot" to "Telegram's Settings",
            "update.blocked_tv" to "Android's Settings",
            "browse.chat_hidden_where" to "read by no code since hidden chats went (docs/settings-audit.md)",
        )
        var checked = 0
        val wrong = english.filterKeys { it !in elsewhere }.mapNotNull { (key, text) ->
            val after = Regex("""Settings, ([A-Z][^.;]*)""").find(text)?.groupValues?.get(1) ?: return@mapNotNull null
            checked++
            if (places.any { after.startsWith(it) }) null else "$key: \"$after\""
        }
        // The chooser's note, the VLC line, the online notices, the kids lock, the posters notice.
        assertTrue("read $checked lines", checked >= 8)
        assertEquals("help text pointing at no place in Settings", emptyList<String>(), wrong)
    }

    @Test
    fun `the rows U20 moved keep the names an old backup carries`() {
        // A move is a change of page, never of the stored name: a backup from before restores
        // into the same rows where they are now.
        for (name in listOf("preferred_audio_language", "downmix_stereo", "even_volume", "download_before_playing", "suggest_vlc")) {
            assertTrue("$name travels in a backup", name in Backups.SETTINGS)
        }
        // Crash reports stay on the device that agreed to them, before and after the move.
        assertTrue("crash_reports" !in Backups.SETTINGS)
    }
}
