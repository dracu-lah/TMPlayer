package com.tmplayer.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguagesTest {

    @Test
    fun `twenty languages ship beside english`() {
        assertEquals(21, Languages.tags.size)
        assertEquals(
            listOf(
                "en", "es-419", "pt-BR", "fr", "de", "it", "pl", "ru", "uk", "tr", "id", "vi", "ar", "fa",
                "zh-CN", "zh-TW", "ja", "ko", "hi", "bn", "ml",
            ),
            Languages.tags,
        )
    }

    @Test
    fun `arabic and persian are right to left`() {
        assertTrue(Languages.isRtl("ar"))
        assertTrue(Languages.isRtl("fa"))
        assertEquals(listOf("ar", "fa"), Languages.all.filter { it.rtl }.map { it.tag })
        assertFalse(Languages.isRtl("en"))
        assertTrue(Translator.build("ar", null, emptyMap()).rtl)
    }

    @Test
    fun `the saved setting wins over the system`() {
        assertEquals("de", Languages.resolve("de", listOf("fr-FR")))
        assertEquals("pt-BR", Languages.resolve("pt-BR", listOf("es-CL")))
    }

    @Test
    fun `an empty or unknown saved value follows the system`() {
        assertEquals("fr", Languages.resolve("", listOf("fr-FR")))
        assertEquals("fr", Languages.resolve(null, listOf("fr-CA")))
        assertEquals("fr", Languages.resolve("xx", listOf("fr")))
    }

    @Test
    fun `system tags map onto the shipped regional catalogs`() {
        assertEquals("es-419", Languages.match("es-CL"))
        assertEquals("es-419", Languages.match("es-MX"))
        assertEquals("es-419", Languages.match("es-419"))
        assertEquals("es-419", Languages.match("es-ES"))
        assertEquals("es-419", Languages.match("es"))
        assertEquals("pt-BR", Languages.match("pt-PT"))
        assertEquals("pt-BR", Languages.match("pt-BR"))
        assertEquals("pt-BR", Languages.match("pt"))
        assertEquals("zh-CN", Languages.match("zh-Hans"))
        assertEquals("zh-CN", Languages.match("zh-CN"))
        assertEquals("zh-CN", Languages.match("zh-SG"))
        assertEquals("zh-CN", Languages.match("zh-Hans-HK"))
        assertEquals("zh-CN", Languages.match("zh"))
        assertEquals("zh-TW", Languages.match("zh-TW"))
        assertEquals("zh-TW", Languages.match("zh-Hant"))
        assertEquals("zh-TW", Languages.match("zh-Hant-CN"))
        assertEquals("zh-TW", Languages.match("zh-HK"))
        assertEquals("zh-TW", Languages.match("zh-MO"))
        assertNull(Languages.match("zh-AU"))
    }

    @Test
    fun `a language matches whatever its region`() {
        assertEquals("de", Languages.match("de-AT"))
        assertEquals("ar", Languages.match("ar-EG"))
        assertEquals("ar", Languages.match("ar_SA"))
        assertEquals("ru", Languages.match("ru-UA"))
        assertEquals("ml", Languages.match("ml-IN"))
        assertEquals("pl", Languages.match("pl-PL"))
        assertEquals("fa", Languages.match("fa-IR"))
        assertEquals("bn", Languages.match("bn-BD"))
        assertEquals("hi", Languages.match("hi_IN"))
        assertEquals("en", Languages.match("en-GB"))
    }

    @Test
    fun `android's legacy indonesian code still matches`() {
        assertEquals("id", Languages.match("in-ID"))
        assertEquals("id", Languages.match("id-ID"))
    }

    @Test
    fun `the system list is walked in order and english is the last resort`() {
        assertEquals("ja", Languages.resolve("", listOf("nl-NL", "ja-JP", "de-DE")))
        assertEquals("zh-TW", Languages.resolve("", listOf("nl-NL", "zh-TW", "ko-KR")))
        assertEquals("ko", Languages.resolve("", listOf("zh-AU", "ko-KR")))
        assertEquals("en", Languages.resolve("", listOf("nl-NL", "sv-SE")))
        assertEquals("en", Languages.resolve("", emptyList()))
        assertEquals("en", Languages.resolve("", listOf("")))
    }

    @Test
    fun `the pseudo-locale resolves only when a debug build allows it`() {
        assertEquals("en", Languages.resolve("en-XA", listOf("en-US")))
        assertEquals("en-XA", Languages.resolve("en-XA", listOf("en-US"), pseudo = true))
        assertEquals("en", Languages.resolve("", listOf("en-XA")))
        assertEquals("en-XA", Languages.resolve("", listOf("en-XA", "de"), pseudo = true))
    }
}
