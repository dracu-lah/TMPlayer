package com.tmplayer.ui.browse

import com.tmplayer.data.MediaItem
import com.tmplayer.data.VideoCopies
import com.tmplayer.i18n.L
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** "Best for this device" on the copies list: on the copy the app would play, only where the copies differ. */
class CopyBestTagTest {

    private var nextId = 1L
    private var word = ""
    private var slow: (MediaItem) -> Boolean = { false }

    private fun copy(name: String) = MediaItem(
        chatId = 7, messageId = nextId++, fileId = 0, title = name, sizeBytes = 1_000_000_000, durationSec = 2600,
        mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = nextId.toInt(), fileName = name,
    )

    @Before
    fun device() {
        word = VideoCopies.deviceWord
        slow = VideoCopies.tooSlowHere
        VideoCopies.deviceWord = "tv"
    }

    @After
    fun reset() {
        VideoCopies.deviceWord = word
        VideoCopies.tooSlowHere = slow
    }

    @Test
    fun `the best copy carries the tag and the others do not`() {
        val low = copy("Film.2020.480p.mkv")
        val top = copy("Film.2020.2160p.mkv")
        val all = listOf(low, top)
        val s = L.messages
        assertEquals("Best for this device", copyBest(top, s, all))
        assertNull(copyBest(low, s, all))
        // It shows at the front of a copy's detail line.
        assertEquals("Best for this device", copyDetail(top, s, null, among = all).substringBefore("  ·  "))
    }

    @Test
    fun `a single copy, equal pictures and copies that state none say nothing`() {
        val s = L.messages
        val a = copy("Film.2020.1080p.mkv")
        val b = copy("Film.2020.1080p.x265.mkv")
        assertNull(copyBest(a, s, listOf(a)))
        assertNull(copyBest(a, s, listOf(a, b)))
        val x = copy("one.mkv")
        val y = copy("two.mkv")
        assertNull(copyBest(x, s, listOf(x, y)))
    }

    @Test
    fun `a copy the device plays too slowly never carries the tag`() {
        val low = copy("Film.2020.720p.mkv")
        val top = copy("Film.2020.2160p.HEVC.mkv")
        VideoCopies.tooSlowHere = { it.id == top.id }
        val s = L.messages
        assertNull(copyBest(top, s, listOf(low, top)))
        assertEquals("Best for this device", copyBest(low, s, listOf(low, top)))
    }
}
