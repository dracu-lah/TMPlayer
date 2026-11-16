package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class DownloadShelfTest {

    private data class Row(val id: Long, val name: String, val at: Long, val size: Long = 1_000)

    private fun item(row: Row) = MediaItem(
        chatId = -100L,
        messageId = row.id,
        fileId = row.id.toInt(),
        title = row.name,
        sizeBytes = row.size,
        durationSec = 1_400,
        mimeType = "",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = 0,
        fileName = row.name,
        caption = "",
    )

    private fun arrange(vararg rows: Row) =
        DownloadShelf.arrange(rows.toList(), ::item, { "r${it.id}" }, { it.at }, corrections = emptyMap())

    private fun arranged(sort: DownloadShelf.Sort, vararg rows: Row) =
        DownloadShelf.arrange(rows.toList(), ::item, { "r${it.id}" }, { it.at }, corrections = emptyMap(), sort = sort, locale = java.util.Locale.ENGLISH)
            .map { if (it is DownloadShelf.Entry.Show) "show" else (it as DownloadShelf.Entry.Single).row.id.toString() }

    private val sortRows = arrayOf(
        Row(1, "The Zebra Run (2020).mkv", 40, size = 500),
        Row(2, "Harbour.Notes.S01E01.mkv", 30, size = 700),
        Row(3, "Anchor Point (2012).mkv", 20, size = 1_000),
        Row(4, "Harbour.Notes.S01E02.mkv", 10, size = 700),
    )

    @Test
    fun `newest is by when each was downloaded, a show by its newest episode`() {
        assertEquals(listOf("1", "show", "3"), arranged(DownloadShelf.Sort.Newest, *sortRows))
    }

    @Test
    fun `name ignores a leading article and puts a show under its title`() {
        assertEquals(listOf("3", "show", "1"), arranged(DownloadShelf.Sort.Name, *sortRows))
    }

    @Test
    fun `largest counts a show by all its episodes`() {
        assertEquals(listOf("show", "3", "1"), arranged(DownloadShelf.Sort.Largest, *sortRows))
    }

    @Test
    fun `rows downloaded in the same moment are ordered by key, whatever order they come in`() {
        val rows = arrayOf(Row(3, "Gamma (2021).mkv", 50), Row(1, "Alpha (2019).mkv", 50), Row(2, "Beta (2020).mkv", 50))
        val expected = listOf("1", "2", "3")
        assertEquals(expected, arranged(DownloadShelf.Sort.Newest, *rows))
        assertEquals(expected, arranged(DownloadShelf.Sort.Newest, *rows.reversedArray()))
        // A later row still goes first; the tie only decides among equals.
        assertEquals(listOf("4") + expected, arranged(DownloadShelf.Sort.Newest, *rows, Row(4, "Delta (2022).mkv", 60)))
    }

    @Test
    fun `a stored order reads back, an unknown one is newest`() {
        for (sort in DownloadShelf.Sort.entries) assertEquals(sort, DownloadShelf.Sort.decode(sort.name))
        assertEquals(DownloadShelf.Sort.Newest, DownloadShelf.Sort.decode("Sideways"))
    }

    @Test
    fun `episodes of one show are grouped in watching order`() {
        val shelf = arrange(
            Row(1, "Harbour.Notes.S01E02.1080p.mkv", 30),
            Row(2, "Some.Film.2024.1080p.mkv", 20),
            Row(3, "Harbour.Notes.S01E01.1080p.mkv", 10),
        )
        assertEquals(2, shelf.size)
        val show = shelf[0] as DownloadShelf.Entry.Show
        assertEquals(listOf(3L, 1L), show.episodes.map { it.id })
        assertEquals(30L, show.newest)
        assertTrue(shelf[1] is DownloadShelf.Entry.Single)
    }

    @Test
    fun `a lone episode stays a video of its own and singles run newest first`() {
        val shelf = arrange(
            Row(1, "Harbour.Notes.S01E02.1080p.mkv", 10),
            Row(2, "Some.Film.2024.1080p.mkv", 20),
        )
        assertEquals(listOf(2L, 1L), shelf.map { (it as DownloadShelf.Entry.Single).row.id })
    }

    private val zone = ZoneOffset.UTC
    private fun at(day: Int, hour: Int) = LocalDateTime.of(2026, 10, day, hour, 0).toInstant(zone).toEpochMilli()

    @Test
    fun `calendar days, not 24 hour spans`() {
        val now = at(18, 9)
        assertEquals(DayGroup.Today, DayGroup.of(at(18, 1), now, zone))
        assertEquals(DayGroup.Yesterday, DayGroup.of(at(17, 23), now, zone))
        assertEquals(DayGroup.Earlier, DayGroup.of(at(16, 23), now, zone))
        // A clock that ran ahead is still today.
        assertEquals(DayGroup.Today, DayGroup.of(at(19, 1), now, zone))
    }

    @Test
    fun `headings only when they help`() {
        val now = at(18, 9)
        val mixed = listOf(at(18, 8), at(18, 7), at(17, 20), at(10, 20))
        val groups = DayGroup.headings(mixed, { it }, now, zone)!!
        assertEquals(listOf(DayGroup.Today, DayGroup.Yesterday, DayGroup.Earlier), groups.map { it.first })
        assertEquals(2, groups[0].second.size)
        assertNull(DayGroup.headings(listOf(at(18, 8), at(18, 7)), { it }, now, zone))
        assertNull(DayGroup.headings(listOf(at(10, 8), at(18, 7)), { it }, now, zone))
    }
}
