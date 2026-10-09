package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeStepsTest {

    private var nextId = 1L
    private val mb = 1024L * 1024

    private fun video(fileName: String, caption: String = "", size: Long = 1) = MediaItem(
        chatId = 7,
        messageId = nextId++,
        fileId = 0,
        title = fileName.ifBlank { caption.lineSequence().firstOrNull().orEmpty() },
        sizeBytes = size,
        durationSec = 60,
        mimeType = "video/mp4",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = nextId.toInt(),
        fileName = fileName,
        caption = caption,
    )

    @Test
    fun `the last episode of a season leads into the next season`() {
        val items = listOf(
            video("Harbour.Notes.S02E01.1080p.mkv"),
            video("Harbour.Notes.S01E03.1080p.mkv"),
            video("Harbour.Notes.S01E02.1080p.mkv"),
            video("Harbour.Notes.S01E01.1080p.mkv"),
        )
        val end = EpisodeNeighbours.around(items[1], items)
        assertEquals(items[0].id, end.next?.id)
        assertEquals("S02E01", end.nextTag?.code)
        assertEquals(items[2].id, end.previous?.id)
        assertEquals("S01E02", end.previousTag?.code)
        assertEquals("S01E03", end.current?.code)

        val start = EpisodeNeighbours.around(items[0], items)
        assertEquals(items[1].id, start.previous?.id)
        assertEquals("S01E03", start.previousTag?.code)
    }

    @Test
    fun `nothing before the first episode and nothing after the last`() {
        val items = listOf(video("Show S01E01 720p.mkv"), video("Show S01E02 720p.mkv"))
        val first = EpisodeNeighbours.around(items[0], items)
        assertNull(first.previous)
        assertNull(first.previousTag)
        assertEquals(items[1].id, first.next?.id)
        val last = EpisodeNeighbours.around(items[1], items)
        assertNull(last.next)
        assertNull(last.nextTag)
        assertEquals(items[0].id, last.previous?.id)
    }

    @Test
    fun `an episode named only in the caption, or as a bare E5, is a step like any other`() {
        val show = (1..4).map { video("Family_Full_House_With_Rohit_Sharma_S01E0${it}_Mumbai_Punters_vs_Team.mp4") }
        val captionOnly = video("", caption = "Family Full House With Rohit Sharma E05")
        val items = show + captionOnly
        val fromFour = EpisodeNeighbours.around(show[3], items)
        assertEquals(captionOnly.id, fromFour.next?.id)
        assertEquals("S01E05", fromFour.nextTag?.code)

        val fromFive = EpisodeNeighbours.around(captionOnly, items)
        assertEquals(show[3].id, fromFive.previous?.id)
        assertNull(fromFive.next)
        assertEquals("S01E05", fromFive.current?.code)

        val bare = video("Family Full House With Rohit Sharma \u2014 E5. Vlog.mp4")
        val withBare = EpisodeNeighbours.around(bare, show)
        assertEquals(show[3].id, withBare.previous?.id)
        assertEquals("Vlog", withBare.current?.name)
    }

    @Test
    fun `of several copies the step is the one closest to the copy playing`() {
        val sizes = listOf(354L, 599L, 1_843L)
        val items = (1..3).flatMap { n -> sizes.map { video("Show_S01E0${n}.mp4", size = it * mb + n) } }
        val smallTwo = items.single { it.fileName.contains("E02") && it.sizeBytes / mb == 354L }
        val steps = EpisodeNeighbours.around(smallTwo, items)
        assertEquals(354L, steps.next!!.sizeBytes / mb)
        assertEquals(354L, steps.previous!!.sizeBytes / mb)
        assertEquals("S01E03", steps.nextTag?.code)

        val bigTwo = items.single { it.fileName.contains("E02") && it.sizeBytes / mb == 1_843L }
        assertEquals(1_843L, EpisodeNeighbours.around(bigTwo, items).next!!.sizeBytes / mb)
    }

    @Test
    fun `the playing file is grouped even when the search left it out`() {
        val one = video("Show S01E01 720p.mkv")
        val two = video("Show S01E02 720p.mkv")
        assertEquals(two.id, EpisodeNeighbours.around(one, listOf(two)).next?.id)
    }

    @Test
    fun `a film has no steps and no tag`() {
        val items = listOf(
            video("City.Archive.1999.1080p.BluRay.mkv"),
            video("Harbour.Notes.S01E01.mkv"),
            video("Harbour.Notes.S01E02.mkv"),
        )
        val film = EpisodeNeighbours.around(items[0], items)
        assertNull(film.previous)
        assertNull(film.next)
        assertNull(film.current)
    }

    @Test
    fun `a lone episode is tagged but has no steps`() {
        val lone = video("Harbour.Notes.S01E04.The.Lighthouse.1080p.mkv")
        val steps = EpisodeNeighbours.around(lone, listOf(lone))
        assertNull(steps.next)
        assertEquals("S01E04", steps.current?.code)
        assertEquals("S01E04  ·  The Lighthouse", steps.current?.label)
    }

    @Test
    fun `the episode name is what follows the code, never the release tags`() {
        assertEquals("The Lighthouse", MediaName.episodeName("Harbour.Notes.S01E04.The.Lighthouse.1080p.WEB-DL.mkv"))
        assertEquals("Mumbai Punters vs Team", MediaName.episodeName("Family_Full_House_With_Rohit_Sharma_S01E01_Mumbai_Punters_vs_Team.mp4"))
        assertEquals("Pilot", MediaName.episodeName("Show S01E01-E02 Pilot 720p.mkv"))
        assertNull(MediaName.episodeName("Harbour.Notes.S01E04.1080p.WEB-DL.mkv"))
        assertNull(MediaName.episodeName("Show S01E04 Malayalam 720p.mkv"))
        assertNull(MediaName.episodeName("[SubsPlease] Show - 04 [1080p].mkv"))
        assertNull(MediaName.episodeName("City.Archive.1999.1080p.BluRay.mkv"))
    }

    @Test
    fun `episodes step by their numbers whatever order they were posted in`() {
        // Posted out of order: E01, then E03, then E02 (dates follow the list).
        val items = listOf(
            video("Harbour.Notes.S01E01.mkv"),
            video("Harbour.Notes.S01E03.mkv"),
            video("Harbour.Notes.S01E02.mkv"),
        )
        val byNumber = EpisodeNeighbours.around(items[1], items)
        assertEquals("S01E02", byNumber.previousTag?.code)
        assertNull(byNumber.next)
        // The series view's grouping comes along for the episode list.
        assertEquals(listOf("S01E01", "S01E02", "S01E03"), byNumber.series?.episodes?.map { it.code })
    }

    @Test
    fun `a film carries no series`() {
        val film = video("Night.Train.2019.1080p.mkv")
        assertNull(EpisodeNeighbours.around(film, listOf(film)).series)
        assertNull(EpisodeNeighbours.showOf(film))
        assertEquals("Harbour Notes", EpisodeNeighbours.showOf(video("Harbour.Notes.S01E02.mkv")))
    }
}
