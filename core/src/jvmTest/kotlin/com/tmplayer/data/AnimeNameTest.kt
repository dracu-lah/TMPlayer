package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The names an anime's files take (T11): absolute numbers, versions, ranges, cours, specials. */
class AnimeNameTest {

    private fun parse(name: String) = MediaName.parse(name)

    private fun check(name: String, title: String, season: Int?, episode: Int?, last: Int? = null) {
        val p = parse(name)
        assertEquals("title of $name", title, p.title)
        assertEquals("season of $name", season, p.season)
        assertEquals("episode of $name", episode, p.episode)
        assertEquals("last of $name", last, p.lastEpisode)
    }

    @Test
    fun anAbsoluteNumberAfterADash() {
        check("[SubsPlease] Naruto Shippuden - 123 (1080p) [ABCD1234].mkv", "Naruto Shippuden", null, 123)
        check("[Group] Naruto Shippuden - 123 [1080p].mkv", "Naruto Shippuden", null, 123)
        check("[Group] Naruto Shippuden - 500 [1080p HEVC].mkv", "Naruto Shippuden", null, 500)
        check("[Group] One Piece - 1080 [1080p].mkv", "One Piece", null, 1080)
        check("[Group] Mob Psycho 100 - 05 [1080p].mkv", "Mob Psycho 100", null, 5)
        check("[Group] 86 - Eighty Six - 05 [1080p].mkv", "86 - Eighty Six", null, 5)
    }

    @Test
    fun aVersionIsTheSameEpisode() {
        check("[Group] Naruto Shippuden - 123v2 [1080p].mkv", "Naruto Shippuden", null, 123)
        check("[Group] Naruto Shippuden - 123v3 [1080p].mkv", "Naruto Shippuden", null, 123)
        check("Naruto Shippuden 123v2.mkv", "Naruto Shippuden", null, 123)
        check("[Group] Naruto Shippuden 123v2 [720p].mkv", "Naruto Shippuden", null, 123)
    }

    @Test
    fun aBareNumberAfterATitleIsLeftToTheChat() {
        // "Mob Psycho 100" and "Naruto Shippuden 123" read the same; only the chat's other files tell them apart.
        assertNull(parse("Naruto Shippuden 123.mkv").episode)
        assertNull(parse("Mob Psycho 100.mkv").episode)
    }

    @Test
    fun aSeasonBeforeTheDashedNumber() {
        check("[SubsPlease] Sky Garden S2 - 05 (1080p).mkv", "Sky Garden", 2, 5)
        check("[Group] Sky Garden Season 2 - 05.mkv", "Sky Garden", 2, 5)
        check("[Group] Sky Garden S02 - 05v2 [1080p].mkv", "Sky Garden", 2, 5)
        check("[Group] Sky Garden - S01E05 - Title [1080p].mkv", "Sky Garden", 1, 5)
    }

    @Test
    fun aCourSplitKeepsItsWordsInTheTitleForTheProvidersEntry() {
        // AniList lists each cour and each season as an entry of its own, named so.
        check("[Group] Sky Garden 2nd Season - 05 [720p].mkv", "Sky Garden 2nd Season", null, 5)
        check("[Group] Sky Garden Part 2 - 13 [1080p].mkv", "Sky Garden Part 2", null, 13)
        check("[Group] Sky Garden Cour 2 - 13 [1080p].mkv", "Sky Garden Cour 2", null, 13)
    }

    @Test
    fun twoEpisodesInOneFile() {
        check("[Group] Sky Garden - 12-13 [1080p].mkv", "Sky Garden", null, 12, last = 13)
        check("[Group] Sky Garden - 12~13 [1080p].mkv", "Sky Garden", null, 12, last = 13)
        check("[Group] Sky Garden - 05 END [1080p].mkv", "Sky Garden", null, 5)
        // The same reading the series pages use for S01E01-E02.
        assertEquals(2, parse("Show.S01E01-E02.mkv").lastEpisode)
    }

    @Test
    fun oneDigitOnlyAFansubReadsAsAnEpisode() {
        check("[Group] Sky Garden - 5 [BD 1080p HEVC FLAC].mkv", "Sky Garden", null, 5)
        assertNull("no group, no episode: this is a title", parse("Sky Garden Part - 2.mkv").episode)
    }

    @Test
    fun specialsAreSeasonZero() {
        check("[Group] Sky Garden - OVA 02 [1080p].mkv", "Sky Garden", 0, 2)
        check("[Group] Sky Garden OVA2 [1080p].mkv", "Sky Garden", 0, 2)
        check("[Group] Sky Garden - OVA [1080p].mkv", "Sky Garden", 0, 1)
        check("[Group] Sky Garden - SP01 [1080p].mkv", "Sky Garden", 0, 1)
        check("[Group] Sky Garden - Special 3.mkv", "Sky Garden", 0, 3)
        check("[Group] Sky Garden - Recap 1.mkv", "Sky Garden", 0, 1)
        assertEquals("a show with OVA in its name is not an OVA", null, parse("Sky OVA Garden.mkv").season)
    }

    @Test
    fun creditlessOpeningsAreExtrasNotEpisodes() {
        for (name in listOf("[Group] Sky Garden - NCOP1 [1080p].mkv", "[Group] Sky Garden - NCED [1080p].mkv", "[Group] Sky Garden - Creditless OP.mkv")) {
            val p = parse(name)
            assertFalse(name, p.isEpisode)
            assertNotNull(name, p.extra)
        }
    }

    @Test
    fun theRangeIsTheOneSeriesPagesUse() {
        val p = parse("[Group] Sky Garden - 12-13 [1080p].mkv")
        assertEquals(12, p.episode)
        assertEquals(13, p.lastEpisode)
        assertEquals("the same video as the shelf files it", "Sky Garden", p.title)
    }

    @Test
    fun garbageNeverThrowsAndStaysInRange() {
        val rng = kotlin.random.Random(7)
        val parts = listOf("[Group]", "[", "]", " - ", "OVA", "SP", "v2", "v", "99999", "0", "-", "~", "5", "123", "Season", "S2", "END", "(", ")", "NCOP", "Show", "日本語", "\u0000", "‮", "٣")
        repeat(20_000) {
            val name = buildString { repeat(rng.nextInt(1, 12)) { append(parts.random(rng)); if (rng.nextBoolean()) append(' ') } } + ".mkv"
            val p = parse(name)
            p.episode?.let { assertTrue(name, it in 0..9999) }
            p.lastEpisode?.let { last -> assertTrue("$name last", p.episode != null && last > p.episode!!) }
        }
        val huge = "[Group] " + "Sky Garden ".repeat(50_000) + " - 05 [1080p].mkv"
        val long = parse(huge)
        assertTrue(long.title.isNotEmpty())
        parse("[Group] " + "- 1 ".repeat(100_000))
        parse("OVA".repeat(100_000))
    }
}
