package com.tmplayer.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * N3: the spellings one title goes by in the Malayalam survey (private/name-survey, MSB and Cinema
 * Paradiso, 2026-10-31) share a [MediaName.spellingKey]; two different titles never do.
 */
class SpellingKeyTest {

    private fun key(title: String) = MediaName.spellingKey(title)

    private fun assertOneTitle(vararg spellings: String) {
        val keys = spellings.associateWith { key(it) }
        assertEquals(1, keys.values.toSet().size, "one title, several keys: $keys")
    }

    private fun assertTwoTitles(a: String, b: String) = assertNotEquals(key(a), key(b), "\"$a\" and \"$b\" are two titles")

    @Test
    fun `the four spellings of Aniyathipraavu are one`() {
        assertOneTitle("Aniyathipraavu", "Aniyathi Pravu", "Aniyathipravu", "ANIYATHI-PRAAVU", "Aniyathi.Praavu")
    }

    @Test
    fun `Kathanayakan Kadhanayakan and Katha Nayagan are one`() {
        assertOneTitle("Kathanayakan", "Kadhanayakan", "Katha Nayagan", "Katha Nayakan")
    }

    @Test
    fun `Aarya 2 and Arya 2 are one, the sequel number stays`() {
        assertOneTitle("Aarya 2", "Arya 2", "Aarya2", "Aarya-2")
        assertTwoTitles("Aarya 2", "Aarya")
        assertTwoTitles("Aarya 2", "Aarya 3")
        assertTwoTitles("Arya 2", "Arya 22")
        assertEquals("arya 2", key("Aarya 2"))
    }

    @Test
    fun `Aaram Thampuran and Aaram Thamburan are one`() {
        assertOneTitle("Aaram Thampuran", "Aaram Thamburan", "Aaramthampuran")
    }

    @Test
    fun `Swapnakkoodu and Swapnakoodu are one`() {
        assertOneTitle("Swapnakkoodu", "Swapnakoodu", "Swapna Koodu")
    }

    @Test
    fun `a title with or without its space is one`() {
        assertOneTitle("Thalayana Manthram", "Thalayanamanthram", "Thalayana-Manthram")
    }

    @Test
    fun `case, accents and punctuation do not matter`() {
        assertOneTitle("Amélie Poulain", "AMELIE POULAIN", "amelie.poulain")
    }

    @Test
    fun `two different titles stay two`() {
        // In the survey itself: one letter apart, and a different name.
        assertTwoTitles("Avalude Ravukal", "Avarude Ravukal")
        assertTwoTitles("Salam", "Lal Salam")
        assertTwoTitles("Premam", "Premalu")
        // A sequel number or a subtitle is a different title.
        assertTwoTitles("Baahubali", "Baahubali 2 The Conclusion")
        assertTwoTitles("Honey Bee", "Honey Bee 2")
        assertTwoTitles("Aadu", "Aadu 2")
        assertTwoTitles("Thalayana Manthram", "Thalayana Manthram 2")
        // A short title is never folded: the voiced and plain pair would make these one.
        assertTwoTitles("Bad", "Pad")
        assertTwoTitles("Don", "Ton")
        assertTwoTitles("Thor", "Tor")
        assertTwoTitles("Moon", "Mon")
        assertTwoTitles("Gold", "Cold")
        // Words that differ in a letter this key keeps (vowels, l and r, m and n).
        assertTwoTitles("Kathanayakan", "Kathanayikan")
        assertTwoTitles("Godzilla", "Godzilla Minus One")
    }

    @Test
    fun `a title in another script is its plain key`() {
        val malayalam = "മിഥുനം"
        assertEquals(SeriesShelf.keyOf("Mithunam $malayalam"), key("Mithunam $malayalam"))
        assertEquals(SeriesShelf.keyOf("Mithunam $malayalam"), key("MITHUNAM $malayalam"))
        assertEquals("", key(""))
        assertEquals("", key("   "))
        assertEquals("", key("​\u0000"))
    }

    @Test
    fun `a name of digits is left as it is`() {
        assertEquals("96", key("96"))
        assertEquals("1994", key("1994"))
        assertEquals("7 9 3", key("7 9 3"))
    }

    @Test
    fun `the key is a function of the name the parser reads`() {
        // The titles are what MediaName.parse gives, so "DVDWO", a handle and a year drop out first.
        val names = listOf(
            "DVDWO Aniyathipraavu (1997).mkv", "@CC_Aniyathi_Pravu.mkv", "Aniyathipravu 1997 DVDRip.avi",
        )
        assertOneTitle(*names.map { MediaName.parse(it, maxYear = 2027).title }.toTypedArray())
    }

    @Test
    fun `huge and odd input is handled`() {
        key("a".repeat(100_000))
        key("ab ".repeat(30_000) + "9".repeat(30_000))
        key("😀".repeat(1_000))
        key("h".repeat(10_000) + "th".repeat(1_000))
        key("\uD800")
    }
}
