package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lines of the Chapters page: who is listed, in what order, with what words, and which one is playing. */
class ChapterRowsTest {

    private val three = listOf(Chapter(0, "Opening"), Chapter(95_000, "Part A"), Chapter(1_310_000, "Ending"))

    @Test
    fun `the page is offered from two chapters`() {
        assertFalse(Chapters.listable(emptyList()))
        assertFalse(Chapters.listable(listOf(Chapter(0, "Only"))))
        assertTrue(Chapters.listable(three.take(2)))
    }

    @Test
    fun `each row has its name and start, and the one playing is marked`() {
        val rows = Chapters.rows(three, positionMs = 100_000)
        assertEquals(listOf("Opening", "Part A", "Ending"), rows.map { it.title })
        assertEquals(listOf(0L, 95_000L, 1_310_000L), rows.map { it.startMs })
        assertEquals(listOf(false, true, false), rows.map { it.current })
    }

    @Test
    fun `a chapter with no name is numbered by its place`() {
        val rows = Chapters.rows(listOf(Chapter(0, null), Chapter(60_000, "  "), Chapter(120_000, "Named")), 0)
        assertEquals(listOf("Chapter 1", "Chapter 2", "Named"), rows.map { it.title })
    }

    @Test
    fun `unsorted and repeated starts come out sorted, once each, numbered after sorting`() {
        val rows = Chapters.rows(listOf(Chapter(120_000, null), Chapter(0, "A"), Chapter(120_000, "Dup"), Chapter(-40, "Below")), 0)
        assertEquals(listOf(0L, 120_000L), rows.map { it.startMs })
        assertEquals("Chapter 2", rows[1].title)
    }

    @Test
    fun `before the first chapter none is marked, and with none there are no rows`() {
        val rows = Chapters.rows(listOf(Chapter(30_000, "Late start"), Chapter(90_000, "Next")), 1_000)
        assertTrue(rows.none { it.current })
        assertTrue(Chapters.rows(emptyList(), 5).isEmpty())
    }

    @Test
    fun `three hundred chapters list at once and the last is found`() {
        val many = List(300) { Chapter(it * 10_000L, null) }
        val rows = Chapters.rows(many, 2_995_000)
        assertEquals(300, rows.size)
        assertTrue(rows.last().current)
        assertEquals("Chapter 300", rows.last().title)
    }

    // PB-02: a malformed file's marks at or past the end, which a seek would turn into the end of the video.
    private val pastTheEnd = listOf(Chapter(0, "Opening"), Chapter(600_000, "Part A"), Chapter(1_199_500, "At the end"), Chapter(5_000_000, "Way past"))

    @Test
    fun `with the length known, marks at or past the end are not listed, offered or stepped to`() {
        val rows = Chapters.rows(pastTheEnd, positionMs = 700_000, durationMs = 1_200_000)
        assertEquals(listOf("Opening", "Part A"), rows.map { it.title })
        assertFalse(Chapters.listable(listOf(Chapter(0, "A"), Chapter(9_000_000, "B")), durationMs = 1_200_000))
        val marks = Chapters.inside(pastTheEnd, 1_200_000)
        assertEquals(listOf(0L, 600_000L), marks.map { it.startMs })
        assertEquals("forward from the last real chapter is the last one, not a seek past the end", null, Chapters.next(marks, 700_000))
        // The length unknown: everything stays, as before.
        assertEquals(4, Chapters.rows(pastTheEnd, 0).size)
        assertEquals(4, Chapters.inside(pastTheEnd, 0).size)
    }

    @Test
    fun `a chapter seek lands from zero to a second short of the end, whatever the mark says`() {
        assertEquals(1_199_000L, Chapters.seekTarget(5_000_000, 1_200_000))
        assertEquals(0L, Chapters.seekTarget(-40, 1_200_000))
        assertEquals(600_000L, Chapters.seekTarget(600_000, 1_200_000))
        // Unknown length (0, or Media3's TIME_UNSET): the mark as it is, never below zero.
        assertEquals(5_000_000L, Chapters.seekTarget(5_000_000, 0))
        assertEquals(5_000_000L, Chapters.seekTarget(5_000_000, Long.MIN_VALUE + 1))
    }

    @Test
    fun `inside sorts mpv's own order, so the flash names the chapter picked`() {
        val unsorted = listOf(Chapter(600_000, "Second"), Chapter(0, "First"))
        val marks = Chapters.inside(unsorted, 1_200_000)
        assertEquals("Second", marks[Chapters.at(marks, 600_000)].title)
    }

    @Test
    fun `ten thousand marks list a thousand lines, and a huge name is cut`() {
        val many = List(10_000) { Chapter(it * 1_000L, if (it == 0) "x".repeat(100_000) else null) }
        val rows = Chapters.rows(many, 0)
        assertEquals(Chapters.MAX_ROWS, rows.size)
        assertEquals(Chapters.MAX_TITLE, rows.first().title.length)
        assertTrue(rows.first().title.endsWith("\u2026"))
    }
}
