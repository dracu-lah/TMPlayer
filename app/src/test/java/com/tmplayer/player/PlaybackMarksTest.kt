package com.tmplayer.player

import android.view.KeyEvent
import com.tmplayer.data.LogScrubber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** "Report stutter" (L3): the marks, the last minute they keep, and the lines they give Send logs. */
class PlaybackMarksTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var now = 1_000_000L

    private fun marks(name: String = "marks.jsonl") = PlaybackMarks(File(folder.root, name)) { now }

    private fun window(count: Int, everyMs: Long = 5_000L, dropped: Int = 0, wait: Long = 0L, base: Long = 500_000L) =
        (0 until count).map { TraceSample(atMs = base + it * everyMs, intervalMs = everyMs, droppedFrames = dropped, readWaitMs = wait, bufferAheadMs = 8_000) }

    @Test
    fun `a mark keeps only the last minute of the window`() {
        val m = marks()
        assertEquals(PlaybackMarks.Result.KEPT, m.mark(window(40)))
        val kept = m.marks().single().samples
        // 40 readings 5 s apart span 195 s; the minute that ends at the newest is 12 of them.
        assertEquals(12, kept.size)
        assertEquals(500_000L + 39 * 5_000L, kept.last().atMs)
        assertTrue(kept.last().atMs - kept.first().atMs < PlaybackMarks.WINDOW_MS)
    }

    @Test
    fun `at the fastest cadence a mark stays within its reading cap`() {
        val m = marks()
        m.mark(window(500, everyMs = 100))
        assertTrue(m.marks().single().samples.size <= PlaybackMarks.MAX_READINGS)
    }

    @Test
    fun `pressed twice is one mark and the second says it was already marked`() {
        val m = marks()
        assertEquals(PlaybackMarks.Result.KEPT, m.mark(window(3)))
        now += 1_000
        assertEquals(PlaybackMarks.Result.ALREADY, m.mark(window(3)))
        assertEquals(1, m.marks().size)
        now += PlaybackMarks.DEBOUNCE_MS
        assertEquals(PlaybackMarks.Result.KEPT, m.mark(window(3)))
        assertEquals(2, m.marks().size)
    }

    @Test
    fun `a mark with nothing to keep, as in a seek right at the start, is still a mark`() {
        val m = marks()
        assertEquals(PlaybackMarks.Result.KEPT, m.mark(emptyList()))
        assertEquals(1, m.marks().size)
        assertTrue(m.marks().single().samples.isEmpty())
        assertTrue(m.reportLines().any { it.contains("0 reading(s)") })
    }

    @Test
    fun `only the last five marks are kept`() {
        val m = marks()
        repeat(8) {
            now += PlaybackMarks.DEBOUNCE_MS
            m.mark(window(2, dropped = it, base = 500_000L + it * 100_000L))
        }
        assertEquals(PlaybackMarks.MAX_MARKS, m.marks().size)
        assertEquals(listOf(3, 4, 5, 6, 7), m.marks().map { it.samples.first().droppedFrames })
    }

    @Test
    fun `a second mark inside the same minute adds only the readings after the first`() {
        val m = marks()
        m.mark(window(6))
        now += PlaybackMarks.DEBOUNCE_MS
        m.mark(window(9))
        val sizes = m.marks().map { it.samples.size }
        assertEquals(listOf(6, 3), sizes)
        val all = m.marks().flatMap { it.samples }.map { it.atMs }
        assertEquals(all.sorted(), all)
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun `a clock that went backwards never loses the mark`() {
        val m = marks()
        m.mark(window(2))
        now -= 3_600_000L
        // A negative gap is not "just now": it marks.
        assertEquals(PlaybackMarks.Result.KEPT, m.mark(window(2)))
    }

    @Test
    fun `a folder that cannot be written gives FAILED and never throws`() {
        val blocker = File(folder.root, "blocker").apply { writeText("a file where a folder should be") }
        val m = PlaybackMarks(File(blocker, "marks.jsonl")) { now }
        assertEquals(PlaybackMarks.Result.FAILED, m.mark(window(2)))
        assertTrue(m.marks().isEmpty())
        assertTrue(m.reportLines().isEmpty())
    }

    @Test
    fun `a garbage file is read as no marks and replaced by the next one`() {
        val file = File(folder.root, "marks.jsonl")
        file.writeText("}{ not json\n" + "x".repeat(10_000) + "\n\u0000")
        val m = PlaybackMarks(file) { now }
        assertTrue(m.marks().isEmpty())
        assertEquals(PlaybackMarks.Result.KEPT, m.mark(window(2)))
        assertEquals(1, m.marks().size)
    }

    @Test
    fun `the report lines name the verdict and survive the log scrubber unchanged`() {
        val m = marks()
        m.mark(window(6, dropped = 60))
        val lines = m.reportLines()
        assertTrue(lines.first().startsWith("Playback trace: 1 moment(s)"))
        assertTrue(lines.any { it.startsWith("Marked at ") && it.contains("verdict ") })
        val scrubber = LogScrubber()
        for (line in lines) assertEquals(line, scrubber.scrub(line))
        assertEquals(1 + 1 + 6, lines.size)
        assertTrue(lines.last().contains("dropped=60"))
    }

    @Test
    fun `a slow line reads as the network in the report`() {
        val m = marks()
        m.mark(window(6, wait = 3_000).map { it.copy(underruns = 1) })
        assertTrue(m.reportLines().any { it.contains("verdict network_bound") })
    }

    @Test
    fun `no marks give no lines and delete empties them`() {
        val m = marks()
        assertTrue(m.reportLines().isEmpty())
        m.mark(window(2))
        assertFalse(m.reportLines().isEmpty())
        m.delete()
        assertTrue(m.reportLines().isEmpty())
        now += 1
        assertEquals(PlaybackMarks.Result.KEPT, m.mark(window(2)))
    }

    @Test
    fun `only a held Info key marks, a plain press is left alone`() {
        assertTrue(PlaybackMarks.isMarkKey(KeyEvent.KEYCODE_INFO, isLongPress = true))
        assertFalse(PlaybackMarks.isMarkKey(KeyEvent.KEYCODE_INFO, isLongPress = false))
        assertFalse(PlaybackMarks.isMarkKey(KeyEvent.KEYCODE_DPAD_CENTER, isLongPress = true))
        assertFalse(PlaybackMarks.isMarkKey(KeyEvent.KEYCODE_MENU, isLongPress = true))
    }
}
