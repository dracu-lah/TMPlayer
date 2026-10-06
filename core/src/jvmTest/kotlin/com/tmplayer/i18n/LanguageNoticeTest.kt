package com.tmplayer.i18n

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The auto-switch and its one-time card, against the shipped Spanish catalog. The words are not
 * pinned, so a better translation never breaks these: what matters is that they are not English.
 */
class LanguageNoticeTest {

    @After
    fun english() = Translator.use(Languages.ENGLISH)

    @Test
    fun `a device in a shipped language switches the app with nothing picked`() {
        assertEquals("es-419", Translator.select("", listOf("es-CL", "en-GB")))
        assertNotEquals("Retry", L.commonRetry)
    }

    @Test
    fun `the card is said once, in the new language`() {
        val active = Translator.select("", listOf("es-MX"))
        assertTrue(LanguageNotice.shouldShow(saved = "", active = active, announced = "", hasCatalog = Translator.hasCatalog(active)))
        val card = L.languageNowIn(LanguageNotice.name(active))
        assertTrue(card, "Español (Latinoamérica)" in card)
        assertNotEquals("Now in Español (Latinoamérica)", card)
        // Once announced, never again for that language.
        assertFalse(LanguageNotice.shouldShow(saved = "", active = active, announced = "es-419", hasCatalog = true))
        // A later move to another language is a new switch, and is said again.
        assertTrue(LanguageNotice.shouldShow(saved = "", active = "ar", announced = "es-419", hasCatalog = true))
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
        assertFalse(LanguageNotice.shouldShow(saved = "ar", active = "ar", announced = "", hasCatalog = true))
        assertFalse(LanguageNotice.shouldShow(saved = "en", active = "en", announced = "", hasCatalog = true))
    }

    @Test
    fun `a pick in settings beats the device and system default goes back to it`() {
        assertEquals("en", Translator.select("en", listOf("es-CL")))
        assertEquals("Retry", L.commonRetry)
        assertEquals("es-419", Translator.select("", listOf("es-CL")))
        assertNotEquals("Retry", L.commonRetry)
    }

    @Test
    fun `catalogs are found on the classpath`() {
        assertTrue(Translator.hasCatalog("en"))
        assertTrue(Translator.hasCatalog("es-419"))
        assertTrue(Translator.hasCatalog("ar"))
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
