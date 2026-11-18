package com.tmplayer.online

import com.tmplayer.data.MediaItem
import com.tmplayer.data.SeriesShelf
import com.tmplayer.data.ShelfEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue 31: names and stills for the seasons past the first. A chat that counts the way the
 * provider does keeps working; one that counts straight through gets its names too.
 */
class EpisodeNumberingTest {

    private var nextId = 1L

    private fun video(fileName: String) = MediaItem(
        chatId = 7, messageId = nextId++, fileId = 0, title = fileName, sizeBytes = 1, durationSec = 60,
        mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = nextId.toInt(),
        fileName = fileName, caption = "",
    )

    private fun showOf(vararg names: String) =
        SeriesShelf.arrange(names.map(::video)).filterIsInstance<ShelfEntry.Show>().single().series

    /** Three seasons of twelve, the way TMDB lists them. */
    private val layout = listOf(MetaSeason(1, 12), MetaSeason(2, 12), MetaSeason(3, 10))

    private fun listOfSeason(season: Int, count: Int) =
        (1..count).map { MetaEpisode(season, it, name = "S$season name $it", stillUrl = "https://image.tmdb.org/t/p/w300/s$season$it.jpg") }

    private val lists = layout.associate { it.number to listOfSeason(it.number, it.episodeCount) }

    private fun numbersOf(show: com.tmplayer.data.Series, season: Int) =
        show.seasons.first { it.number == season }.episodes.map { it.episode }

    @Test
    fun aSeasonCountedFromOneIsLeftAlone() {
        val show = showOf(
            "Show.Name.S01E01.1080p.mkv", "Show.Name.S01E02.1080p.mkv",
            "Show.Name.S02E03.1080p.mkv", "Show Name 2x04 720p.mkv", "Show Name Season 2 Episode 5.mkv",
        )
        val numbers = numbersOf(show, 2)
        assertEquals(listOf(3, 4, 5), numbers)
        assertFalse(EpisodeNumbering.counted(2, numbers, layout))
        assertEquals(listOf(2), EpisodeNumbering.seasonsFor(2, numbers, layout))
        assertEquals(2 to 4, EpisodeNumbering.place(2, 4, numbers, layout))
        assertEquals(lists.getValue(2), EpisodeNumbering.rekey(2, numbers, layout, lists))
    }

    @Test
    fun aSecondSeasonThatContinuesTheNumbersGetsItsNamesAndStills() {
        val show = showOf(
            "Show.Name.S01E01.mkv", "Show.Name.S01E12.mkv",
            "Show.Name.S02E13.mkv", "Show.Name.S02E14.mkv", "Show.Name.S02E24.mkv",
        )
        val numbers = numbersOf(show, 2)
        assertEquals(listOf(13, 14, 24), numbers)
        assertTrue(EpisodeNumbering.counted(2, numbers, layout))
        assertEquals(2 to 1, EpisodeNumbering.place(2, 13, numbers, layout))
        assertEquals(2 to 12, EpisodeNumbering.place(2, 24, numbers, layout))
        assertEquals(listOf(2), EpisodeNumbering.seasonsFor(2, numbers, layout))
        val named = EpisodeNumbering.rekey(2, numbers, layout, mapOf(2 to lists.getValue(2)))
        assertEquals("S2 name 1", EpisodeNames.listName(2, 13, named))
        assertEquals("S2 name 12", EpisodeNames.listName(2, 24, named))
        assertEquals("https://image.tmdb.org/t/p/w300/s21.jpg", EpisodeNames.listed(2, 13, named)?.stillUrl)
        assertNull("the provider's own number is not the chat's", EpisodeNames.listName(2, 1, named))
        // Season one still reads as it did.
        assertFalse(EpisodeNumbering.counted(1, numbersOf(show, 1), layout))
    }

    @Test
    fun aShowWithNoSeasonAtAllIsPlacedAcrossTheProvidersSeasons() {
        val show = showOf("Show Name Ep 01.mkv", "Show Name Ep 02.mkv", "Show Name Ep 13.mkv", "Show Name Ep 25.mkv")
        val numbers = numbersOf(show, 1)
        assertEquals(listOf(1, 2, 13, 25), numbers)
        // Two of four lie past season one's twelve: counted straight through.
        assertTrue(EpisodeNumbering.counted(1, numbers, layout))
        assertEquals(1 to 2, EpisodeNumbering.place(1, 2, numbers, layout))
        assertEquals(2 to 1, EpisodeNumbering.place(1, 13, numbers, layout))
        assertEquals(3 to 1, EpisodeNumbering.place(1, 25, numbers, layout))
        assertEquals(listOf(1, 2, 3), EpisodeNumbering.seasonsFor(1, numbers, layout))
        val named = EpisodeNumbering.rekey(1, numbers, layout, lists)
        assertEquals(listOf(1, 2, 13, 25), named.map { it.number })
        assertTrue(named.all { it.season == 1 })
        assertEquals("S2 name 1", EpisodeNames.listName(1, 13, named))
        assertEquals("S3 name 1", EpisodeNames.listName(1, 25, named))
        assertEquals("S1 name 2", EpisodeNames.listName(1, 2, named))
    }

    @Test
    fun specialsAMissingSeasonAndAStrayNumberAreNotReplaced() {
        val special = listOf(1, 2, 3)
        assertFalse("specials are the chat's own", EpisodeNumbering.counted(0, listOf(40), layout))
        assertEquals(0 to 40, EpisodeNumbering.place(0, 40, listOf(40), layout))
        assertFalse("a season the provider does not list", EpisodeNumbering.counted(9, listOf(40), layout))
        assertFalse("one stray number in a season", EpisodeNumbering.counted(2, special + (1..9) + 13, layout))
        assertFalse("no layout", EpisodeNumbering.counted(2, listOf(13, 14), emptyList()))
        assertFalse("empty", EpisodeNumbering.counted(2, emptyList(), layout))
        assertNull("past every season", EpisodeNumbering.place(1, 500, listOf(500, 499), layout))
        assertNull(EpisodeNumbering.place(1, 0, listOf(1), layout))
        assertNull(EpisodeNumbering.place(1, Int.MAX_VALUE, listOf(1), layout))
    }

    @Test
    fun aHugeShowAsksForABoundedNumberOfSeasons() {
        val big = (1..40).map { MetaSeason(it, 25) }
        val numbers = (1..500).toList()
        assertTrue(EpisodeNumbering.counted(1, numbers, big))
        val asked = EpisodeNumbering.seasonsFor(1, numbers, big)
        assertEquals(EpisodeNumbering.MAX_SEASONS, asked.size)
        assertEquals((1..EpisodeNumbering.MAX_SEASONS).toList(), asked)
        // A garbage layout is bounded too.
        val absurd = (1..5000).map { MetaSeason(it, 1) }
        assertEquals(EpisodeNumbering.MAX_SEASONS, EpisodeNumbering.seasonsFor(1, (1..5000).toList(), absurd).size)
    }
}
