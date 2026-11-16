package com.tmplayer.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The age rating on the facts line: a lone letter is a word or nothing, a longer rating is kept. */
class CertificationTextTest {

    private fun read(raw: String?) = CertificationText.readable(raw, adults = "Adults", everyone = "Everyone")

    @Test
    fun `a lone A is written as Adults and a lone U as Everyone`() {
        assertEquals("Adults", read("A"))
        assertEquals("Adults", read(" a "))
        assertEquals("Everyone", read("U"))
    }

    @Test
    fun `any other lone letter says nothing so it is left out`() {
        for (letter in listOf("S", "X", "R", "G", "7", "?")) assertNull(letter, read(letter))
    }

    @Test
    fun `a rating longer than a letter stays as it is`() {
        for (rating in listOf("PG-13", "TV-MA", "UA 16+", "18", "12A", "FSK 16")) assertEquals(rating, read(rating))
        assertEquals("PG-13", read("  PG-13  "))
    }

    @Test
    fun `nothing and blank are nothing, and garbage does not throw`() {
        assertNull(read(null))
        assertNull(read(""))
        assertNull(read("   "))
        assertEquals("x".repeat(10_000), read("x".repeat(10_000)))
    }
}
