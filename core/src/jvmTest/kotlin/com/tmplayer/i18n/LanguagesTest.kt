package com.tmplayer.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguagesTest {

    @Test
    fun `spanish and arabic ship beside english`() {
        assertEquals(listOf("en", "es-419", "ar"), Languages.tags)
    }

    @Test
    fun `only arabic is right to left`() {
        assertTrue(Languages.isRtl("ar"))
        assertEquals(listOf("ar"), Languages.all.filter { it.rtl }.map { it.tag })
        assertFalse(Languages.isRtl("en"))
        assertTrue(Translator.build("ar", null, emptyMap()).rtl)
    }

    @Test
    fun `the saved setting wins over the system`() {
        assertEquals("ar", Languages.resolve("ar", listOf("es-CL")))
        assertEquals("es-419", Languages.resolve("es-419", listOf("ar-EG")))
    }

    @Test
    fun `an empty or unknown saved value follows the system`() {
        assertEquals("ar", Languages.resolve("", listOf("ar-SA")))
        assertEquals("ar", Languages.resolve(null, listOf("ar-MA")))
        assertEquals("es-419", Languages.resolve("xx", listOf("es")))
    }

    @Test
    fun `system tags map onto the shipped regional catalogs`() {
        assertEquals("es-419", Languages.match("es-CL"))
        assertEquals("es-419", Languages.match("es-MX"))
        assertEquals("es-419", Languages.match("es-419"))
        assertEquals("es-419", Languages.match("es-ES"))
        assertEquals("es-419", Languages.match("es"))
        assertNull(Languages.match("pt-BR"))
        assertNull(Languages.match("zh-CN"))
    }

    @Test
    fun `a language matches whatever its region`() {
        assertEquals("ar", Languages.match("ar-EG"))
        assertEquals("ar", Languages.match("ar_SA"))
        assertEquals("en", Languages.match("en-GB"))
        assertNull(Languages.match("de-AT"))
    }

    @Test
    fun `the system list is walked in order and english is the last resort`() {
        assertEquals("ar", Languages.resolve("", listOf("nl-NL", "ar-EG", "es-MX")))
        assertEquals("es-419", Languages.resolve("", listOf("ja-JP", "es-ES")))
        assertEquals("en", Languages.resolve("", listOf("nl-NL", "sv-SE")))
        assertEquals("en", Languages.resolve("", emptyList()))
        assertEquals("en", Languages.resolve("", listOf("")))
    }

    @Test
    fun `the pseudo-locale resolves only when a debug build allows it`() {
        assertEquals("en", Languages.resolve("en-XA", listOf("en-US")))
        assertEquals("en-XA", Languages.resolve("en-XA", listOf("en-US"), pseudo = true))
        assertEquals("en", Languages.resolve("", listOf("en-XA")))
        assertEquals("en-XA", Languages.resolve("", listOf("en-XA", "es"), pseudo = true))
    }
}
