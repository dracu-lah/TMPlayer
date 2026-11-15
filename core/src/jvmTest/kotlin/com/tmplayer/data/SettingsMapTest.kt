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
            listOfNotNull(row.title, row.desktopTitle) + row.details + row.drawnAs
        } + SettingsGroup.entries.flatMap { listOf(it.title, it.summary) } + SettingsLink.entries.map { it.from }
        val missing = keys.filter { it !in english }
        assertTrue("not in en.json: $missing", missing.isEmpty())
    }

    @Test
    fun `every row has one id and a page its forms have`() {
        val ids = SettingsMap.rows.map { it.id }
        assertEquals(ids.groupBy { it }.filterValues { it.size > 1 }.keys, emptySet<String>())
        val onlyOn = mapOf(
            SettingsGroup.Gestures to setOf(DeviceForm.Phone),
            SettingsGroup.Mouse to setOf(DeviceForm.Desktop),
            SettingsGroup.TvHome to setOf(DeviceForm.Tv),
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
            "pause_downloads" to SettingsGroup.Storage,
            "resume_sync" to SettingsGroup.Library,
            "find_subtitles" to SettingsGroup.Subtitles,
            "detailed_logs" to SettingsGroup.About,
            "playback_test" to SettingsGroup.About,
            "crash_reports" to SettingsGroup.Account,
            // Moved by U20: the sound and the soundtrack's language beside the subtitles' languages.
            "audio_language" to SettingsGroup.Subtitles,
            "downmix" to SettingsGroup.Subtitles,
            "even_volume" to SettingsGroup.Subtitles,
            "download_first" to SettingsGroup.Storage,
            "privacy_page" to SettingsGroup.Account,
        )
        for ((id, group) in homes) {
            val found = SettingsMap.rows.filter { it.id == id }
            assertEquals(id, 1, found.size)
            assertEquals(id, group, found.single().group)
        }
        // Suggest VLC is read by nothing (U20): gone from Settings.
        assertEquals(null, SettingsMap.row("suggest_vlc"))
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
        assertFalse(SettingsSearch.offered(DeviceForm.Tv, 500))
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
        // The desktop's own words for a row.
        assertEquals("size_limits", search("smallest video", DeviceForm.Desktop).first())
        // Accents and case as typed.
        assertEquals("remember_searches", search("RÉMEMBER sear").first())
        assertEquals("pin", search("pin").first())
        // A row drawn under another title is found by it too.
        assertTrue("pin" in search("change the pin"))
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
