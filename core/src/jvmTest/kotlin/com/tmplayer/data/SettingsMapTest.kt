package com.tmplayer.data

import com.tmplayer.i18n.Languages
import com.tmplayer.i18n.Translator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The map of Settings (U20): every key it names is in the catalog, every row has one home, the
 * pages a form lacks hold none of its rows, every link from outside Settings lands on a row that
 * exists, and the search box's matcher answers as a viewer would expect.
 */
class SettingsMapTest {

    private val english: Map<String, String> =
        Translator.parse(File("src/commonMain/resources/i18n/en.json").readText())

    @Before
    fun english() {
        Translator.use(Languages.ENGLISH)
    }

    private fun text(key: String): String = Translator.messages.text(key)

    private fun search(query: String, form: DeviceForm = DeviceForm.Phone): List<String> =
        SettingsSearch.match(query, SettingsMap.on(form), form, ::text).map { it.row.id }

    @Test
    fun `every key the map and the links name is in the catalog`() {
        val keys = SettingsMap.rows.flatMap { row ->
            listOfNotNull(row.title, row.desktopTitle, row.keywords) + row.details + row.drawnAs
        } + SettingsGroup.entries.flatMap { listOf(it.title, it.summary) } + SettingsLink.entries.map { it.from }
        val missing = keys.filter { it !in english }
        assertTrue("not in en.json: $missing", missing.isEmpty())
    }

    @Test
    fun `every row has one id and a page its forms have`() {
        val ids = SettingsMap.rows.map { it.id }
        assertEquals(ids.groupBy { it }.filterValues { it.size > 1 }.keys, emptySet<String>())
        val onlyOn = mapOf(
            // A television posts no notifications (CP76).
            SettingsGroup.Notifications to setOf(DeviceForm.Phone, DeviceForm.Desktop),
        )
        for (row in SettingsMap.rows) {
            assertTrue("$row has no form", row.forms.isNotEmpty())
            onlyOn[row.group]?.let { allowed -> assertTrue("$row is on a form its page is not", allowed.containsAll(row.forms)) }
            assertTrue("$row is on Setup choices, which lists the setup's answers", row.group != SettingsGroup.SetupChoices)
        }
    }

    @Test
    fun `gestures and the mouse are rows of Playback and Home is on one page with the library (Raju 11 1, 11 3)`() {
        // Player gestures, Mouse and Home were pages of their own; a viewer looking for "what plays"
        // or "which videos show" met two pages for each.
        assertEquals(emptyList<String>(), SettingsGroup.entries.map { it.name }.filter { it in setOf("Gestures", "Mouse", "Home") })
        val phone = SettingsMap.on(DeviceForm.Phone, SettingsGroup.Playback).map { it.id }
        assertTrue("the phone's gestures are on Playback: $phone", listOf("haptics", "tap_plays", "double_tap", "hold_speed", "seek_gesture", "brightness_gesture", "volume_gesture").all { it in phone })
        assertEquals(emptyList<String>(), SettingsMap.on(DeviceForm.Tv, SettingsGroup.Playback).map { it.id }.filter { it in setOf("haptics", "hold_speed", "mouse_hold_speed") })
        val desktop = SettingsMap.on(DeviceForm.Desktop, SettingsGroup.Playback).map { it.id }
        assertTrue("the desktop's mouse rows are on Playback: $desktop", "mouse_hold_speed" in desktop && "wheel_seeks" in desktop)
        // The decoder choice is jargon: last on the desktop's Playback page, under Advanced.
        assertEquals("software_decoding", desktop.last())
        val library = SettingsMap.on(DeviceForm.Phone, SettingsGroup.Library).map { it.id }
        assertTrue("Home's rows and the library's are one page: $library", "home_carousel" in library && "open_last_chat" in library && "posters" in library)
        assertEquals("Home and library", text(SettingsGroup.Library.title))
    }

