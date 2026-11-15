package com.tmplayer.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N3 wired in: spelling twins of one title (the survey's Aniyathipraavu x4, Kathanayakan and
 * Kadhanayakan, Aarya 2 and Arya 2) group together in Home's rows ([SameTitle]) and in a chat's
 * shelf ([SeriesShelf]); two different titles, a sequel number, a short name and a video the viewer
 * set apart never do.
 */
class SpellingTwinsGroupingTest {

    @After
    fun forget() = VideoCorrections.set(emptyMap())

    private var nextId = 1L

    private fun video(fileName: String, chatId: Long = 7, duration: Int = 8_000, date: Int = nextId.toInt()) = MediaItem(
        chatId = chatId,
        messageId = nextId++,
        fileId = 0,
        title = fileName,
        sizeBytes = 1_500L * SizeFilter.MB,
        durationSec = duration,
        mimeType = "video/x-matroska",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = date,
        fileName = fileName,
        caption = "",
    )

    private val noOnline: (MediaItem) -> String? = { null }

    private fun dedupe(vararg names: String, duration: (Int) -> Int = { 8_000 }): List<ShelfEntry> =
        SameTitle.dedupe(names.mapIndexed { i, n -> ShelfEntry.File(video(n, chatId = i + 1L, duration = duration(i))) }, noOnline)

    // ---- Home's rows and the carousel -----------------------------------------------------------

    @Test
    fun `four spellings of Aniyathipraavu with one year are one tile with four copies`() {
        val folded = dedupe(
            "Aniyathipraavu.2017.1080p.mkv",
            "Aniyathi.Pravu.2017.720p.mkv",
            "Aniyathipravu (2017) WEB-DL.mkv",
            "ANIYATHI-PRAAVU 2017 HDRip.mkv",
        )
        assertEquals(1, folded.size)
        assertEquals(4, (folded.single() as ShelfEntry.File).item.versions.size)
    }

    @Test
    fun `Kathanayakan and Kadhanayakan with one year are one, Aarya 2 and Arya 2 are one`() {
        assertEquals(1, dedupe("Kathanayakan.2010.mkv", "Kadhanayakan.2010.1080p.mkv").size)
        assertEquals(1, dedupe("Aarya 2 (2009) 720p.mkv", "Arya.2.2009.1080p.mkv").size)
    }

    @Test
    fun `a sequel number, another year, another name or another length keeps two tiles`() {
        assertEquals("a sequel number stays", 2, dedupe("Aarya 2 (2009).mkv", "Arya (2009).mkv").size)
        assertEquals("Aarya 2 is not Aarya 3", 2, dedupe("Aarya 2 (2009).mkv", "Arya 3 (2009).mkv").size)
        assertEquals("a remake is another film", 2, dedupe("Aniyathipraavu (2017).mkv", "Aniyathi Pravu (1997).mkv").size)
        assertEquals("one letter apart is another name", 2, dedupe("Kathanayakan (2010).mkv", "Kathanayikan (2010).mkv").size)
        assertEquals("a short title is never folded", 2, dedupe("Bad (2010).mkv", "Pad (2010).mkv").size)
        assertEquals(
            "a cut of another length is another video",
            2,
            dedupe("Kathanayakan 2010.mkv", "Kadhanayakan 2010.mkv") { if (it == 0) 8_000 else 2_000 }.size,
        )
    }

    @Test
    fun `a name with no year never folds by spelling alone`() {
        assertNull(SameTitle.spellingFilmKey(video("Kathanayakan.1080p.mkv")))
        assertEquals(2, dedupe("Kathanayakan 1080p.mkv", "Kadhanayakan 720p.mkv").size)
    }

    @Test
    fun `the viewer's Not the same video keeps a twin apart`() {
        val a = video("Kathanayakan.2010.mkv", chatId = 1)
        val b = video("Kadhanayakan.2010.mkv", chatId = 2)
        VideoCorrections.set(mapOf(b.id to VideoCorrection(separate = true)))
        assertNull(SameTitle.spellingFilmKey(b))
        assertEquals(2, SameTitle.dedupe(listOf(ShelfEntry.File(a), ShelfEntry.File(b)), noOnline).size)
    }

    @Test
    fun `the keys everything else reads keep their words`() {
        assertEquals("film:khalifa|2026", SameTitle.filmKey(video("Khalifa.2026.1080p.mkv")))
        assertNotNull(SameTitle.spellingFilmKey(video("Khalifa.2026.1080p.mkv")))
        assertEquals("khalifa|2026", SeriesShelf.sameVideoKey(video("Khalifa.2026.1080p.mkv")))
    }

    // ---- a chat's shelf -------------------------------------------------------------------------

    private fun shows(entries: List<ShelfEntry>) = entries.filterIsInstance<ShelfEntry.Show>().map { it.series }

    @Test
    fun `episodes of one show spelt two ways in a chat are one show`() {
        val items = listOf(
            video("Kathanayakan.S01E01.720p.mkv"),
            video("Kadhanayakan.S01E02.720p.mkv"),
            video("Kathanayakan.S01E03.720p.mkv"),
        )
        val show = shows(SeriesShelf.arrange(items)).single()
        assertEquals(listOf(1, 2, 3), show.episodes.map { it.episode })
        // The key is one of the written ones (the one the most files carry), so nothing stored on a key moves.
        assertEquals("kathanayakan", show.key)
    }

    @Test
    fun `a show and its sequel under a number stay two`() {
        val items = listOf(
            video("Aarya.S01E01.mkv"), video("Arya.S01E02.mkv"),
            video("Aarya.2.S01E01.mkv"), video("Arya.2.S01E02.mkv"),
        )
        val found = shows(SeriesShelf.arrange(items))
        assertEquals(2, found.size)
        assertTrue(found.all { it.episodes.size == 2 })
    }

    @Test
    fun `a show that only one spelling had is left as it was`() {
        val items = listOf(video("Harbour.Notes.S01E01.mkv"), video("Harbour.Notes.S01E02.mkv"))
        val show = shows(SeriesShelf.arrange(items)).single()
        assertEquals("harbour notes", show.key)
    }

    @Test
    fun `copies of a film spelt two ways in one chat are one tile`() {
        val items = listOf(video("Aarya 2 2009 720p.mkv"), video("Arya.2.2009.1080p.mkv"))
        val folded = SeriesShelf.foldFilmCopies(items)
        assertEquals(1, folded.size)
        assertEquals(2, folded.single().versions.size)
    }

    @Test
    fun `names that fold together but carry no year are never copies of one another`() {
        // "g" and "k" are one letter to the spelling key; without a year nothing else says they are one film.
        val items = (listOf("Holiday ag", "Holiday ak", "Holiday ab", "Holiday ap")).map { video("$it.mp4", duration = 60) }
        assertEquals(4, SeriesShelf.foldFilmCopies(items).size)
        assertEquals(4, SameTitle.dedupe(items.map { ShelfEntry.File(it) }, noOnline).size)
    }

    @Test
    fun `two films whose names differ by a sequel number are two tiles in one chat`() {
        val items = listOf(video("Aarya 2009 720p.mkv"), video("Arya.2.2009.1080p.mkv"))
        assertEquals(2, SeriesShelf.foldFilmCopies(items).size)
    }
}
