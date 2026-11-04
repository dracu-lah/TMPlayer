package com.tmplayer.data

import com.tmplayer.i18n.Languages
import com.tmplayer.i18n.Translator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which files are one video: the evidence [SameVideo] weighs, and how [SeriesShelf] lays it out. */
class SameVideoTest {

    private var nextId = 1L

    @After
    fun forget() = VideoCorrections.set(emptyMap())

    private fun video(
        fileName: String,
        duration: Int = 0,
        size: Long = 1_500 * MB,
        caption: String = "",
        fileId: Int = nextId.toInt() + 1000,
        date: Int = nextId.toInt(),
    ) = MediaItem(
        chatId = 7,
        messageId = nextId++,
        fileId = fileId,
        title = fileName.ifBlank { caption },
        sizeBytes = size,
        durationSec = duration,
        mimeType = "video/x-matroska",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = date,
        fileName = fileName,
        caption = caption,
    )

    private fun tiles(items: List<MediaItem>, corrections: Map<String, VideoCorrection> = emptyMap()) =
        SeriesShelf.arrange(items, corrections).map { entry ->
            when (entry) {
                is ShelfEntry.Show -> "show:${entry.series.key}"
                is ShelfEntry.File -> entry.item.versions.ifEmpty { listOf(entry.item) }.joinToString("+") { it.fileName }
            }
        }

    @Test
    fun `a film's qualities sent together as one album are copies`() {
        val a = video("Night Lantern (2022) 720p.mkv", size = 900 * MB).copy(albumId = 55)
        val b = video("Night.Lantern.2022.1080p.mkv", size = 2_000 * MB).copy(albumId = 55)
        assertEquals(1, SameVideo.group(listOf(a, b), emptyMap()).size)
    }

    @Test
    fun `album files named only by the borrowed caption stay apart`() {
        val shared = MediaMapper.shareAlbumCaptions(
            listOf(
                video("", caption = "Harbour Notes S01E01").copy(albumId = 9),
                video("video.mp4").copy(albumId = 9),
                video("VID_20241013_120000.mp4").copy(albumId = 9),
            ),
        )
        assertEquals(3, SameVideo.group(shared, emptyMap()).size)
    }

    @Test
    fun `an album's videos without a caption take the album's`() {
        val first = video("", caption = "Harbour Notes S01E01").copy(albumId = 9)
        val second = video("video.mp4").copy(albumId = 9)
        val alone = video("clip.mp4")
        val shared = MediaMapper.shareAlbumCaptions(listOf(first, second, alone))
        assertEquals("Harbour Notes S01E01", shared[1].caption)
        assertEquals("", shared[2].caption)
        assertEquals("Harbour Notes", shared[1].parsedName.title)
    }

    @Test
    fun `copies sent as documents fold on title and year`() {
        val items = listOf(video("Night Lantern (2022) 720p.mkv", size = 900 * MB), video("Night.Lantern.2022.1080p.mkv", size = 2_000 * MB))
        assertEquals(listOf("Night.Lantern.2022.1080p.mkv+Night Lantern (2022) 720p.mkv"), tiles(items))
        assertEquals(1, SameVideo.group(items, emptyMap()).size)
    }

    @Test
    fun `lengths that disagree keep two videos apart whatever the name says`() {
        val items = listOf(video("Night Lantern (2022) 720p.mkv", duration = 7200), video("Night Lantern (2022) 1080p.mkv", duration = 5400))
        assertEquals(2, tiles(items).size)
        val close = listOf(video("Night Lantern (2022) 720p.mkv", duration = 7200), video("Night Lantern (2022) 1080p.mkv", duration = 7330))
        assertEquals(1, tiles(close).size)
    }

    @Test
    fun `the player's learned length counts as evidence`() {
        val a = video("Night Lantern (2022) 720p.mkv")
        val b = video("Night Lantern (2022) 1080p.mkv")
        val learned = mapOf(a.id to 7200, b.id to 5000)
        assertEquals(2, SameVideo.group(listOf(a, b), emptyMap()) { learned[it.id] ?: 0 }.size)
        assertEquals(1, SameVideo.group(listOf(a, b), emptyMap()).size)
    }

    @Test
    fun `a remake is another film`() {
        val items = listOf(video("Night Lantern (1998) 720p.mkv"), video("Night Lantern (2022) 720p.mkv"))
        assertEquals(2, tiles(items).size)
    }