    @Test
    fun `no page shows two rows with the same title`() {
        for (form in DeviceForm.entries) for (group in SettingsGroup.entries) {
            val titles = SettingsMap.on(form, group).map { text(it.titleOn(form)) }
            assertEquals("$form $group", titles.distinct(), titles)
        }
    }

    @Test
    fun `this week's rows each have one home`() {
        val homes = mapOf(
            "default_player" to SettingsGroup.Playback,
            "player_engine" to SettingsGroup.Playback,
            "mobile_data" to SettingsGroup.Storage,
            "resume_sync" to SettingsGroup.Accounts,
            "find_subtitles" to SettingsGroup.Subtitles,
            "detailed_logs" to SettingsGroup.Troubleshooting,
            "playback_test" to SettingsGroup.Troubleshooting,
            "crash_reports" to SettingsGroup.Privacy,
            // Moved by U20: the sound and the soundtrack's language beside the subtitles' languages.
            "audio_language" to SettingsGroup.Subtitles,
            "downmix" to SettingsGroup.Subtitles,
            "even_volume" to SettingsGroup.Subtitles,
            "download_first" to SettingsGroup.Storage,
            "privacy_page" to SettingsGroup.Privacy,
        )
        for ((id, group) in homes) {
            val found = SettingsMap.rows.filter { it.id == id }
            assertEquals(id, 1, found.size)
            assertEquals(id, group, found.single().group)
        }
        // Suggest VLC is read by nothing (U20): gone from Settings.
        assertEquals(null, SettingsMap.row("suggest_vlc"))
    }

    /** WP-08: the pages in one order on every platform, nine on a television and ten elsewhere. */
    @Test
    fun `the pages are in the task order, nine on a television and ten on a phone and a desktop`() {
        val touch = listOf("Accounts", "Playback", "Subtitles", "Storage", "Appearance", "Library", "Privacy", "Notifications", "Help", "About")
        assertEquals(touch, SettingsMap.pages(DeviceForm.Phone).map { it.name })
        assertEquals(touch, SettingsMap.pages(DeviceForm.Desktop).map { it.name })
        assertEquals(touch - "Notifications", SettingsMap.pages(DeviceForm.Tv).map { it.name })
        assertEquals(9, SettingsMap.pages(DeviceForm.Tv).size)
        assertEquals(10, SettingsMap.pages(DeviceForm.Phone).size)
        assertEquals(
            listOf("Account", "Playback", "Subtitles and audio", "Downloads and data", "Appearance and language", "Home and library", "Privacy and lock", "Notifications", "Help", "About"),
            SettingsMap.pages(DeviceForm.Phone).map { text(it.title) },
        )
        // Troubleshooting and Redo setup choices open from rows of Help and are not listed.
        assertEquals(listOf(SettingsGroup.Help), listOf(SettingsGroup.Troubleshooting.parent, SettingsGroup.SetupChoices.parent).distinct())
        for (form in DeviceForm.entries) for (group in SettingsGroup.entries.filter { !it.listed }) {
            assertTrue("$group is not listed on $form", group !in SettingsMap.pages(form))
        }
        assertTrue("setup_choices" in SettingsMap.on(DeviceForm.Tv, SettingsGroup.Help).map { it.id })
        assertTrue("troubleshooting" in SettingsMap.on(DeviceForm.Tv, SettingsGroup.Help).map { it.id })
        // The television's launcher rows are a section of Home and library.
        val launcher = setOf("watch_next", "tv_channels")
        assertEquals(listOf("watch_next", "tv_channels"), SettingsMap.on(DeviceForm.Tv, SettingsGroup.Library).map { it.id }.filter { it in launcher })
        assertEquals(emptyList<String>(), SettingsMap.on(DeviceForm.Phone, SettingsGroup.Library).map { it.id }.filter { it in launcher })
        // Language is the first row of Appearance and language, on every form.
        for (form in DeviceForm.entries) assertEquals("language", SettingsMap.on(form, SettingsGroup.Appearance).first().id)
        // Sign out is on Account, Backup with the storage, Skip buttons in Playback (WP-08).
        assertEquals(SettingsGroup.Accounts, SettingsMap.row("sign_out")!!.group)
        assertEquals(SettingsGroup.Storage, SettingsMap.row("back_up")!!.group)
        assertEquals(SettingsGroup.Storage, SettingsMap.row("restore")!!.group)
        assertEquals(SettingsGroup.Playback, SettingsMap.row("skip_buttons")!!.group)
        assertEquals(SettingsGroup.Library, SettingsMap.row("skip_times")!!.group)
        assertEquals(null, SettingsMap.row("skip_mode_intro"))
        // One About row and one Support row.
        assertEquals(1, SettingsMap.rows.count { it.id == "about" })
        assertEquals(1, SettingsMap.rows.count { it.id == "support" })
    }

