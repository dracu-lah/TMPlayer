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
    fun `only files of 400 MB or more make a page, whatever the size filter says`() {
        val small = video("Small Film 2001.mkv", date = 40).copy(sizeBytes = 399 * SizeFilter.MB)
        val unknown = video("Unknown Size Film 2002.mkv", date = 39).copy(sizeBytes = 0)
        val floor = video("Floor Film 2003.mkv", date = 38).copy(sizeBytes = HomeFeatured.MIN_BYTES)
        val big = video("Big Film 2004.mkv", date = 37)
        val picks = HomeFeatured.pick(listOf(small, unknown, floor, big).map(::file))
        assertEquals(listOf(floor.id, big.id), picks.map { it.item.id })
        // A small copy leading still passes when another copy of it is big.
        val led = video("Copies Film 2005.mkv", date = 36).copy(sizeBytes = 200 * SizeFilter.MB, versions = listOf(big))
        assertTrue(HomeFeatured.bigEnough(FeaturedPick(file(led))))
        assertTrue(!HomeFeatured.eligible(FeaturedPick(file(small))))
    }

    @Test
    fun `a show counts by its episode files`() {
        fun show(size: Long) = SeriesShelf.arrange(
            listOf(
                video("Harbour.Notes.S01E02.1080p.mkv", date = 20, durationSec = 2_600).copy(sizeBytes = size),
                video("Harbour.Notes.S01E01.1080p.mkv", date = 19, durationSec = 2_600).copy(sizeBytes = size),
            ),
        ).single()
        assertEquals(1, HomeFeatured.pick(listOf(show(450 * SizeFilter.MB))).size)
        assertTrue(HomeFeatured.pick(listOf(show(250 * SizeFilter.MB))).isEmpty())
        // A lone episode by its own file.
        val lone = video("Harbour.Notes.S01E03.720p.mkv", date = 15, durationSec = 2_600).copy(sizeBytes = 300 * SizeFilter.MB)
        assertTrue(HomeFeatured.pick(listOf(file(lone))).isEmpty())
    }

    @Test
    fun `a complete page has a wide picture and an overview`() {
        val full = com.tmplayer.online.MetaInfo(
            provider = com.tmplayer.online.MetaProvider.Tmdb, id = "1", kind = com.tmplayer.online.MetaKind.Film,
            title = "Night Train", year = 2019, overview = "A night on a train.",
            posterUrl = "https://image.tmdb.org/p.jpg", backdropUrl = "https://image.tmdb.org/b.jpg",
        )
        assertTrue(HomeFeatured.isComplete(full))
        assertTrue(!HomeFeatured.isComplete(null))
        // A poster alone is not a wide picture.
        assertTrue(!HomeFeatured.isComplete(full.copy(backdropUrl = null)))
        assertTrue(!HomeFeatured.isComplete(full.copy(overview = " ")))
    }

    private fun films(count: Int) = HomeFeatured.pick(
        (1..count).map { n -> file(video("Film Number ${'A' + n} ${2000 + n}.mkv", date = n)) },
        HomeFeatured.CANDIDATES,
    )

    @Test
    fun `a fill keeps what is known complete and asks about the rest`() = kotlinx.coroutines.runBlocking<Unit> {
        val picks = films(8)
        val known = mapOf(picks[0].key to true, picks[1].key to false)
        val asked = java.util.Collections.synchronizedList(ArrayList<String>())
        val keys = HomeFeatured.completeKeys(
            picks,
            known = { known[it.key] },
            ask = { pick ->
                asked += pick.key
                // "No match" for the third, complete for the rest.
                pick.key != picks[2].key
            },
            scope = this,
        )
        assertEquals(listOf(0, 3, 4, 5, 6), keys.map { key -> picks.indexOfFirst { it.key == key } })
        // Nothing already known is asked again.
        assertTrue(picks[0].key !in asked && picks[1].key !in asked)
    }

    @Test
    fun `a lookup that does not answer in time is left out of this fill`() = kotlinx.coroutines.runBlocking<Unit> {
        val picks = films(3)
        val keys = HomeFeatured.completeKeys(
            picks,
            known = { null },
            ask = { pick ->
                if (pick.key == picks[0].key) kotlinx.coroutines.delay(5_000)
                if (pick.key == picks[2].key) error("garbage answer")
                true
            },
            scope = this,
            waitMs = 300,
        )
        assertEquals(setOf(picks[1].key), keys)
        coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
    }

    @Test
    fun `nothing complete means no carousel`() = kotlinx.coroutines.runBlocking<Unit> {
        val picks = films(4)
        val keys = HomeFeatured.completeKeys(picks, known = { false }, ask = { true }, scope = this)
        assertTrue(keys.isEmpty())
        assertTrue(picks.filter { it.key in keys }.isEmpty())
        assertTrue(HomeFeatured.completeKeys(emptyList(), known = { true }, ask = { true }, scope = this).isEmpty())
    }
}
