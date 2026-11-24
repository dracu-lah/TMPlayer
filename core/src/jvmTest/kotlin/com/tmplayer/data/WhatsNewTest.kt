package com.tmplayer.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class WhatsNewTest {

    @Test
    fun `a fresh install remembers its version and shows nothing`() {
        assertEquals(WhatsNew.Launch(show = false, remember = "1.23.0"), WhatsNew.onLaunch("", "1.23.0", "1.23.0"))
    }

    @Test
    fun `an update that reaches the highlights shows them once`() {
        assertEquals(WhatsNew.Launch(show = true, remember = "1.23.0"), WhatsNew.onLaunch("1.22.1", "1.23.0", "1.23.0"))
        // The next launch of the same version: nothing.
        assertEquals(WhatsNew.Launch(show = false, remember = null), WhatsNew.onLaunch("1.23.0", "1.23.0", "1.23.0"))
    }

    @Test
    fun `skipping a version still shows the highlights reached`() {
        assertEquals(WhatsNew.Launch(show = true, remember = "1.24.0"), WhatsNew.onLaunch("1.21.0", "1.24.0", "1.23.0"))
    }

    @Test
    fun `a patch release with the same highlights stays quiet`() {
        assertEquals(WhatsNew.Launch(show = false, remember = "1.23.1"), WhatsNew.onLaunch("1.23.0", "1.23.1", "1.23.0"))
    }

    @Test
    fun `highlights for a version not yet installed wait`() {
        assertEquals(WhatsNew.Launch(show = false, remember = "1.22.2"), WhatsNew.onLaunch("1.22.1", "1.22.2", "1.23.0"))
    }

    @Test
    fun `a downgrade or a development build changes nothing`() {
        assertEquals(WhatsNew.Launch(show = false, remember = null), WhatsNew.onLaunch("1.24.0", "1.23.0", "1.23.0"))
        assertEquals(WhatsNew.Launch(show = false, remember = null), WhatsNew.onLaunch("1.22.1", "0", "1.23.0"))
        assertEquals(WhatsNew.Launch(show = false, remember = null), WhatsNew.onLaunch("1.22.1", "dev", "1.23.0"))
    }

    @Test
    fun `build suffixes and a leading v are read as the release`() {
        assertEquals("1.23.0", WhatsNew.release("1.23.0-promo"))
        assertEquals("1.23.0", WhatsNew.release("v1.23.0"))
        assertEquals(WhatsNew.Launch(show = true, remember = "1.23.0"), WhatsNew.onLaunch("1.22.1", "1.23.0-promo", "1.23.0"))
    }

    @Test
    fun `the bundled highlights read from the catalog`() {
        assertTrue(WhatsNew.highlights.size in 3..14)
        assertTrue(WhatsNew.highlights.all { it.isNotBlank() && !it.startsWith("whatsnew.") })
        assertTrue(WhatsNew.CHANGELOG.startsWith("https://"))
    }

    @Test
    fun `the beta's sheet is shown once on the update that reaches it`() {
        assertTrue(Regex("""\d+\.\d+\.\d+""").matches(WhatsNew.VERSION))
        // An update from the last stable to a pre-release of the version: shown, and remembered.
        val first = WhatsNew.onLaunch("1.28.5", "${WhatsNew.VERSION}-beta.1")
        assertEquals(WhatsNew.Launch(show = true, remember = WhatsNew.VERSION), first)
        // The next launch, and the next beta of the same version: nothing.
        assertEquals(WhatsNew.Launch(show = false, remember = null), WhatsNew.onLaunch(WhatsNew.VERSION, "${WhatsNew.VERSION}-beta.1"))
        assertEquals(WhatsNew.Launch(show = false, remember = null), WhatsNew.onLaunch(WhatsNew.VERSION, "${WhatsNew.VERSION}-beta.2"))
    }

    @Test
    fun `a stored version that is empty or garbage is a fresh install and shows nothing`() {
        for (stored in listOf("", " ", "garbage", "1.2", "1.x.0", "v", "9".repeat(10_000), "\u0000", "-1.0.0")) {
            val launch = WhatsNew.onLaunch(stored, WhatsNew.VERSION)
            assertEquals(stored, false, launch.show)
            assertEquals(stored, WhatsNew.VERSION, launch.remember)
        }
    }

    @Test
    fun `every form gets headed lines of its own and none for a key it does not have`() {
        for (form in ReleaseHighlights.Form.entries) {
            val sections = ReleaseHighlights.sectionsFor(form)
            assertTrue("$form", sections.size in 2..4)
            assertTrue("$form", sections.all { it.title.isNotBlank() && !it.title.startsWith("whatsnew.") && it.lines.isNotEmpty() })
            val lines = sections.flatMap { it.lines }
            assertTrue("$form has ${lines.size}", lines.size in 5..14)
            assertEquals("$form repeats a line", lines.size, lines.toSet().size)
            assertTrue(lines.all { it.isNotBlank() && !it.startsWith("whatsnew.") })
        }
        val tv = ReleaseHighlights.highlightsFor(ReleaseHighlights.Form.Tv).toSet()
        val phone = ReleaseHighlights.highlightsFor(ReleaseHighlights.Form.Phone).toSet()
        val desktop = ReleaseHighlights.highlightsFor(ReleaseHighlights.Form.Desktop).toSet()
        val web = ReleaseHighlights.highlightsFor(ReleaseHighlights.Form.Web).toSet()
        // The remote's lines only on a TV, the touch lines never there.
        for (other in listOf(phone, desktop, web)) {
            assertTrue(other.none { "Page and Channel" in it || "Hold OK" in it || "CC key" in it || "OK on it" in it })
        }
        assertTrue(tv.any { "Hold OK" in it } && tv.any { "CC key" in it })
        assertTrue(tv.none { "gear" in it || "On a tablet" in it || "keyboard shortcuts" in it || "browser" in it })
        assertTrue(desktop.any { "keyboard shortcuts" in it } && web.any { "browser" in it } && phone.any { "On a tablet" in it })
        // The web is not told about the desktop window, nor the desktop about the browser.
        assertTrue(web.none { "Quitting" in it || "Force software" in it })
        assertTrue(desktop.none { "browser" in it })
        // One line on the way into the account menu on every form.
        assertTrue(tv.any { "switch or add an account" in it })
        assertTrue(listOf(phone, desktop, web).all { set -> set.any { "account menu" in it } })
    }

    @Test
    fun `the sheet's words keep the public copy rules and carry no dash`() {
        val words = ReleaseHighlights.Form.entries.flatMap { form ->
            ReleaseHighlights.sectionsFor(form).flatMap { listOf(it.title) + it.lines }
        }
        for (word in words) {
            assertTrue(word, '\u2014' !in word && '\u2013' !in word)
            assertTrue(word, !Regex("""\b(film|films|movie|movies|uploader)\b""", RegexOption.IGNORE_CASE).containsMatchIn(word))
            assertTrue(word, word.length <= 160)
        }
    }

    @Test
    fun `a form is read from a word and anything else is the web's`() {
        assertEquals(ReleaseHighlights.Form.Tv, ReleaseHighlights.Form.parse("tv"))
        assertEquals(ReleaseHighlights.Form.Phone, ReleaseHighlights.Form.parse("PHONE"))
        for (garbage in listOf(null, "", "nope", "9".repeat(10_000), "\u0000")) {
            assertEquals(ReleaseHighlights.Form.Web, ReleaseHighlights.Form.parse(garbage))
        }
        assertEquals(ReleaseHighlights.Form.Desktop, ReleaseHighlights.Form.of(DeviceForm.Desktop))
        assertEquals(ReleaseHighlights.sectionsFor(ReleaseHighlights.Form.Tv), WhatsNew.sections(DeviceForm.Tv))
    }

    @Test
    fun `last seen version and the announced language persist`() = runBlocking {
        val file = Files.createTempDirectory("tm-whatsnew").resolve(SettingsStore.FILE_NAME).toFile()
        val settings = SettingsStore(SettingsStore.openDataStore(file))
        assertEquals("", settings.lastSeenVersion.first())
        assertEquals("", settings.languageAnnounced.first())
        settings.setLastSeenVersion("1.23.0")
        settings.setLanguageAnnounced("de")
        assertEquals("1.23.0", settings.lastSeenVersion.first())
        assertEquals("de", settings.languageAnnounced.first())
    }
}
