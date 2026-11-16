package com.tmplayer.data

import com.tmplayer.data.EpisodeGuide.Row
import com.tmplayer.data.EpisodeGuide.State
import com.tmplayer.online.MetaAiring
import com.tmplayer.online.MetaEpisode
import com.tmplayer.online.MetaSeason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The merge of what the metadata knows with what the chats hold (T9): every season and episode
 * listed, each Available (a video), Missing (known, not in the chats) or Upcoming (not aired).
 */
class SeriesGuideTableTest {

    private val today = "2026-11-16"

    private fun video(id: Long, name: String) = MediaItem(
        chatId = 1, messageId = id, fileId = id.toInt(), title = name, sizeBytes = 100, durationSec = 60,
        mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null, date = id.toInt(), fileName = name,
    )

    private fun show(codes: List<String>, title: String = "Harbour.Notes"): Series {
        val items = codes.mapIndexed { at, code -> video(at + 1L, "$title.$code.mkv") }
        return EpisodeGuide.seriesOf(items.first(), items) ?: error("no series for $codes")
    }

    private fun sxe(season: Int, episodes: Iterable<Int>) = episodes.map { "S%02dE%02d".format(season, it) }

    private fun states(rows: List<Row>) = rows.map {
        when (it) {
            is Row.Have -> "have"
            is Row.Gap -> if (it.episode.state == State.Missing) "missing" else "unaired"
            is Row.Pack -> "pack"
        }
    }

    private fun numbers(rows: List<Row>) = rows.map { if (it is Row.Have) it.episode.episode else (it as Row.Gap).episode.number }