    /** WP-08: every page name an earlier version stored reads as a page of this one. */
    @Test
    fun `every page name stored before WP-08 maps to a page of the new order`() {
        val old = listOf("Playback", "Subtitles", "Storage", "Notifications", "Appearance", "Account", "Library", "TvHome", "About", "SetupChoices")
        val expected = mapOf("Account" to SettingsGroup.Privacy, "TvHome" to SettingsGroup.Library)
        for (name in old) {
            val group = SettingsGroup.fromStored(name)
            assertNotNull("$name maps to nothing", group)
            assertEquals(name, expected[name] ?: SettingsGroup.valueOf(name), group)
        }
        // The new names read as themselves, and a name nobody used reads as nothing.
        for (group in SettingsGroup.entries) assertEquals(group, SettingsGroup.fromStored(group.name))
        assertEquals(null, SettingsGroup.fromStored("Nothing"))
        assertEquals(null, SettingsGroup.fromStored(""))
        // A retired name is never also a group's name, or it could not be told apart.
        assertTrue(SettingsGroup.RETIRED.keys.none { SettingsGroup.of(it) != null })
    }

    /** WP-08: a row moved off a page is an alias there for one release, naming where it went. */
    @Test
    fun `every moved row has a Moved to alias at its old place and lands on its new page`() {
        val moved = SettingsMap.rows.filter { it.movedFrom != null }
        assertTrue(moved.size > 10)
        for (row in moved) {
            val from = row.movedFrom!!
            assertTrue("$row is not on its old page now", row.group != from)
            for (form in row.forms) {
                assertTrue("$from lists no alias for $row on $form", row in SettingsMap.movedFrom(form, from))
                // The alias reads "Moved to {page}": the page it names is one the form lists or opens from one.
                val page = if (row.group.listed) row.group else row.group.parent!!
                assertTrue("$row names $page, which $form does not list", page in SettingsMap.pages(form))
            }
        }
        val signOut = SettingsMap.movedFrom(DeviceForm.Tv, SettingsGroup.Privacy).map { it.id }
        assertTrue("sign_out" in signOut && "devices" in signOut && "add_account" in signOut)
        assertEquals(listOf("back_up", "restore"), SettingsMap.movedFrom(DeviceForm.Phone, SettingsGroup.About).map { it.id }.filter { it in setOf("back_up", "restore") })
        assertTrue("skip_buttons" in SettingsMap.movedFrom(DeviceForm.Phone, SettingsGroup.Library).map { it.id })
        // A row a form does not have leaves no alias there (detailed logs and the playback test are no desktop's).
        assertTrue("detailed_logs" !in SettingsMap.movedFrom(DeviceForm.Desktop, SettingsGroup.About).map { it.id })
        // The old place is a page the form lists, or the alias has nowhere to be seen.
        for (group in moved.map { it.movedFrom!! }.distinct()) assertTrue("$group is a listed page", group.listed)
        assertEquals("Moved to Downloads and data", text("settings.also_in_moved").replace("{page}", text(SettingsGroup.Storage.title)))
        // One home for every row: ids are unique, so a moved row is never listed on two pages.
        assertEquals(SettingsMap.rows.size, SettingsMap.rows.map { it.id }.distinct().size)
    }

