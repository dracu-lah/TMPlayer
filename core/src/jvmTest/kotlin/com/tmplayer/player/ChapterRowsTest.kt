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
}