    @Test
    fun `two of six seasons present list all six`() {
        val series = show(sxe(1, 1..3) + sxe(4, 1..2))
        val listed = (1..6).map { MetaSeason(it, 10, if (it == 6) "2027-03-01" else "20%02d-01-01".format(20 + it)) }
        val seasons = EpisodeGuide.seasons(series, listed, null, today)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), seasons.map { it.number })
        assertEquals(
            listOf(State.InChat, State.Missing, State.Missing, State.InChat, State.Missing, State.Upcoming),
            seasons.map { it.state },
        )
        assertTrue(EpisodeGuide.agrees(series, listed))
    }

    @Test
    fun `an unaired season is upcoming with its date`() {
        val series = show(sxe(1, 1..2))
        val listed = listOf(MetaSeason(1, 10, "2024-01-01"), MetaSeason(2, 10, "2027-02-01"))
        val seasons = EpisodeGuide.seasons(series, listed, null, today)
        assertEquals(State.Upcoming, seasons[1].state)
        assertEquals("2027-02-01", seasons[1].airDate)
        val rows = EpisodeGuide.rows(2, series, null, today, listed[1])
        assertEquals(List(10) { "unaired" }, states(rows))
    }

    @Test
    fun `five of ten episodes present show the five gaps by number`() {
        val series = show(sxe(1, listOf(1, 3, 5, 7, 9)))
        val rows = EpisodeGuide.rows(1, series, null, today, MetaSeason(1, 10, "2024-01-01"))
        assertEquals(listOf("have", "missing", "have", "missing", "have", "missing", "have", "missing", "have", "missing"), states(rows))
        assertEquals((1..10).toList(), numbers(rows))
        assertEquals("S01E02", (rows[1] as Row.Gap).episode.code)
    }

    @Test
    fun `the provider's episode list gives names and air dates, unaired ones keep their date`() {
        val series = show(sxe(1, 1..2))
        val listed = listOf(
            MetaEpisode(1, 1, "One", airDate = "2024-01-01"),
            MetaEpisode(1, 2, "Two", airDate = "2024-01-08"),
            MetaEpisode(1, 3, "Three", airDate = "2024-01-15"),
            MetaEpisode(1, 4, "Four", airDate = "2027-01-01"),
        )
        val rows = EpisodeGuide.rows(1, series, listed, today, MetaSeason(1, 4, "2024-01-01"))
        assertEquals(listOf("have", "have", "missing", "unaired"), states(rows))
        assertEquals("Three", (rows[2] as Row.Gap).episode.meta?.name)
        assertEquals("2027-01-01", (rows[3] as Row.Gap).episode.airDate)
        assertEquals("One", (rows[0] as Row.Have).meta?.name)
    }

    @Test
    fun `the next episode marks the rest of a running season as not aired`() {
        val series = show(sxe(3, 1..3))
        val next = MetaAiring(3, 6, "2026-12-01")
        val rows = EpisodeGuide.rows(3, series, null, today, MetaSeason(3, 8, "2026-09-01"), next)
        // 4 and 5 aired and are not here; 6 airs on 12-01; 7 and 8 follow it.
        assertEquals(listOf("have", "have", "have", "missing", "missing", "unaired", "unaired", "unaired"), states(rows))
        assertEquals("2026-12-01", (rows[5] as Row.Gap).episode.airDate)
    }

    @Test
    fun `specials are listed last and only when the chat holds one`() {
        val series = show(listOf("S01E01", "S01E02", "S00E01"))
        val listed = listOf(MetaSeason(1, 2, "2024-01-01"))
        val seasons = EpisodeGuide.seasons(series, listed, null, today)
        assertEquals(listOf(1, 0), seasons.map { it.number })
        assertTrue(seasons.last().special)
        val none = show(listOf("S01E01", "S01E02"))
        assertEquals(listOf(1), EpisodeGuide.seasons(none, listed, null, today).map { it.number })
        // The specials list has only what the chat holds when the provider gave no count.
        assertEquals(listOf("have"), states(EpisodeGuide.rows(0, series, null, today)))
    }

    @Test
    fun `a two part finale in one file is not missing its second half`() {
        val series = show(listOf("S01E01", "S01E02", "S01E03E04"))
        val rows = EpisodeGuide.rows(1, series, null, today, MetaSeason(1, 4, "2024-01-01"))
        assertEquals(listOf("have", "have", "have"), states(rows))
        // Two files for the two halves are two present rows.
        val two = show(sxe(1, 1..4))
        assertEquals(List(4) { "have" }, states(EpisodeGuide.rows(1, two, null, today, MetaSeason(1, 4, "2024-01-01"))))
        // A finale posted as "E04a" and "E04b" is one number, both halves listed as rows.
        val halves = show(listOf("S01E03", "S01E04a", "S01E04b"))
        assertEquals(listOf("missing", "missing", "have", "have", "have", "missing"), states(EpisodeGuide.rows(1, halves, null, today, MetaSeason(1, 5, "2024-01-01"))))
    }

    @Test
    fun `a wrong match does not agree, so nothing is called missing`() {
        // The chat holds three seasons of ten; the provider's show has two of six: another show.
        val series = show(sxe(1, 1..10) + sxe(2, 1..10) + sxe(3, 1..10))
        val listed = listOf(MetaSeason(1, 6, "2020-01-01"), MetaSeason(2, 6, "2021-01-01"))
        assertFalse(EpisodeGuide.agrees(series, listed))
    }

    @Test
    fun `absolute numbering of an anime does not agree`() {
        val series = show(sxe(1, 1..30))
        val listed = listOf(MetaSeason(1, 12, "2020-01-01"), MetaSeason(2, 12, "2021-01-01"), MetaSeason(3, 12, "2022-01-01"))
        assertFalse(EpisodeGuide.agrees(series, listed))
    }

    @Test
    fun `one season numbered another way keeps its own list and the others still show gaps`() {
        val series = show(sxe(1, 1..40) + sxe(2, 1..12))
        val listed = listOf(MetaSeason(1, 45, "2020-01-01"), MetaSeason(2, 10, "2021-01-01"))
        assertTrue(EpisodeGuide.agrees(series, listed))
        // Season 2 runs past its ten: the chat's list alone, none of it missing.
        assertEquals(List(12) { "have" }, states(EpisodeGuide.rows(2, series, null, today, listed[1])))
        assertEquals(5, states(EpisodeGuide.rows(1, series, null, today, listed[0])).count { it == "missing" })
    }

    @Test
    fun `with no metadata the page is as it was`() {
        val series = show(sxe(1, 1..2))
        assertTrue(EpisodeGuide.agrees(series, emptyList()))
        assertEquals(listOf(1), EpisodeGuide.seasons(series, emptyList(), null, today).map { it.number })
        assertEquals(listOf("have", "have"), states(EpisodeGuide.rows(1, series, null, today)))
        assertEquals(emptyList<Row>(), EpisodeGuide.rows(2, series, null, today))
    }

    @Test
    fun `garbage and huge answers stay bounded`() {
        val series = show(sxe(1, 1..2))
        val garbage = listOf(
            MetaSeason(-3, 5), MetaSeason(0, 99), MetaSeason(1, Int.MAX_VALUE), MetaSeason(1, 7), MetaSeason(Int.MAX_VALUE, 4),
            MetaSeason(2, -9, "not a date"), MetaSeason(3, 0, ""),
        ) + (1..5000).map { MetaSeason(it, 10, "2020-01-01") }
        val started = System.nanoTime()
        val seasons = EpisodeGuide.seasons(series, garbage, MetaAiring(Int.MAX_VALUE, -1, ""), today)
        assertTrue(seasons.size <= EpisodeGuide.MAX_SEASONS + 1)
        assertTrue(seasons.none { it.number < 0 })
        val info = MetaSeason(1, Int.MAX_VALUE)
        val listed = listOf(MetaEpisode(1, 0, "zero"), MetaEpisode(1, -4, "neg"), MetaEpisode(1, Int.MAX_VALUE, "huge"), MetaEpisode(9, 3, "other season"), MetaEpisode(1, 3, "ok"))
        val rows = EpisodeGuide.rows(1, series, listed, today, info, MetaAiring(1, Int.MAX_VALUE, "x"))
        assertTrue(rows.size <= EpisodeGuide.MAX_EPISODES * 2)
        assertEquals(listOf(1, 2, 3), numbers(rows))
        EpisodeGuide.agrees(series, garbage)
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", (System.nanoTime() - started) < 2_000_000_000L)
    }

    @Test
    fun `twenty seasons and a thousand episodes list cheaply from counts alone`() {
        val held = (1..20).flatMap { season -> sxe(season, 1..3) }
        val series = show(held)
        val listed = (1..20).map { MetaSeason(it, 50, "20%02d-01-01".format(it)) }
        val started = System.nanoTime()
        val seasons = EpisodeGuide.seasons(series, listed, null, today)
        assertEquals(20, seasons.size)
        assertTrue(seasons.all { it.state == State.InChat })
        // One long season of a thousand episodes, from its count: no list, no request per row.
        val long = EpisodeGuide.rows(1, series, null, today, MetaSeason(1, 1000, "2000-01-01"))
        assertEquals(1000, long.size)
        assertEquals(997, long.count { it is Row.Gap })
        assertNotNull(long.firstOrNull { it is Row.Have })
        assertTrue((System.nanoTime() - started) < 2_000_000_000L)
    }
}