    @Test
    fun `the moved rows are found by search at their new home`() {
        val phone = SettingsMap.on(DeviceForm.Phone)
        assertEquals("sign_out", search("sign out").first())
        assertEquals("sign_out", SettingsSearch.match("log out", SettingsMap.on(DeviceForm.Tv), DeviceForm.Tv, ::text).first().row.id)
        assertEquals("Account", SettingsSearch.match("sign out", phone, DeviceForm.Phone, ::text).first().page)
        assertEquals("Downloads and data", SettingsSearch.match("back up", phone, DeviceForm.Phone, ::text).first { it.row.id == "back_up" }.page)
        assertEquals("Troubleshooting", SettingsSearch.match("playback test", phone, DeviceForm.Phone, ::text).first { it.row.id == "playback_test" }.page)
        assertTrue("skip_buttons" in search("intro"))
        assertTrue("skip_buttons" in search("credits"))
        assertTrue("setup_choices" in search("redo"))
    }

    @Test
    fun `every link from outside Settings lands on a row that is there`() {
        for (link in SettingsLink.entries) {
            val row = link.row ?: continue
            for (form in link.forms) {
                val there = SettingsMap.on(form, link.group).map { it.id }
                assertTrue("$link on $form: $row is not on ${link.group} ($there)", row in there)
            }
        }
    }

    @Test
    fun `a television gets no search box, a phone and a desktop do past 25 rows`() {
        assertFalse("a television has no box over a list", SettingsSearch.offered(DeviceForm.Tv, 500))
        // WP-09: it has Find a setting, over the rows it shows and never a touch-only one.
        assertTrue(SettingsSearch.findOnTv(SettingsMap.on(DeviceForm.Tv).size))
        assertFalse(SettingsSearch.findOnTv(0))
        val tvRows = SettingsMap.on(DeviceForm.Tv)
        val hits = SettingsSearch.match("haptic", tvRows, DeviceForm.Tv) { text(it) }
        assertEquals("haptics is a phone's row", emptyList<String>(), hits.map { it.row.id })
        assertTrue(SettingsSearch.match("back up", tvRows, DeviceForm.Tv) { text(it) }.any { it.row.id == "back_up" })
        // An empty query: the rows people come for, those this device has, in order.
        val common = SettingsSearch.suggestions(tvRows, DeviceForm.Tv) { text(it) }.map { it.row.id }
        assertEquals(SettingsSearch.COMMON_ROWS.filter { id -> tvRows.any { it.id == id } }, common)
        assertFalse(SettingsSearch.offered(DeviceForm.Phone, 25))
        assertTrue(SettingsSearch.offered(DeviceForm.Phone, 26))
        assertTrue(SettingsSearch.offered(DeviceForm.Desktop, SettingsMap.on(DeviceForm.Desktop).size))
        assertTrue(SettingsSearch.offered(DeviceForm.Phone, SettingsMap.on(DeviceForm.Phone).size))
    }

    @Test
    fun `the matcher answers as a viewer expects`() {
        // A title that starts with the words typed comes first.
        assertEquals("subtitle_style", search("subtitle").first())
        assertEquals("subtitle_language", search("preferred subtitle").first())
        assertEquals("audio_language", search("audio lang").first())
        // Word starts, in any order.
        assertEquals("resume_rewind", search("resuming rewind").first())
        // Found by the line under the row and by its page's name.
        assertTrue("player_engine" in search("vlc", DeviceForm.Tv) || "engine_switch" in search("vlc", DeviceForm.Tv))
        assertTrue("downmix" in search("sound"))
        // A row another form has is not offered.
        assertTrue("wallpaper_colours" in search("wallpaper"))
        assertEquals(emptyList<String>(), search("wallpaper", DeviceForm.Desktop))
        assertTrue("wheel_seeks" in search("wheel", DeviceForm.Desktop))
        assertEquals(emptyList<String>(), search("wheel"))
        // Accents and case as typed.
        assertEquals("remember_searches", search("RÉMEMBER sear").first())
        assertEquals("pin", search("pin").first())
        // A row drawn under another title is found by it too.
        assertTrue("pin" in search("change the pin"))
    }

