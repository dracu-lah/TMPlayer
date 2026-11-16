package com.tmplayer.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.system.measureTimeMillis

/**
 * TS: with nothing else to go on, the copy of a video played without asking is the best picture the
 * device suits, not the newest post. The viewer's pick, a download, a started copy and the habit
 * still come first, and a copy the device decodes too slowly is left out.
 */
class BestCopyTest {

    private var nextId = 1L

    private fun copy(name: String, size: Long = 1_000_000_000, date: Int = nextId.toInt(), width: Int = 0, height: Int = 0) = MediaItem(
        chatId = 7, messageId = nextId++, fileId = 0, title = name, sizeBytes = size, durationSec = 2600,
        mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = date, fileName = name,
        width = width, height = height,
    )

    private var word = ""

    @Before
    fun device() {
        word = VideoCopies.deviceWord
        VideoCopies.deviceWord = "tv"
    }

    @After
    fun reset() {
        VideoCopies.deviceWord = word
    }

    private val low = copy("Harbour.Notes.S01E01.480p.mkv", date = 5)
    private val mid = copy("Harbour.Notes.S01E01.1080p.mkv", date = 3)
    private val top = copy("Harbour.Notes.S01E01.2160p.HEVC.mkv", date = 1)

    @Test
    fun `a television is handed the tallest copy it can play, not the newest`() {
        assertEquals(top.id, VideoCopies.pick(listOf(low, mid, top))?.id)
        assertEquals(top.id, VideoCopies.bestForDevice(listOf(low, mid, top), tooSlow = { false })?.id)
    }

    @Test
    fun `a phone starts at 1080p, and takes the lowest above it only when nothing else exists`() {
        VideoCopies.deviceWord = "phone"
        assertEquals(mid.id, VideoCopies.pick(listOf(low, mid, top))?.id)
        // Only pictures above 1080p exist: the lowest of them, not the tallest.
        assertEquals(1440, VideoCopies.heightOf(VideoCopies.pick(listOf(copy("Harbour.Notes.S01E01.2160p.mkv", date = 7), copy("Harbour.Notes.S01E01.1440p.mkv", date = 3)))!!))
    }

    @Test
    fun `a copy the device decodes too slowly is left out while any other remains, and named as passed over`() {
        val slow: (MediaItem) -> Boolean = { it.id == top.id }
        assertEquals(mid.id, VideoCopies.pick(listOf(low, mid, top), tooSlow = slow)?.id)
        assertEquals(top.id, VideoCopies.passedOver(listOf(low, mid, top), tooSlow = slow)?.id)
        // Every copy slow: the tallest of them still plays, rather than nothing.
        assertNotNull(VideoCopies.pick(listOf(mid, top), tooSlow = { true }))
    }

    @Test
    fun `the viewer's pick, a started copy, a download and the habit all come first`() {
        val all = listOf(low, mid, top)
        assertEquals(low.id, VideoCopies.pick(all, chosen = low.id)?.id)
        assertEquals(low.id, VideoCopies.pick(all, started = { it.id == low.id })?.id)
        val saved = low.copy(locality = MediaItem.Locality.Downloaded)
        assertEquals(low.id, VideoCopies.pick(listOf(saved, mid, top))?.id)
        val habit = QualityHabit().noted(480).noted(480)
        assertEquals(low.id, VideoCopies.pick(all, habit = habit)?.id)
    }

    @Test
    fun `a tie in height goes to the larger file and then the newer post, and copies that state no height keep the newest`() {
        val small = copy("Harbour.Notes.S01E02.1080p.mkv", size = 500_000_000, date = 9)
        val big = copy("Harbour.Notes.S01E02.1080p.x265.mkv", size = 2_000_000_000, date = 2)
        assertEquals(big.id, VideoCopies.bestForDevice(listOf(small, big), tooSlow = { false })?.id)
        val plainOld = copy("one.mkv", date = 1)
        val plainNew = copy("two.mkv", date = 8)
        assertNull(VideoCopies.bestForDevice(listOf(plainOld, plainNew), tooSlow = { false }))
        assertEquals(plainNew.id, VideoCopies.pick(listOf(plainOld, plainNew))?.id)
    }

    @Test
    fun `the height comes from the stated picture when the name says none`() {
        val a = copy("a.mkv", width = 1920, height = 1080, date = 1)
        val b = copy("b.mkv", width = 1280, height = 720, date = 9)
        assertEquals(a.id, VideoCopies.pick(listOf(b, a))?.id)
    }

    @Test
    fun `an episode with no history plays the best copy, one like the last watched wins after that`() {
        val e = listOf(low, mid, top).map { it.copy(fileName = it.fileName) }
        val episode = SeriesEpisode(item = e.first(), season = 1, episode = 1, copies = e)
        val none: (MediaItem) -> Boolean = { false }
        assertEquals(top.id, SeriesShelf.pick(episode, none, { 0f }, like = null).id)
        // Most like the last: a 480p copy watched leads to the 480p copy.
        assertEquals(low.id, SeriesShelf.pick(episode, none, { 0f }, like = low).id)
        // A copy partway through wins over both.
        assertEquals(mid.id, SeriesShelf.pick(episode, none, { if (it.id == mid.id) 0.4f else 0f }, like = low).id)
    }

    @Test
    fun `choosing for a whole show with four sizes an episode and many shows stays cheap`() {
        val shows = (1..400).map { n ->
            (1..4).map { q -> copy("Show$n.S01E01.${listOf(480, 720, 1080, 2160)[q - 1]}p.mkv", date = q) }
        }
        var picks = 0
        val ms = measureTimeMillis {
            repeat(30) { for (copies in shows) { VideoCopies.bestForDevice(copies, tooSlow = { false }); picks++ } }
        }
        assertEquals(12_000, picks)
        assertTrue("took $ms ms", ms < 2_000)
    }
}