    @Test
    fun `without a year only matching lengths fold`() {
        val documents = listOf(video("Night Lantern 720p.mkv"), video("Night Lantern 1080p.mkv"))
        assertEquals(2, tiles(documents).size)
        val timed = listOf(video("Night Lantern 720p.mkv", duration = 7200), video("Night Lantern 1080p.mkv", duration = 7210))
        assertEquals(1, tiles(timed).size)
    }

    @Test
    fun `the same file forwarded twice, or posted twice, is one video`() {
        val forwarded = listOf(video("Night Lantern 720p.mkv", fileId = 55), video("Night Lantern 720p.mkv", fileId = 55))
        assertEquals(1, tiles(forwarded).size)
        val reposted = listOf(video("Night Lantern 720p.mkv", size = 700 * MB), video("Night Lantern 720p.mkv", size = 700 * MB))
        assertEquals(1, tiles(reposted).size)
    }

    @Test
    fun `a short clip is never a copy of a feature, and is listed after it as an extra`() {
        Translator.use(Languages.ENGLISH)
        val film = video("Night Lantern (2022) 1080p.mkv", duration = 7200, date = 10)
        val clip = video("Night Lantern (2022) 720p.mp4", duration = 120, size = 20 * MB, date = 20)
        val other = video("Coast Run (2014) 720p.mkv", duration = 6000, date = 15)
        val entries = SeriesShelf.arrange(listOf(clip, other, film), emptyMap())
        val items = entries.map { (it as ShelfEntry.File).item }
        assertEquals(listOf(other.id, film.id, clip.id), items.map { it.id })
        assertEquals(ExtraKind.Extra, items.last().extra)
        assertEquals("Night Lantern (2022) · Extra", SeriesShelf.tileTitle(items.last()))
    }

    @Test
    fun `a trailer by name follows its film and is labelled`() {
        Translator.use(Languages.ENGLISH)
        val trailer = video("Night Lantern (2022) Official Trailer.mp4", size = 40 * MB, date = 30)
        val film = video("Night Lantern (2022) 720p.mkv", date = 10)
        val items = SeriesShelf.arrange(listOf(trailer, film), emptyMap()).map { (it as ShelfEntry.File).item }
        assertEquals(listOf(film.id, trailer.id), items.map { it.id })
        assertEquals(ExtraKind.Trailer, items[1].extra)
        assertEquals("Night Lantern (2022) · Trailer", SeriesShelf.tileTitle(items[1]))
        assertNull(SeriesShelf.sameVideoKey(trailer))
    }

    @Test
    fun `a word alone does not make an extra, nor does a short film with a year`() {
        val big = video("Night Lantern Trailer.mkv", size = 1_400 * MB)
        assertNull((SeriesShelf.arrange(listOf(big), emptyMap()).single() as ShelfEntry.File).item.extra)
        val film = video("Night Lantern (2022) 1080p.mkv", duration = 7200)
        val short = video("Night Lantern (2019) 720p.mp4", duration = 480, size = 60 * MB)
        val entries = SeriesShelf.arrange(listOf(short, film), emptyMap()).map { (it as ShelfEntry.File).item }
        assertTrue(entries.all { it.extra == null })
    }

    @Test
    fun `a split film is one video with its pieces in order`() {
        val cd2 = video("Coast Run 2014 720p CD2.avi", size = 700 * MB, date = 2)
        val cd1 = video("Coast Run 2014 720p CD1.avi", size = 700 * MB, date = 1)
        val whole = video("Coast Run 2014 1080p.mkv", size = 2_000 * MB, date = 3)
        val tile = (SeriesShelf.arrange(listOf(whole, cd2, cd1), emptyMap()).single() as ShelfEntry.File).item
        assertEquals(2, tile.versions.size)
        val split = tile.versions.first { it.parts.isNotEmpty() }
        assertEquals(listOf(cd1.id, cd2.id), split.parts.map { it.id })
        assertEquals("night lantern|2022|part2", SeriesShelf.sameVideoKey(video("Night Lantern 2022 CD2.avi")))
        // One piece alone is not the film.
        val lone = listOf(video("Coast Run 2014 720p CD2.avi", size = 700 * MB), video("Coast Run 2014 1080p.mkv"))
        assertEquals(2, tiles(lone).size)
        // Pieces far apart in size are not one cut.
        val odd = listOf(video("Coast Run 2014 CD1.avi", size = 700 * MB), video("Coast Run 2014 CD2.avi", size = 100 * MB))
        assertTrue(SeriesShelf.arrange(odd, emptyMap()).all { (it as ShelfEntry.File).item.parts.isEmpty() })
    }

