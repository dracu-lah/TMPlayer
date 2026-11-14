package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFacetsTest {

    private var nextId = 1L
    private val now = 1_800_000_000L
    private val day = 86_400L

    private fun video(
        name: String,
        seconds: Int = 0,
        width: Int = 0,
        height: Int = 0,
        ageDays: Long = 400,
        title: String = name,
    ) = nextId++.let { id ->
        MediaItem(
            chatId = 1, messageId = id, fileId = id.toInt(), title = title, sizeBytes = 1, durationSec = seconds,
            mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = (now - ageDays * day).toInt(),
            fileName = name, width = width, height = height,
        )
    }

    private fun verdict(item: MediaItem, facets: VideoFacets) = VideoFaceting.verdict(item, facets, now)

    @Test
    fun `the traits come from the name and the stated size`() {
        val t = video("Night.Train.2019.2160p.HDR10.WEB-DL.Malayalam.Tamil.ESub.mkv").traits
        assertEquals(4, t.pictureRank)
        assertTrue(t.hdr)
        assertTrue(t.subtitles)
        assertEquals(listOf("ml", "ta"), t.languages)
        val sized = video("clip.mp4", width = 1920, height = 1080).traits
        assertEquals(3, sized.pictureRank)
        assertFalse(sized.hdr)
        assertEquals(0, video("clip.mp4").traits.pictureRank)
    }

    @Test
    fun `a video sent as a video is read by its title`() {
        val t = video("", title = "Harbour Lights 2020 Hindi 720p").traits
        assertEquals(2, t.pictureRank)
        assertEquals(listOf("hi"), t.languages)
    }

    // Table: the filter, the video, what it says.
    @Test
    fun `length, picture, HDR, subtitles, language and date, one at a time`() {
        val table = listOf(
            Triple(VideoFacets(length = VideoFacets.Length.Short), video("a.mkv", seconds = 600), VideoFaceting.Verdict.Pass),
            Triple(VideoFacets(length = VideoFacets.Length.Short), video("a.mkv", seconds = 3_600), VideoFaceting.Verdict.Fail),
            Triple(VideoFacets(length = VideoFacets.Length.Short), video("a.mkv", seconds = 0), VideoFaceting.Verdict.Unknown),
            Triple(VideoFacets(length = VideoFacets.Length.Long), video("a.mkv", seconds = 3_600), VideoFaceting.Verdict.Pass),
            Triple(VideoFacets(length = VideoFacets.Length.Long), video("a.mkv", seconds = 1_500), VideoFaceting.Verdict.Fail),
            Triple(VideoFacets(length = VideoFacets.Length.Long), video("a.mkv", seconds = -5), VideoFaceting.Verdict.Unknown),
            Triple(VideoFacets(picture = VideoFacets.Picture.FullHd), video("a.2160p.mkv"), VideoFaceting.Verdict.Pass),
            Triple(VideoFacets(picture = VideoFacets.Picture.FullHd), video("a.1080p.mkv"), VideoFaceting.Verdict.Pass),
            Triple(VideoFacets(picture = VideoFacets.Picture.FullHd), video("a.720p.mkv"), VideoFaceting.Verdict.Fail),
            Triple(VideoFacets(picture = VideoFacets.Picture.Hd), video("a.mkv", width = 1280, height = 720), VideoFaceting.Verdict.Pass),
            Triple(VideoFacets(picture = VideoFacets.Picture.Uhd), video("a.mkv"), VideoFaceting.Verdict.Unknown),
            Triple(VideoFacets(hdr = true), video("a.DV.mkv"), VideoFaceting.Verdict.Pass),
            Triple(VideoFacets(hdr = true), video("a.mkv"), VideoFaceting.Verdict.Fail),
            Triple(VideoFacets(subtitles = true), video("a.MSubs.mkv"), VideoFaceting.Verdict.Pass),
            Triple(VideoFacets(subtitles = true), video("a.mkv"), VideoFaceting.Verdict.Fail),
            Triple(VideoFacets(language = "ta"), video("Film 2021 Tamil HDRip.mkv"), VideoFaceting.Verdict.Pass),
            Triple(VideoFacets(language = "ta"), video("Film 2021 Telugu HDRip.mkv"), VideoFaceting.Verdict.Fail),
            Triple(VideoFacets(language = "ml"), video("Malayalam Lessons 1.mp4"), VideoFaceting.Verdict.Fail),
            Triple(VideoFacets(added = VideoFacets.Added.Week), video("a.mkv", ageDays = 3), VideoFaceting.Verdict.Pass),
            Triple(VideoFacets(added = VideoFacets.Added.Week), video("a.mkv", ageDays = 9), VideoFaceting.Verdict.Fail),
            Triple(VideoFacets(added = VideoFacets.Added.Year), video("a.mkv", ageDays = 300), VideoFaceting.Verdict.Pass),
        )
        table.forEachIndexed { row, (facets, item, expected) ->
            assertEquals("row $row: ${item.fileName}", expected, verdict(item, facets))
        }
    }

    @Test
    fun `filters combine, and a known failure beats an unknown`() {
        val facets = VideoFacets(length = VideoFacets.Length.Short, hdr = true)
        assertEquals(VideoFaceting.Verdict.Fail, verdict(video("a.mkv", seconds = 0), facets))
        assertEquals(VideoFaceting.Verdict.Unknown, verdict(video("a.HDR.mkv", seconds = 0), facets))
        assertEquals(VideoFaceting.Verdict.Pass, verdict(video("a.HDR.mkv", seconds = 60), facets))
    }

    @Test
    fun `screen keeps the order, lists unknowns after the matches and counts both`() {
        val a = video("a.1080p.mkv")
        val b = video("b.mkv")
        val c = video("c.480p.mkv")
        val d = video("d.2160p.mkv")
        val shelf = listOf(a, b, c, d).map { ShelfEntry.File(it) }
        val screened = VideoFaceting.screen(shelf, VideoFacets(picture = VideoFacets.Picture.FullHd), now)
        assertEquals(listOf(a, d, b), screened.shown.map { (it as ShelfEntry.File).item })
        assertEquals(1, screened.hidden)
        assertEquals(1, screened.unknown)
        assertEquals(shelf, VideoFaceting.screen(shelf, VideoFacets.NONE, now).shown)
    }

    @Test
    fun `a film passes when any copy does`() {
        val sd = video("Film.2020.480p.mkv")
        val hd = video("Film.2020.1080p.mkv")
        val film = ShelfEntry.File(sd.copy(versions = listOf(sd, hd)))
        assertEquals(VideoFaceting.Verdict.Pass, VideoFaceting.verdict(film, VideoFacets(picture = VideoFacets.Picture.FullHd), now))
    }

    @Test
    fun `encode and decode round trip, and unknown parts fall back one by one`() {
        val all = VideoFacets(VideoFacets.Length.Long, VideoFacets.Picture.Uhd, hdr = true, subtitles = true, language = "ml", added = VideoFacets.Added.Month)
        assertEquals(all, VideoFacets.decode(all.encode()))
        assertEquals("", VideoFacets.NONE.encode())
        assertEquals(VideoFacets.NONE, VideoFacets.decode(null))
        assertEquals(VideoFacets.NONE, VideoFacets.decode("garbage"))
        assertEquals(VideoFacets(hdr = true), VideoFacets.decode("len=forever;hdr=1;pic=8k;zz=1;lang=Malayalam!;;="))
        assertEquals(VideoFacets(language = "ta"), VideoFacets.decode("lang=TA"))
        assertEquals(6, all.count)
    }

    @Test
    fun `the languages offered are the loaded names, most common first, the picked one kept`() {
        val shelf = listOf(
            video("A 2020 Tamil.mkv"), video("B 2020 Tamil.mkv"), video("C 2021 Hindi.mkv"), video("D.mkv"),
        ).map { ShelfEntry.File(it) }
        assertEquals(listOf("ta", "hi"), VideoFaceting.languagesIn(shelf))
        assertEquals(listOf("te", "ta", "hi"), VideoFaceting.languagesIn(shelf, keep = "te"))
        assertEquals(listOf("ta"), VideoFaceting.languagesIn(shelf, limit = 1))
    }

    @Test
    fun `twenty thousand videos are screened from facts read once and kept on each item`() {
        val items = (1..20_000).map { i -> video("Show.S01E${i % 20 + 1}.${if (i % 50 == 0) "HDR." else ""}1080p.mkv", seconds = if (i % 7 == 0) 0 else 1_400) }
        val shelf = items.map { ShelfEntry.File(it) }
        val hdr = VideoFaceting.screen(shelf, VideoFacets(hdr = true), now)
        assertEquals(400, hdr.shown.size)
        assertEquals(19_600, hdr.hidden)
        assertTrue(items.all { it.traits === it.traits })
        // A copy with the same names keeps what was read ([MediaItem.keptReads]).
        val copy = items[0].copy(sizeBytes = 2).keptReads(items[0])
        assertTrue(copy.traits === items[0].traits)
        // Unknown lengths are shown, never dropped: every seventh has none.
        val short = VideoFaceting.screen(shelf, VideoFacets(length = VideoFacets.Length.Short), now)
        assertEquals(items.count { it.durationSec == 0 }, short.unknown)
        assertEquals(short.unknown, short.shown.size)
    }

    @Test
    fun `a filtered grid pages by itself a few times only`() {
        assertTrue(VideoFaceting.mayAutoLoad(filtersOn = false, shown = 0, screenful = 12, autoLoaded = 99))
        assertTrue(VideoFaceting.mayAutoLoad(filtersOn = true, shown = 0, screenful = 12, autoLoaded = 0))
        assertFalse(VideoFaceting.mayAutoLoad(filtersOn = true, shown = 3, screenful = 12, autoLoaded = VideoFaceting.AUTO_PAGES))
        assertTrue(VideoFaceting.mayAutoLoad(filtersOn = true, shown = 40, screenful = 12, autoLoaded = VideoFaceting.AUTO_PAGES))
    }

    @Test
    fun `last played puts the latest first and the never played last`() {
        val a = video("a.mkv")
        val b = video("b.mkv")
        val c = video("c.mkv")
        val played = mapOf(a.id to 100L, c.id to 300L)
        val sorted = VideoSorting.sort(listOf(a, b, c).map { ShelfEntry.File(it) }, VideoSort.LastPlayed, lastPlayed = { played[it.id] })
        assertEquals(listOf(c, a, b), sorted.map { (it as ShelfEntry.File).item })
        assertEquals(VideoSort.LastPlayed, VideoSort.decode("LastPlayed"))
    }
}
