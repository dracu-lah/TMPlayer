package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

/** A whole season posted as one file: the parser's [ParsedName.pack], and where the show's page lists it (TS). */
class SeasonPackTest {

    private var nextId = 1L

    private fun video(fileName: String, caption: String = "") = MediaItem(
        chatId = 7, messageId = nextId++, fileId = 0, title = fileName, sizeBytes = 1, durationSec = 60,
        mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = nextId.toInt(),
        fileName = fileName, caption = caption,
    )

    private fun episodes(vararg numbers: Int, season: Int = 1) = numbers.map { video("Harbour.Notes.S0${season}E0$it.720p.mkv") }

    private fun show(items: List<MediaItem>) = SeriesShelf.arrange(items).filterIsInstance<ShelfEntry.Show>().single().series

    @Test
    fun `the parser calls a season with no episode a pack`() {
        val packs = listOf(
            "Harbour Notes Season 2 Complete 1080p.mkv" to 2,
            "Harbour.Notes.S02.COMPLETE.1080p.WEB-DL.mkv" to 2,
            "Harbour Notes S02 Combined.mkv" to 2,
            "Harbour Notes Season 3.mkv" to 3,
            "Harbour Notes S01-S03 1080p.mkv" to 1,
            "Harbour Notes Seasons 1-3 Complete.mkv" to 1,
            "Harbour Notes S01 to S03.mkv" to 1,
        )
        for ((name, first) in packs) {
            val parsed = MediaName.parse(name)
            assertTrue(name, parsed.pack)
            assertEquals(name, first, parsed.packSeason)
            assertEquals(name, "Harbour Notes", parsed.title)
            assertFalse(name, parsed.isEpisode)
        }
        assertNull(MediaName.parse("Harbour Notes S01-S03.mkv").season)
        assertEquals(3, MediaName.parse("Harbour Notes S01-S03.mkv").seasonEnd)
    }

    @Test
    fun `an episode, a range, a date, a trailer and a film are no pack`() {
        for (name in listOf(
            "Harbour.Notes.S02E04.mkv", "Harbour Notes S01E01-E13.mkv", "Harbour Notes S01 E01 & E02.mkv",
            "Harbour Notes 2024.10.14 720p.mkv", "Harbour Notes Season 2 Trailer.mkv", "Coast Run (2014) 1080p.mkv",
            "Harbour Notes - 04.mkv", "Harbour Notes Complete 1080p.mkv", "",
        )) {
            assertFalse(name, MediaName.parse(name).pack)
        }
    }

    @Test
    fun `a pack of a show the chat holds joins the show instead of standing alone`() {
        val pack = video("Harbour Notes Season 2 Complete 1080p.mkv")
        val entries = SeriesShelf.arrange(episodes(1, 2, 3) + pack)
        assertTrue("the pack is no loose tile", entries.none { it is ShelfEntry.File })
        val series = show(episodes(1, 2, 3) + pack)
        assertEquals(listOf(1, 2, 3), series.episodes.map { it.episode })
        assertEquals(3, series.episodeCount)
        assertEquals(listOf(2), series.packs.map { it.season })
        assertNull(series.packs.single().seasonEnd)
    }

    @Test
    fun `a pack of a title with no show stays a plain tile`() {
        val items = listOf(video("Studio.Sessions.S01E01.mkv"), video("Harbour Notes Season 2 Complete 1080p.mkv"))
        val entries = SeriesShelf.arrange(items)
        assertTrue(entries.none { it is ShelfEntry.Show })
        assertEquals(2, entries.size)
    }

    @Test
    fun `a pack that names its range fills the episodes it holds instead`() {
        val range = video("Harbour Notes S01E01-E04 1080p.mkv")
        val series = show(episodes(2, 3, 4, 5) + range)
        assertTrue(series.packs.isEmpty())
        assertEquals(listOf(range.id), series.episodes.first { it.episode == 2 }.alsoIn.map { it.id })
    }

    @Test
    fun `a span pack sits under its first season, two copies of a season are two rows`() {
        val span = video("Harbour Notes S01-S03 1080p.mkv")
        val a = video("Harbour Notes Season 2 Complete 720p.mkv")
        val b = video("Harbour Notes Season 2 Complete 1080p.mkv")
        val series = show(episodes(1, 2) + listOf(span, a, b))
        assertEquals(listOf(1, 2, 2), series.packs.map { it.season })
        assertEquals(3, series.packs.first().seasonEnd)
        // The newer copy of a season first.
        assertEquals(b.id, series.packs[1].item.id)
    }

    @Test
    fun `a pack set apart by the viewer, or titled otherwise, is not taken in`() {
        val pack = video("Harbour Notes Season 2 Complete 1080p.mkv")
        val other = video("Kitchen Journal Season 2 Complete 1080p.mkv")
        val apart = SeriesShelf.arrange(episodes(1, 2) + pack + other, mapOf(pack.id to VideoCorrection(separate = true)))
        assertEquals(3, apart.size)
        val series = show(episodes(1, 2) + other)
        assertTrue(series.packs.isEmpty())
    }

    @Test
    fun `the guide lists a pack after its season and a pack only season as held`() {
        val pack = video("Harbour Notes Season 2 Complete 1080p.mkv")
        val series = show(episodes(1, 2) + pack)
        val seasons = EpisodeGuide.seasons(series, emptyList(), null, "2026-11-16")
        assertEquals(listOf(1, 2), seasons.map { it.number })
        assertEquals(EpisodeGuide.State.InChat, seasons.first { it.number == 2 }.state)
        assertEquals(0, seasons.first { it.number == 2 }.inChat)
        val only = EpisodeGuide.rows(2, series, null, "2026-11-16")
        assertEquals(listOf(pack.id), only.map { (it as EpisodeGuide.Row.Pack).pack.item.id })
        val withEpisodes = show(episodes(1, 2) + video("Harbour Notes S01 Complete.mkv"))
        val rows = EpisodeGuide.rows(1, withEpisodes, null, "2026-11-16")
        assertEquals(3, rows.size)
        assertTrue(rows.last() is EpisodeGuide.Row.Pack)
        assertEquals(2, (rows.last() as EpisodeGuide.Row.Pack).after)
    }

    @Test
    fun `a pack is never an episode in the progress`() {
        val pack = video("Harbour Notes Season 2 Complete 1080p.mkv")
        val series = show(episodes(1, 2) + pack)
        val progress = SeriesShelf.progress(series, { false }, { 0f })
        assertEquals(2, progress.total)
        assertEquals(1, progress.next?.episode)
    }

    @Test
    fun `a large chat with many packs arranges inside the budget and drops none`() {
        val items = ArrayList<MediaItem>()
        repeat(300) { show ->
            repeat(8) { n -> items += video("Harbour Show $show S01E0${n + 1}.mkv") }
            repeat(5) { n -> items += video("Harbour Show $show Season ${n + 2} Complete ${n}p.mkv") }
        }
        repeat(14_000) { items += video("Film number $it (20${it % 25 + 10}).mkv") }
        var entries = emptyList<ShelfEntry>()
        val ms = measureTimeMillis { entries = SeriesShelf.arrange(items) }
        val shows = entries.filterIsInstance<ShelfEntry.Show>().map { it.series }
        assertEquals(300, shows.size)
        assertEquals(1500, shows.sumOf { it.packs.size })
        assertTrue("took $ms ms", ms < 5_000)
    }
}
