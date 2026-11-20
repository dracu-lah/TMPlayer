package com.tmplayer.dev

import com.tmplayer.data.MediaItem
import com.tmplayer.data.SeriesShelf
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NameDumpTest {

    private fun video(id: Long, fileName: String, caption: String = "", size: Long = 300_000_000, duration: Int = 2_700) =
        MediaItem(
            chatId = 7, messageId = id, fileId = id.toInt(), title = fileName, sizeBytes = size,
            durationSec = duration, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null,
            date = 1_700_000_000 + id.toInt(), fileName = fileName, caption = caption,
        )

    @Test
    fun jsonLineCarriesTheNameTheParseAndTheShelf() {
        val item = video(1, "Harbour.Notes.S02E05.1080p.mkv", caption = "\n  Harbour Notes  \nsecond line")
        val line = JSONObject(NameDump.jsonLine("My \"chat\"", "Channel", item, NameDump.Placement("show:harbour notes", 2, 5)))
        assertEquals("My \"chat\"", line.getString("chat"))
        assertEquals("Channel", line.getString("chatKind"))
        assertEquals("Harbour.Notes.S02E05.1080p.mkv", line.getString("fileName"))
        assertEquals("Harbour Notes", line.getString("caption"))
        assertEquals(300_000_000L, line.getLong("sizeBytes"))
        assertEquals(2_700, line.getInt("durationSec"))
        assertEquals(1_700_000_001, line.getInt("date"))
        val parsed = line.getJSONObject("parsed")
        assertEquals(2, parsed.getInt("season"))
        assertEquals(5, parsed.getInt("episode"))
        assertTrue(parsed.isNull("lastEpisode"))
        assertTrue(parsed.isNull("airDate"))
        assertEquals("show:harbour notes", line.getString("shelf"))
        assertEquals(2, line.getInt("showSeason"))
        assertEquals(5, line.getInt("showEpisode"))
        assertFalse(line.toString().contains('\n'))
    }

    @Test
    fun captionIsCutTo200Characters() {
        val long = "x".repeat(500)
        assertEquals(200, NameDump.captionLine(long).length)
        assertEquals("", NameDump.captionLine("  \n \n"))
    }

    @Test
    fun leftoversAreNumbersThatAreNoResolutionYearOrCodec() {
        assertTrue(NameDump.mayBeEpisode("Harbour Notes 06.mp4"))
        assertTrue(NameDump.mayBeEpisode("Harbour Notes #7 720p x264.mkv"))
        assertTrue(NameDump.mayBeEpisode("Lesson 12 - Fractions.mp4"))
        assertFalse(NameDump.mayBeEpisode("The Sample Room 2019 1080p WEB-DL x264 AAC5.1.mkv"))
        assertFalse(NameDump.mayBeEpisode("The Sample Room (2019) 720p HEVC 10bit H.265 DDP5.1 1.4GB.mkv"))
        assertFalse(NameDump.mayBeEpisode("Harbour.Notes.S01E05.480p.mkv"))
        assertFalse(NameDump.mayBeEpisode("Harbour Notes 1920x1080 720 480.mp4"))
        assertFalse(NameDump.mayBeEpisode("Harbour Notes.mp4"))
    }

    @Test
    fun placementsFollowTheShelf() {
        val items = listOf(
            video(5, "Harbour Notes S01E03.mkv"),
            video(4, "Harbour Notes S01E02.mkv"),
            video(3, "Harbour Notes S01E01.mkv"),
            video(2, "Lone Film 2019 1080p.mkv", size = 2_000_000_000, duration = 6_000),
            video(1, "Odd Name 07.mp4", size = 100_000_000, duration = 1_500),
        )
        val placed = NameDump.placements(items, SeriesShelf.arrange(items, corrections = emptyMap()))
        val show = placed.getValue(items[0].id)
        assertTrue(show.shelf.startsWith("show:"))
        assertEquals(1, show.season)
        assertEquals(3, show.episode)
        assertEquals(show.shelf, placed.getValue(items[2].id).shelf)
        assertEquals(NameDump.FILE, placed.getValue(items[3].id).shelf)
        assertEquals(NameDump.FILE, placed.getValue(items[4].id).shelf)

        val leftovers = NameDump.leftovers("Chat", items, placed)
        assertEquals(listOf("Chat\tOdd Name 07.mp4"), leftovers)
    }

    @Test
    fun copiesOfOneFilmAreOneGroup() {
        val items = listOf(
            video(2, "Lone Film 2019 1080p.mkv", size = 2_000_000_000, duration = 6_000),
            video(1, "Lone Film 2019 720p.mkv", size = 900_000_000, duration = 6_000),
        )
        val placed = NameDump.placements(items, SeriesShelf.arrange(items, corrections = emptyMap()))
        assertEquals(NameDump.FILM_COPY_GROUP, placed.getValue(items[0].id).shelf)
        assertEquals(NameDump.FILM_COPY_GROUP, placed.getValue(items[1].id).shelf)
    }
}