    @Test
    fun `language dubs are copies of one film, told apart by their language`() {
        val tamil = video("Night Lantern (2022) Tamil 720p.mkv")
        val hindi = video("Night Lantern (2022) Hindi Dubbed 720p.mkv")
        val tile = (SeriesShelf.arrange(listOf(tamil, hindi), emptyMap()).single() as ShelfEntry.File).item
        assertEquals(2, tile.versions.size)
        assertEquals(listOf("ta"), MediaFacts.of(tamil).languages)
        assertEquals(listOf("hi"), MediaFacts.of(hindi).languages)
    }

    @Test
    fun `a film re-posted with no year or quality marker still folds by its specific title`() {
        // HX45: a channel handle prefixed to the name instead of a year, same exact file name
        // posted three times (the real "Prince of Mollywood"/Doraemon duplicate chat).
        val a = video("@MovieeManHD Doraemon Nobita and the Castle of the Undersea .mkv", duration = 60)
        val b = video("@MovieeManHD Doraemon Nobita and the Castle of the Undersea .mkv", duration = 60)
        val c = video("@MovieeManHD Doraemon Nobita and the Castle of the Undersea .mkv", duration = 60)
        val tile = (SeriesShelf.arrange(listOf(a, b, c), emptyMap()).single() as ShelfEntry.File).item
        assertEquals(3, tile.versions.size)
    }

    @Test
    fun `a single generic word still never folds two unrelated clips`() {
        val a = video("Lecture.mp4", duration = 300)
        val b = video("Lecture.mp4", duration = 300)
        assertEquals(2, tiles(listOf(a, b)).size)
    }

    @Test
    fun `not the same video splits a copy out, a rename joins a file to its film`() {
        val a = video("Night Lantern (2022) 720p.mkv")
        val b = video("Night Lantern (2022) 1080p.mkv")
        val typo = video("Nite Lantrn 2022 HDRip.mkv")
        assertEquals(2, tiles(listOf(a, b, typo)).size)
        val apart = mapOf(b.id to VideoCorrection(separate = true))
        assertEquals(3, tiles(listOf(a, b, typo), apart).size)
        val renamed = mapOf(typo.id to VideoCorrection(title = "Night Lantern (2022)"))
        assertEquals(1, tiles(listOf(a, b, typo), renamed).size)
        VideoCorrections.set(apart)
        assertNull(SeriesShelf.sameVideoKey(b))
        assertNotNull(SeriesShelf.sameVideoKey(a))
    }

    @Test
    fun `a rename names the tile and decides whether it is a film`() {
        val clip = video("VID_20240101_120000.mp4")
        VideoCorrections.set(mapOf(clip.id to VideoCorrection(title = "Harbour Picnic (2024)")))
        assertEquals("Harbour Picnic (2024)", SeriesShelf.tileTitle(clip))
        assertTrue(SeriesShelf.isFilm(clip))
        assertEquals("Harbour Picnic", SeriesShelf.posterTitle(clip))
    }

    @Test
    fun `episode copies whose lengths disagree are two rows of the show`() {
        val items = listOf(
            video("Harbour.Notes.S01E01.720p.mkv", duration = 2700),
            video("Harbour.Notes.S01E02.720p.mkv", duration = 2700),
            video("Harbour.Notes.S01E02.1080p.mkv", duration = 2710),
            video("Harbour.Notes.S01E02.Recap.mkv", duration = 300),
        )
        val show = (SeriesShelf.arrange(items, emptyMap()).single() as ShelfEntry.Show).series
        val twos = show.episodes.filter { it.episode == 2 }
        assertEquals(listOf(1, 2), twos.map { it.copies.size }.sorted())
    }

    @Test
    fun `air dates are episodes of a show, never copies of each other`() {
        val items = listOf(video("Morning Desk 2024.10.14 720p.mp4"), video("Morning Desk 2024.10.15 720p.mp4"))
        assertEquals(2, tiles(items).size)
        val same = listOf(video("Morning Desk 2024.10.14 720p.mp4"), video("Morning Desk 14-10-2024 1080p.mp4"))
        assertEquals(1, tiles(same).size)
    }

