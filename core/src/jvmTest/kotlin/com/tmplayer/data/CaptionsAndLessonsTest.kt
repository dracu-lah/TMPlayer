package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionsAndLessonsTest {

    private var nextId = 1L

    private fun video(fileName: String, caption: String = "", topicId: Int = 0) = MediaItem(
        chatId = 7,
        messageId = nextId++,
        fileId = 0,
        title = MediaMapper.displayTitle(fileName, caption, "Video"),
        sizeBytes = 1,
        durationSec = 60,
        mimeType = "video/mp4",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = nextId.toInt(),
        fileName = fileName,
        caption = MediaMapper.captionOf(caption),
        topicId = topicId,
    )

    @Test
    fun `camera and messenger names are generic, release names are not`() {
        listOf(
            "VID_20241013_120000.mp4", "video_2024-10-13_12-00-00.mp4", "IMG_1234.MOV", "PXL_20240101_093000123.mp4",
            "VID-20240101-WA0001.mp4", "video.mp4", "video (2).mp4", "20241013.mp4", "1234567890abcdef1234.mp4",
            "Screen_Recording_20241013-120000.mp4",
        ).forEach { assertTrue(it, MediaMapper.isGenericName(it)) }
        listOf(
            "1917.2019.1080p.mkv", "Harbour.Notes.S01E02.mkv", "lecture_05_thermodynamics.mp4", "Physics Class 12.mp4",
        ).forEach { assertFalse(it, MediaMapper.isGenericName(it)) }
    }

    @Test
    fun `a caption names a video whose file name was made up`() {
        assertEquals("Lecture 5: Entropy", video("VID_20241013_120000.mp4", "Lecture 5: Entropy\nNotes in the group").title)
        assertEquals("Harbour.Notes.S01E02.mkv", video("Harbour.Notes.S01E02.mkv", "Episode two").title)
        assertEquals("VID_20241013_120000.mp4", video("VID_20241013_120000.mp4").title)
    }

    @Test
    fun `channel boilerplate is taken off a caption`() {
        val caption = MediaMapper.captionOf("Episode 4, The Storm\n\nJoin @harbour_channel\nhttps://t.me/harbour\n#drama #series")
        assertEquals("Episode 4, The Storm", caption)
        assertEquals("", MediaMapper.captionOf("👉 @MoviesHubOfficial"))
        assertEquals("Watch it on the big screen", MediaMapper.captionOf("Watch it on the big screen"))
    }

    @Test
    fun `an overview drops the file's own facts and keeps the poster's words`() {
        val name = "Pulimada.1080P.10bit.Web.Dl.Dolby.hevc.mkv"
        assertNull(MediaText.overviewOf("FILE NAME : Pulimada 1080P 10bit Web Dl Dolby hevc mkv", name, "Pulimada"))
        assertNull(MediaText.overviewOf("📁 File Name: x\n💾 Size: 1.4 GB\nQuality - 1080p\nAudio = Malayalam\nJoin @movies_hub", name, "x"))
        assertNull(MediaText.overviewOf("Pulimada 1080P 10bit Web Dl Dolby hevc", name, "Pulimada"))
        assertNull(MediaText.overviewOf("Pulimada", name, "Pulimada"))
        assertNull(MediaText.overviewOf("", name, "Pulimada"))
        assertNull(MediaText.overviewOf(null, null, null))
        assertEquals(
            "A police officer goes missing in the hills.",
            MediaText.overviewOf("FILE NAME : $name\nA police officer goes missing in the hills.\nSize : 2 GB", name, "Pulimada"),
        )
        assertEquals("Two brothers - one good, one bad", MediaText.overviewOf("Two brothers - one good, one bad", name, "Pulimada"))
        assertEquals("Story: a road trip", MediaText.overviewOf("Story: a road trip", name, "Pulimada"))
    }

    @Test
    fun `a line on most posts is not shown under each`() {
        val items = (1..5).map { video("Lesson.$it.mp4", "Lesson $it notes\nNew every Friday") }
        val repeated = MediaMapper.repeatedCaptionLines(items)
        assertTrue("new every friday" in repeated)
        assertEquals("Lesson 1 notes", MediaMapper.captionLine(items[0], repeated))
        assertNull(MediaMapper.captionLine(video("x.mkv", "x.mkv")))
    }

    @Test
    fun `search text carries the caption`() {
        val item = video("lec05.mp4", "Thermodynamics: entropy and heat")
        assertTrue(Fuzzy.score(item.searchText, "entropy") > 0)
        assertEquals(listOf(item), Fuzzy.rank(listOf(item), "entropy") { it.searchText })
    }

    @Test
    fun `lectures step to their neighbours within the same course`() {
        val two = video("Physics_Class_12_Ch3_Lecture_02.mp4")
        val items = listOf(
            video("Physics_Class_12_Ch3_Lecture_01.mp4"),
            two,
            video("Physics_Class_12_Ch3_Lecture_03.mp4"),
            video("Physics_Class_12_Ch4_Lecture_03.mp4"),
            video("Holiday.mp4"),
        )
        val steps = EpisodeNeighbours.around(two, items)
        assertEquals("Physics_Class_12_Ch3_Lecture_01.mp4", steps.previous?.fileName)
        assertEquals("Physics_Class_12_Ch3_Lecture_03.mp4", steps.next?.fileName)
        assertEquals("Lecture 3", steps.nextTag?.code)
        assertEquals("Physics Class 12 Ch3", EpisodeNeighbours.showOf(two))
    }

    @Test
    fun `a lecture named only in its caption still steps on, past a gap`() {
        val one = video("VID_20241001_100000.mp4", "Lec 1 - Intro")
        val three = video("VID_20241003_100000.mp4", "Lecture 3 - Vectors")
        val steps = EpisodeNeighbours.around(one, listOf(one, three))
        assertEquals(three.id, steps.next?.id)
        assertEquals("Vectors", steps.nextTag?.name)
    }

    @Test
    fun `a film with no lesson word reads as a film`() {
        val film = video("Some.Film.2020.1080p.mkv")
        assertNull(EpisodeNeighbours.showOf(film))
        assertNull(EpisodeNeighbours.around(film, listOf(film, video("Other.Film.2021.mkv"))).next)
    }

    @Test
    fun `a height written without its p still gets a badge`() {
        assertEquals(listOf("1080p"), MediaMapper.qualityTags("Pradhama Drishtiya Kuttakkar 2026 Malayalam HQ HDRip - 1080.mkv"))
        assertEquals(listOf("720p"), MediaMapper.qualityTags("Some Film 2026 - 720 - x264.mkv"))
        assertEquals(emptyList<String>(), MediaMapper.qualityTags("Khalifa The Ruler 2026 Malayalam HQ HDRip - x264 - AAC - 40.mkv"))
        assertEquals(emptyList<String>(), MediaMapper.qualityTags("clip_10800.mp4"))
    }

    @Test
    fun `copies of one film fold into one tile and episodes stay apart`() {
        val items = listOf(
            video("Pradhama Drishtiya Kuttakkar 2026 Malayalam HQ HDRip - 720p.mkv"),
            video("Harbour.Notes.S01E02.720p.mkv"),
            video("Pradhama Drishtiya Kuttakkar 2026 Malayalam HQ HDRip - 1080.mkv"),
            video("Harbour.Notes.S01E01.720p.mkv"),
            video("Pradhama.Drishtiya.Kuttakkar.2026.720p.NF.WEB-DL.DD+5.1.x264.mkv"),
            video("Khalifa The Ruler 2026 Malayalam HQ HDRip - 720p - HEVC - x.mkv"),
            video("Khalifa The Ruler 2026 Malayalam HQ HDRip - x264 - AAC - 40.mkv"),
        )
        val folded = SeriesShelf.foldFilmCopies(items)
        assertEquals(4, folded.size)
        assertEquals(3, folded[0].versions.size)
        assertEquals("Harbour.Notes.S01E02.720p.mkv", folded[1].fileName)
        assertEquals("Harbour.Notes.S01E01.720p.mkv", folded[2].fileName)
        assertEquals(2, folded[3].versions.size)
    }

    @Test
    fun `one season uploaded under a longer name joins the shorter show`() {
        val short = (1..3).map { video("Kerala_Crime_Files_S01E0${it}_Day_${it}_720p_DSNP_WEB_DL_HEVC.mkv") }
        val long = (1..3).map { video("Kerala_Crime_Files_Shiju_Parayil_Veedu_Neendakara_S01E0${it}_Day_$it.mkv") }
        val shows = SeriesShelf.arrange(short + long).filterIsInstance<ShelfEntry.Show>()
        assertEquals(1, shows.size)
        assertEquals(3, shows[0].series.episodeCount)
        assertEquals(2, shows[0].series.episodes.first().copies.size)
    }

    @Test
    fun `a sequel under a longer name stays its own show`() {
        val first = (1..3).map { video("Harbour.Notes.S01E0$it.mkv") }
        val sequel = (1..3).map { video("Harbour.Notes.New.Tides.S02E0$it.mkv") }
        assertEquals(2, SeriesShelf.arrange(first + sequel).filterIsInstance<ShelfEntry.Show>().size)
    }

    @Test
    fun `copies of a lone episode fold into one tile`() {
        val items = listOf(
            video("Loki (2023) S02E05 [ ScienceFiction ] 720p.mkv"),
            video("Loki (2023) S02E05 [ ScienceFiction ] 1080p.mkv"),
        )
        val folded = SeriesShelf.foldFilmCopies(items)
        assertEquals(1, folded.size)
        assertEquals(2, folded[0].versions.size)
    }

    @Test
    fun `a four figure anime episode is an episode, not a picture height`() {
        assertEquals(emptyList<String>(), MediaMapper.qualityTags("[SubsPlease] One Piece - 1080 (720p) [ABCD1234].mkv").filter { it == "1080p" })
        val items = (1078..1080).map { video("[SubsPlease] One Piece - $it (1080p) [ABCD1234].mkv") }
        val show = SeriesShelf.arrange(items).filterIsInstance<ShelfEntry.Show>().single().series
        assertEquals(3, show.episodeCount)
        assertEquals(1080, show.episodes.last().episode)
    }
}