    /**
     * The words a person thinks in (Raju 11.2): the search matches meanings, not only titles. Each
     * phrase must find its row, on the phone and the desktop where both have it, and the rows the
     * review named must come first.
     */
    @Test
    fun `a viewer's own words find the row, on the phone and the desktop`() {
        val phrases = mapOf(
            "bigger" to "interface_size",
            "larger" to "interface_size",
            "font" to "interface_size",
            "zoom" to "interface_size",
            "text size" to "interface_size",
            "big text" to "interface_size",
            "delete" to "free_up_space",
            "delete everything" to "free_up_space",
            "log out" to "sign_out",
            "logout" to "sign_out",
            "sign off" to "sign_out",
            "mobile data" to "mobile_data",
            "data" to "mobile_data",
            "wifi" to "mobile_data",
            "internet" to "mobile_data",
            // The While streaming row, whatever its title says now: the old name and the new words.
            "low storage" to "low_storage",
            "save space" to "low_storage",
            "streaming" to "low_storage",
            "space" to "free_up_space",
        )
        for ((phrase, id) in phrases) {
            val phone = search(phrase, DeviceForm.Phone)
            assertTrue("phone: \"$phrase\" must find $id, found $phone", id in phone)
            val desktop = search(phrase, DeviceForm.Desktop)
            if (SettingsMap.row(id)!!.forms.contains(DeviceForm.Desktop)) {
                assertTrue("desktop: \"$phrase\" must find $id, found $desktop", id in desktop)
            }
        }
        // The first answer is the row the words are for, not a line that happens to hold one.
        assertEquals("interface_size", search("bigger").first())
        assertEquals("interface_size", search("big").first())
        assertEquals("sign_out", search("log out").first())
        assertEquals("sign_out", search("logout").first())
        assertEquals("low_storage", search("low storage").first())
        assertTrue("remove after watching is found by delete too", "remove_after_watching" in search("delete"))
        // The matcher reads the key a row names: a locale's own words are found as well.
        val row = SettingsMap.row("interface_size")!!
        val hits = SettingsSearch.match("grande", listOf(row), DeviceForm.Phone) { key -> if (key == row.keywords) "grande texto" else text(key) }
        assertEquals(listOf("interface_size"), hits.map { it.row.id })
    }

    @Test
    fun `nothing typed, nothing found, and nothing that throws`() {
        assertEquals(emptyList<String>(), search(""))
        assertEquals(emptyList<String>(), search("   ,.;  "))
        assertEquals(emptyList<String>(), search("zzqx"))
        assertEquals(emptyList<String>(), search("{} #%& \u0000"))
        // A placeholder left in a message is not a word of it: no row says "device" for {device}.
        assertFalse("detail_first" in search("device"))
        val huge = "settings ".repeat(20_000)
        assertTrue(search(huge).size <= SettingsSearch.MAX_HITS)
        // A text reader that fails for a key leaves that row's words out, not the search.
        val hits = SettingsSearch.match("pure black", SettingsMap.on(DeviceForm.Phone), DeviceForm.Phone) { key ->
            if (key == "theme.black_detail") error("bad catalog") else text(key)
        }
        assertEquals("pure_black", hits.first().row.id)
        assertNotNull(hits.first().page)
    }

    @Test
    fun `words fold accents and keep other scripts`() {
        assertEquals(listOf("wallpapers", "colours"), SettingsSearch.words("Wallpaper's Colours"))
        assertEquals(listOf("ecran", "noir"), SettingsSearch.words("Écran, noir!"))
        assertEquals(listOf("字幕"), SettingsSearch.words("字幕"))
    }
}
