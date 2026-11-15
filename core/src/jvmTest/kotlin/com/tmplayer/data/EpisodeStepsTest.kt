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

    /** A chat answered forty at a time, newest first, as the lookup reads it. */
    private fun pages(all: List<MediaItem>, size: Int = 40): suspend (String, MediaCursors) -> MediaPage? = { query, cursors ->
        val matching = all.filter { query.isBlank() || it.fileName.contains(query.replace(' ', '.'), ignoreCase = true) }
        val from = cursors.video.toInt()
        val until = minOf(from + size, matching.size)
        MediaPage(matching.subList(from, until), MediaCursors(video = until.toLong()), endReached = until >= matching.size)
    }

    @Test
    fun `the lookup keeps reading pages until both neighbours are found`() = kotlinx.coroutines.test.runTest {
        // E05 playing, E04 the newest post, E06 posted a hundred videos further back.
        val e04 = video("Harbour.Notes.S01E04.mkv")
        val e05 = video("Harbour.Notes.S01E05.mkv")
        val filler = (1..100).map { video("Unrelated.Clip.$it.mkv") }
        val e06 = video("Harbour.Notes.S01E06.mkv")
        val chat = listOf(e04, e05) + filler + e06
        // The plain listing, as for a chat that names its files so the search misses them.
        var reads = 0
        val plain: suspend (String, MediaCursors) -> MediaPage? = { query, cursors ->
            if (query.isNotBlank()) MediaPage(emptyList(), MediaCursors(), endReached = true) else { reads++; pages(chat)(query, cursors) }
        }
        val steps = EpisodeNeighbours.lookUp(e05, "Harbour Notes", plain)
        assertEquals(e04.id, steps.previous?.id)
        assertEquals(e06.id, steps.next?.id)
        assertEquals(3, reads)
    }

    @Test
    fun `the lookup stops at the end of the results, with the one neighbour there is`() = kotlinx.coroutines.test.runTest {
        val e01 = video("Harbour.Notes.S01E01.mkv")
        val e02 = video("Harbour.Notes.S01E02.mkv")
        val steps = EpisodeNeighbours.lookUp(e01, "Harbour Notes", pages(listOf(e02, e01)))
        assertNull(steps.previous)
        assertEquals(e02.id, steps.next?.id)
    }

    @Test
    fun `a split film plays on from one piece to the next`() {
        com.tmplayer.i18n.Translator.use(com.tmplayer.i18n.Languages.ENGLISH)
        val cd2 = video("Coast Run 2014 720p CD2.avi", size = 700 * mb)
        val cd1 = video("Coast Run 2014 720p CD1.avi", size = 700 * mb)
        val items = listOf(cd2, cd1)
        val first = EpisodeNeighbours.around(cd1, items)
        assertEquals(cd2.id, first.next?.id)
        assertEquals("Part 2", first.nextTag?.code)
        assertNull(first.previous)
        val second = EpisodeNeighbours.around(cd2, items)
        assertEquals(cd1.id, second.previous?.id)
        assertNull(second.next)
    }

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

    @Test
    fun `with no chat to ask the kept episodes of the same show are the neighbours`() {
        val e04 = video("Harbour.Notes.S01E04.mkv")
        val e05 = video("Harbour.Notes.S01E05.mkv")
        val e06 = video("Harbour.Notes.S01E06.mkv")
        val other = video("Other.Show.S01E06.mkv")
        val steps = EpisodeNeighbours.amongKept(e05, "Harbour Notes", listOf(e04, e06, other))
        assertEquals(e04.id, steps?.previous?.id)
        assertEquals(e06.id, steps?.next?.id)
    }

    @Test
    fun `kept videos of another show or none at all leave the steps unchanged`() {
        val e05 = video("Harbour.Notes.S01E05.mkv")
        val other = video("Other.Show.S01E06.mkv")
        assertNull(EpisodeNeighbours.amongKept(e05, "Harbour Notes", listOf(other)))
        assertNull(EpisodeNeighbours.amongKept(e05, "Harbour Notes", emptyList()))
        // Only itself kept: nothing to step to.
        assertNull(EpisodeNeighbours.amongKept(e05, "Harbour Notes", listOf(e05)))
    }

    @Test
    fun `a single kept episode after the current one is a next and has no previous`() {
        val e05 = video("Harbour.Notes.S01E05.mkv")
        val e06 = video("Harbour.Notes.S01E06.mkv")
        val steps = EpisodeNeighbours.amongKept(e05, "Harbour Notes", listOf(e06))
        assertNull(steps?.previous)
        assertEquals(e06.id, steps?.next?.id)
    }
}