    @Test
    fun `a caption the chat puts under every post names nothing`() {
        val items = listOf(
            video("Coast Walk 720p.mp4", caption = "Weekly Walk E01"),
            video("Lake Walk 720p.mp4", caption = "Weekly Walk E01"),
            video("Hill Walk 720p.mp4", caption = "Weekly Walk E01"),
        )
        assertEquals(3, tiles(items).size)
    }

    @Test
    fun `numbered films never make a show`() {
        val items = listOf(video("Siren Patrol 100.mp4"), video("Siren Patrol 2.mp4"), video("Siren Patrol 3 (2019).mkv"))
        assertTrue(SeriesShelf.arrange(items, emptyMap()).none { it is ShelfEntry.Show })
    }

    @Test
    fun `fold film copies keeps episodes and puts extras after their film`() {
        val trailer = video("Night Lantern (2022) Trailer.mp4", size = 30 * MB, date = 5)
        val ep1 = video("Harbour.Notes.S01E01.mkv", date = 4)
        val film = video("Night Lantern (2022) 720p.mkv", date = 3)
        val copy = video("Night Lantern (2022) 1080p.mkv", date = 2)
        val ep2 = video("Harbour.Notes.S01E02.mkv", date = 1)
        val folded = SeriesShelf.foldFilmCopies(listOf(trailer, ep1, film, copy, ep2), emptyMap())
        assertEquals(listOf(ep1.id, film.id, trailer.id, ep2.id), folded.map { it.id })
        assertEquals(2, folded[1].versions.size)
    }

    @Test
    fun `two thousand files arrange quickly`() {
        val items = (0 until 2000).map { i ->
            when (i % 5) {
                0 -> video("Show ${i % 40}.S01E${(i / 40) % 30 + 1}.720p.mkv", duration = 2700)
                1 -> video("@HarbourMob_LinkZz_Film_${i % 300}_20${10 + i % 15}_Tamil_HDRip_700MB.mkv")
                2 -> video("Film ${i % 300} (20${10 + i % 15}) 1080p.mkv", duration = 7200)
                3 -> video("VID_2024${i}_120000.mp4", caption = "Clip number $i")
                else -> video("Lecture_${i}_-_Limits.mp4", duration = 1800)
            }
        }
        repeat(3) { SeriesShelf.arrange(items, emptyMap()) }
        // Fresh copies each time, so every name is parsed again as on a first listing; the best of
        // five, so a garbage collection does not decide it.
        val ms = (1..5).minOf {
            val fresh = items.map { it.copy() }
            val start = System.nanoTime()
            SeriesShelf.arrange(fresh, emptyMap())
            (System.nanoTime() - start) / 1_000_000.0
        }
        // The same machine's pace right now, on fixed string work of the same kind: a busy machine
        // (other builds, an emulator) slows both alike, so the budget follows it, while a real
        // regression in the arranging moves only the first figure.
        val pace = (1..5).minOf { calibration() }
        val budget = maxOf(ARRANGE_BUDGET_MS, pace * ARRANGE_PACE_FACTOR)
        println("arranging 2000 files: $ms ms, calibration $pace ms, budget $budget ms")
        assertTrue("arranging 2000 files took $ms ms (calibration $pace ms, budget $budget ms)", ms < budget)
    }

    /** Milliseconds for a fixed lot of name-like string work: lower case, split, rejoin, hash. */
    private fun calibration(): Double {
        val names = (0 until 2000).map { "Show ${it % 40}.S01E${it % 30}.720p.HDRip_${it}_Film.mkv" }
        val start = System.nanoTime()
        var sink = 0
        repeat(20) {
            for (name in names) {
                val words = name.lowercase().split('.', '_', ' ', '-')
                sink += words.joinToString(" ").hashCode()
            }
        }
        if (sink == 42) println(sink)
        return (System.nanoTime() - start) / 1_000_000.0
    }

    private companion object {
        const val MB = 1024L * 1024

        /** The budget on an idle machine: about 60 ms here, 200 ms failed once on a shared CI runner (2026-11-03). */
        const val ARRANGE_BUDGET_MS = 500.0

        /** How many calibration rounds the arranging may take; set from idle runs, with room. */
        const val ARRANGE_PACE_FACTOR = 5.0
    }
}
