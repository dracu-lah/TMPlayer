package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesShelfTest {

    private var nextId = 1L

    /** A video as a chat lists it; [date] counts up so later files are newer. */
    private fun video(fileName: String, caption: String = "", date: Int = nextId.toInt()) = MediaItem(
        chatId = 7,
        messageId = nextId++,
        fileId = 0,
        title = fileName.ifBlank { caption.lineSequence().firstOrNull().orEmpty() },
        sizeBytes = 1,
        durationSec = 60,
        mimeType = "video/mp4",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = date,
        fileName = fileName,
        caption = caption,
    )

    private fun shows(entries: List<ShelfEntry>) = entries.filterIsInstance<ShelfEntry.Show>().map { it.series }
    private fun files(entries: List<ShelfEntry>) = entries.filterIsInstance<ShelfEntry.File>().map { it.item.fileName }

    @Test
    fun `every way of writing one show lands on the same key`() {
        val key = SeriesShelf.keyOf("Harbour Notes")
        assertEquals("harbour notes", key)
        assertEquals(key, SeriesShelf.keyOf("HARBOUR NOTES:"))
        assertEquals(key, SeriesShelf.keyOf("  Harbour  Notes "))
        assertEquals(key, SeriesShelf.keyOf("Harbour-Notes"))
        assertEquals("", SeriesShelf.keyOf(" - "))
    }

    @Test
    fun `files from several naming styles fold into one show`() {
        val items = listOf(
            video("Harbour.Notes.S01E02.1080p.WEB-DL.mkv"),
            video("Harbour Notes 1x01 720p.mkv"),
            video("harbour_notes_720p.mkv", caption = "Harbour Notes S01E03\nJoin the channel"),
            video("City.Archive.1999.1080p.BluRay.mkv"),
        )
        val entries = SeriesShelf.arrange(items)
        val show = shows(entries).single()
        assertEquals("harbour notes", show.key)
        assertEquals("Harbour Notes", show.title)
        assertEquals(3, show.episodeCount)
        assertEquals(listOf(1, 2, 3), show.episodes.map { it.episode })
        assertEquals(listOf("City.Archive.1999.1080p.BluRay.mkv"), files(entries))
    }

    @Test
    fun `seasons come out in order with their episodes in order`() {
        val items = listOf(
            video("Studio.Sessions.S02E02.mkv"),
            video("Studio.Sessions.S01E10.mkv"),
            video("Studio.Sessions.S02E01.mkv"),
            video("Studio.Sessions.S01E02.mkv"),
            video("Studio.Sessions.S01E01.mkv"),
        )
        val show = shows(SeriesShelf.arrange(items)).single()
        assertEquals(listOf(1, 2), show.seasons.map { it.number })
        assertEquals(listOf(1, 2, 10), show.seasons[0].episodes.map { it.episode })
        assertEquals(listOf(1, 2), show.seasons[1].episodes.map { it.episode })
        assertEquals(listOf("S01E01", "S01E02", "S01E10", "S02E01", "S02E02"), show.episodes.map { it.code })
    }

    @Test
    fun `a show without seasons is season one`() {
        val items = listOf(
            video("[SubsPlease] Sky Garden - 02 (1080p).mkv"),
            video("[SubsPlease] Sky Garden - 01 (1080p).mkv"),
            video("Sky Garden Ep 03.mkv"),
        )
        val show = shows(SeriesShelf.arrange(items)).single()
        assertEquals(listOf(1), show.seasons.map { it.number })
        assertEquals(listOf(1, 2, 3), show.episodes.map { it.episode })
    }

    @Test
    fun `two copies of one episode sit together, the newer first`() {
        val items = listOf(
            video("Studio.Sessions.S01E01.720p.mkv", date = 10),
            video("Studio.Sessions.S01E01.1080p.mkv", date = 20),
            video("Studio.Sessions.S01E02.1080p.mkv", date = 15),
        )
        val show = shows(SeriesShelf.arrange(items)).single()
        assertEquals(
            listOf("Studio.Sessions.S01E01.1080p.mkv", "Studio.Sessions.S01E01.720p.mkv", "Studio.Sessions.S01E02.1080p.mkv"),
            show.episodes.map { it.item.fileName },
        )
        assertEquals("Studio.Sessions.S01E01.1080p.mkv", show.cover.fileName)
    }

    @Test
    fun `a lone episode stays a file, and so does a season pack`() {
        val items = listOf(
            video("Studio.Sessions.S01E01.mkv"),
            video("Harbour Notes Season 2 Complete 1080p.mkv"),
            video("Harbour Notes Season 3 Complete 1080p.mkv"),
        )
        val entries = SeriesShelf.arrange(items)
        assertTrue(shows(entries).isEmpty())
        assertEquals(items.map { it.fileName }, files(entries))
    }

    @Test
    fun `a show takes the place of its first file and the chat order is kept`() {
        val items = listOf(
            video("Workshop (2022) 1080p.mkv"),
            video("Studio.Sessions.S01E03.mkv"),
            video("Kitchen Journal.mkv"),
            video("Studio.Sessions.S01E02.mkv"),
            video("Sky Garden - 02.mkv"),
            video("Studio.Sessions.S01E01.mkv"),
            video("Sky Garden - 01.mkv"),
        )
        val keys = SeriesShelf.arrange(items).map {
            when (it) {
                is ShelfEntry.Show -> "show:" + it.series.key
                is ShelfEntry.File -> it.item.fileName
            }
        }
        assertEquals(
            listOf("Workshop (2022) 1080p.mkv", "show:studio sessions", "Kitchen Journal.mkv", "show:sky garden"),
            keys,
        )
    }

    @Test
    fun `nothing is lost or listed twice`() {
        val items = listOf(
            video("Studio.Sessions.S01E01.mkv"),
            video("Studio.Sessions.S01E02.mkv"),
            video("City.Archive.1999.mkv"),
            video("Sky Garden - 01.mkv"),
        )
        val entries = SeriesShelf.arrange(items)
        val listed = entries.flatMap {
            when (it) {
                is ShelfEntry.Show -> it.series.episodes.map { e -> e.item }
                is ShelfEntry.File -> listOf(it.item)
            }
        }
        assertEquals(items.map { it.id }.sorted(), listed.map { it.id }.sorted())
        assertEquals(entries.size, entries.map { it.key }.toSet().size)
    }

    @Test
    fun `movies with years and resolutions never become a show`() {
        val items = listOf(
            video("City.Archive.2016.1080p.BluRay.x265.mkv"),
            video("City.Archive.2016.720p.BluRay.x264.mkv"),
            video("City Archive - 2019 1080p.mkv"),
        )
        assertTrue(shows(SeriesShelf.arrange(items)).isEmpty())
    }

    @Test
    fun `an empty chat arranges to nothing`() {
        assertTrue(SeriesShelf.arrange(emptyList()).isEmpty())
    }

    // ---- progress ---------------------------------------------------------------------------

    private val show = run {
        val items = listOf(
            video("Studio.Sessions.S01E01.mkv"),
            video("Studio.Sessions.S01E02.mkv"),
            video("Studio.Sessions.S01E03.mkv"),
            video("Studio.Sessions.S02E01.mkv"),
        )
        shows(SeriesShelf.arrange(items)).single()
    }

    private fun progress(finished: Set<String>, started: Map<String, Float> = emptyMap()) =
        SeriesShelf.progress(show, { it.fileName.code() in finished }, { started[it.fileName.code()] ?: 0f })

    private fun String.code() = Regex("""S\d\dE\d\d""").find(this)!!.value

    @Test
    fun `a fresh show starts at the first episode`() {
        val p = progress(emptySet())
        assertEquals(0, p.watched)
        assertEquals(4, p.total)
        assertEquals("S01E01", p.next?.code)
        assertFalse(p.finished)
    }

    @Test
    fun `the next episode follows the furthest one watched`() {
        val p = progress(setOf("S01E01", "S01E02"))
        assertEquals(2, p.watched)
        assertEquals("S01E03", p.next?.code)
    }

    @Test
    fun `the end of a season carries on into the next one`() {
        assertEquals("S02E01", progress(setOf("S01E01", "S01E02", "S01E03")).next?.code)
    }

    @Test
    fun `an episode part way through is where to carry on`() {
        val p = progress(setOf("S01E01", "S01E02", "S01E03"), started = mapOf("S01E02" to 0.4f))
        // E02 is on the Watched list, so its old position does not count; nothing else is started.
        assertEquals("S02E01", p.next?.code)
        assertEquals("S01E03", progress(setOf("S01E01"), started = mapOf("S01E03" to 0.5f)).next?.code)
    }

    @Test
    fun `a skipped episode is offered once the later ones are done`() {
        assertEquals("S01E02", progress(setOf("S01E01", "S01E03", "S02E01")).next?.code)
    }

    @Test
    fun `a show watched to the end has nothing next`() {
        val p = progress(setOf("S01E01", "S01E02", "S01E03", "S02E01"))
        assertTrue(p.finished)
        assertNull(p.next)
        assertEquals(4, p.watched)
    }

    @Test
    fun `the caption alone can make a show`() {
        val items = listOf(
            video("", caption = "Sky Garden Episode 1\nsubbed"),
            video("", caption = "Sky Garden Episode 2"),
        )
        val show = shows(SeriesShelf.arrange(items)).single()
        assertEquals("Sky Garden", show.title)
        assertEquals(listOf(1, 2), show.episodes.map { it.episode })
    }
}
