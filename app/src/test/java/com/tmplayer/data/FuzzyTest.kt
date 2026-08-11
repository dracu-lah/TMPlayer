package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyTest {

    @Test
    fun `normalize folds case, accents and punctuation`() {
        assertEquals("pokemon", Fuzzy.normalize("Pokémon"))
        assertEquals("the office us", Fuzzy.normalize("The.Office_(US)"))
        assertEquals("breaking bad s01e01", Fuzzy.normalize("  Breaking   Bad - S01E01  "))
    }

    @Test
    fun `a typo still finds the show`() {
        assertTrue(Fuzzy.score("Friends S03E12.mkv", "freinds") > 0)
        assertTrue(Fuzzy.score("Severance", "sevrance") > 0)
    }

    @Test
    fun `a typo in a short word is a different word`() {
        // Three letters one edit apart are not a near miss, they are two ordinary words.
        assertEquals(0, Fuzzy.score("The Bear", "she"))
    }

    @Test
    fun `one wrong keyword among right ones is forgiven`() {
        // Two of the three land, which clears the half-of-them bar.
        assertTrue(Fuzzy.score("Breaking Bad Season 1", "breaking bad season") > 0)
        assertTrue(Fuzzy.score("Breaking Bad Season 1", "breaking bad zebra") > 0)
    }

    @Test
    fun `mostly wrong keywords are not a match`() {
        assertEquals(0, Fuzzy.score("Breaking Bad Season 1", "breaking zebra giraffe walrus"))
    }

    @Test
    fun `accents and case do not have to be typed`() {
        assertTrue(Fuzzy.score("Amélie (1080p).mkv", "amelie") > 0)
        assertTrue(Fuzzy.score("THE MATRIX", "matrix") > 0)
    }

    @Test
    fun `a blank query returns everything, in the order it arrived`() {
        val items = listOf("b", "a", "c")
        assertEquals(items, Fuzzy.rank(items, "   ") { it })
    }

    @Test
    fun `exact matches rank above partial ones`() {
        val items = listOf(
            "Bad Boys",
            "Breaking Bad Season 2",
            "Breaking Bad Season 1",
        )
        val ranked = Fuzzy.rank(items, "breaking bad") { it }
        // Both seasons carry the whole query as a run of characters; "Bad Boys" shares one word
        // out of two, which clears the bar but scores far lower.
        assertEquals("Breaking Bad Season 2", ranked[0])
        assertEquals("Breaking Bad Season 1", ranked[1])
        assertEquals("Bad Boys", ranked[2])
    }

    @Test
    fun `ranking is stable within a score`() {
        val items = listOf("Dune Part Two", "Dune Part One")
        assertEquals(items, Fuzzy.rank(items, "dune part") { it })
    }

    @Test
    fun `nothing matching drops out of the ranking entirely`() {
        val ranked = Fuzzy.rank(listOf("Chernobyl", "Fargo"), "succession") { it }
        assertTrue(ranked.isEmpty())
    }

    @Test
    fun `one typo covers insertion, deletion, substitution and a swapped pair`() {
        assertTrue(Fuzzy.nearlyEqual("friends", "frends"))
        assertTrue(Fuzzy.nearlyEqual("matrix", "matrx"))
        assertTrue(Fuzzy.nearlyEqual("matrix", "matrixx"))
        assertTrue(Fuzzy.nearlyEqual("matrix", "matrux"))
        // The commonest mistake of all: two neighbours the wrong way round.
        assertTrue(Fuzzy.nearlyEqual("friends", "freinds"))
        assertFalse(Fuzzy.nearlyEqual("matrix", "mtrx"))
        assertFalse(Fuzzy.nearlyEqual("matrix", "wildly different"))
        // Two letters swapped, but not neighbours: a different word.
        assertFalse(Fuzzy.nearlyEqual("abcd", "dbca"))
    }

    @Test
    fun `ranking maps through a key rather than requiring strings`() {
        data class Row(val id: Int, val title: String)

        val rows = listOf(Row(1, "Andor"), Row(2, "The Wire"), Row(3, "Andor Season 2"))
        val ranked = Fuzzy.rank(rows, "andor") { it.title }
        assertEquals(listOf(1, 3), ranked.map { it.id })
    }
}
