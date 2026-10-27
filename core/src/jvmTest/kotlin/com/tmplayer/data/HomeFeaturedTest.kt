package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [HomeFeatured]: the phone's featured carousel, the newest five different things in the starred chats. */
class HomeFeaturedTest {

    private var nextId = 1L

    private fun video(fileName: String, date: Int, chatId: Long = 1, durationSec: Int = 6_000) = MediaItem(
        chatId = chatId,
        messageId = nextId++,
        fileId = nextId.toInt(),
        title = fileName,
        sizeBytes = 1_500_000_000,
        durationSec = durationSec,
        mimeType = "video/x-matroska",
        thumbnailFileId = 1,
        miniThumbnail = null,
        date = date,
        fileName = fileName,
    )

    private fun file(item: MediaItem) = ShelfEntry.File(item)

    private fun titles(picks: List<FeaturedPick>) = picks.map { it.series?.title ?: it.item.fileName }

    @Test
    fun `newest first and at most five`() {
        val entries = (1..8).map { n -> file(video("Film Number ${'A' + n} ${2000 + n}.mkv", date = n)) }
        val picks = HomeFeatured.pick(entries)
        assertEquals(HomeFeatured.LIMIT, picks.size)
        assertEquals(listOf(8, 7, 6, 5, 4), picks.map { it.date })
    }

    @Test
    fun `a short file or one with a made-up name is no film page`() {
        val short = video("Night.Train.2019.1080p.mkv", date = 10, durationSec = 60)
        val generic = video("Video (2024).mp4", date = 9)
        val dated = video("2024-10-13 (2024).mp4", date = 8)
        val film = video("Quiet.Harbour.2021.1080p.mkv", date = 7)
        val unknownLength = video("Sintel.2010.mkv", date = 6, durationSec = 0)
        val numbered = video("1917.2019.1080p.mkv", date = 5)
        val picks = HomeFeatured.pick(listOf(short, generic, dated, film, unknownLength, numbered).map(::file))
        assertEquals(listOf(film.id, unknownLength.id, numbered.id), picks.map { it.item.id })
        assertTrue(HomeFeatured.isGenericTitle("VID_20241013"))
        assertTrue(HomeFeatured.isGenericTitle("img 1234"))
        assertTrue(HomeFeatured.isGenericTitle("Untitled"))
        assertTrue(!HomeFeatured.isGenericTitle("300"))
        assertTrue(!HomeFeatured.isGenericTitle("Video Game High School"))
    }

    @Test
    fun `copies of one film in two chats are one page`() {
        val hd = video("Night.Train.2019.1080p.mkv", date = 10, chatId = 1)
        val sd = video("Night.Train.2019.720p.mkv", date = 9, chatId = 2)
        val other = video("Quiet.Harbour.2021.1080p.mkv", date = 5)
        val picks = HomeFeatured.pick(listOf(file(hd), file(sd), file(other)))
        assertEquals(listOf(hd.id, other.id), picks.map { it.item.id })
    }

    @Test
    fun `a show is one page, and a lone episode of it adds nothing`() {
        val shelf = SeriesShelf.arrange(
            listOf(
                video("Harbour.Notes.S01E02.1080p.mkv", date = 20, durationSec = 2_600),
                video("Harbour.Notes.S01E01.1080p.mkv", date = 19, durationSec = 2_600),
            ),
        )
        val show = shelf.single() as ShelfEntry.Show
        val lone = video("Harbour.Notes.S01E03.720p.mkv", date = 15, chatId = 2, durationSec = 2_600)
        val film = video("Night.Train.2019.1080p.mkv", date = 10)
        val picks = HomeFeatured.pick(listOf(show, file(lone), file(film)))
        assertEquals(2, picks.size)
        assertEquals(show.key, picks[0].key)
        assertEquals(film.id, picks[1].item.id)
    }

    @Test
    fun `extras never make a page`() {
        val trailer = video("Night.Train.2019.Trailer.mkv", date = 30, durationSec = 120).copy(extra = ExtraKind.Trailer)
        val sample = video("Quiet.Harbour.2021.Sample.mkv", date = 29, durationSec = 60)
        val film = video("Night.Train.2019.1080p.mkv", date = 10)
        val picks = HomeFeatured.pick(listOf(file(trailer), file(sample), file(film)))
        assertEquals(listOf(film.id), picks.map { it.item.id })
    }

    @Test
    fun `only films and shows make a page, never other videos`() {
        val clips = (1..4).map { n -> file(video("holiday_clip_${'a' + n}.mp4", date = 100 + n, durationSec = 90)) }
        val films = (1..3).map { n -> file(video("Film Number ${'A' + n} ${2000 + n}.mkv", date = n)) }
        val picks = HomeFeatured.pick(clips + films)
        // The three films, newest first; the newer clips are left out rather than filling in.
        assertEquals(listOf(3, 2, 1), picks.map { it.date })
        assertTrue(HomeFeatured.pick(clips).isEmpty())
    }

    @Test
    fun `nothing in, nothing out`() {
        assertEquals(emptyList<FeaturedPick>(), HomeFeatured.pick(emptyList()))
    }

    @Test
    fun `a sharp online picture goes first, the rest keep their order`() {
        val entries = (1..5).map { n -> file(video("Film Number ${'A' + n} ${2000 + n}.mkv", date = n)) }
        val picks = HomeFeatured.pick(entries)
        val sharp = setOf(picks[2].key, picks[4].key)
        val ranked = HomeFeatured.rank(picks) { it.key in sharp }
        assertEquals(listOf(picks[2], picks[4], picks[0], picks[1], picks[3]), ranked)
        assertEquals(titles(picks), titles(HomeFeatured.rank(picks) { false }))
    }
}
