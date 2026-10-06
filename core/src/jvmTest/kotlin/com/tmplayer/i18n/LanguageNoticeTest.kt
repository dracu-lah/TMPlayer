package com.tmplayer.i18n

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The auto-switch and its one-time card. `de` has a tiny hand-made catalog under the test
 * resources only, so these run against a real classpath catalog without one shipping.
 */
class LanguageNoticeTest {

    @After
    fun english() = Translator.use(Languages.ENGLISH)

    @Test
    fun `a device in a shipped language switches the app with nothing picked`() {
        assertEquals("de", Translator.select("", listOf("de-AT", "en-GB")))
        assertEquals("Wiederholen", L.commonRetry)
        // Keys the test catalog lacks read in English, one by one.
        assertEquals("Cancel", L.commonCancel)
    }

    @Test
    fun `the card is said once, in the new language`() {
        val active = Translator.select("", listOf("de-DE"))
        assertTrue(LanguageNotice.shouldShow(saved = "", active = active, announced = "", hasCatalog = Translator.hasCatalog(active)))
        assertEquals("Jetzt auf Deutsch", L.languageNowIn(LanguageNotice.name(active)))
        // Once announced, never again for that language.
        assertFalse(LanguageNotice.shouldShow(saved = "", active = active, announced = "de", hasCatalog = true))
        // A later move to another language is a new switch, and is said again.
        assertTrue(LanguageNotice.shouldShow(saved = "", active = "fr", announced = "de", hasCatalog = true))
    }

    @Test
    fun `no card for english, the pseudo-locale or a language without a catalog`() {
        assertFalse(LanguageNotice.shouldShow("", Languages.ENGLISH, "", hasCatalog = true))
        assertFalse(LanguageNotice.shouldShow("", Languages.PSEUDO, "", hasCatalog = true))
        assertFalse(LanguageNotice.shouldShow("", "es-419", "", hasCatalog = Translator.hasCatalog("xx")))
        assertFalse(LanguageNotice.shouldShow("", "xx", "", hasCatalog = true))
    }

    @Test
    fun `no card after a pick in settings`() {
        assertFalse(LanguageNotice.shouldShow(saved = "de", active = "de", announced = "", hasCatalog = true))
        assertFalse(LanguageNotice.shouldShow(saved = "en", active = "en", announced = "", hasCatalog = true))
    }

    @Test
    fun `a pick in settings beats the device and system default goes back to it`() {
        assertEquals("en", Translator.select("en", listOf("de-DE")))
        assertEquals("Retry", L.commonRetry)
        assertEquals("de", Translator.select("", listOf("de-DE")))
        assertEquals("Wiederholen", L.commonRetry)
    }

    @Test
    fun `catalogs are found on the classpath`() {
        assertTrue(Translator.hasCatalog("en"))
        assertTrue(Translator.hasCatalog("de"))
        assertFalse(Translator.hasCatalog("xx"))
        // A catalog with no keys yet, as a new language has before its first translation run.
        assertFalse(Translator.hasCatalog("xx-empty"))
    }

    @Test
    fun `the card names the language in itself`() {
        assertEquals("Español (Latinoamérica)", LanguageNotice.name("es-419"))
        assertEquals("العربية", LanguageNotice.name("ar"))
        assertEquals("xx", LanguageNotice.name("xx"))
    }
}
