package com.tmplayer.data

import com.tmplayer.data.ContentProtection.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentProtectionTest {

    @Test
    fun `an ordinary video can be kept`() {
        assertEquals(Verdict.Full, ContentProtection.verdict(canBeSaved = true, chatProtected = false, selfDestructs = false))
    }

    @Test
    fun `a message that cannot be saved streams and nothing else`() {
        assertEquals(Verdict.StreamOnly, ContentProtection.verdict(canBeSaved = false, chatProtected = false, selfDestructs = false))
    }

    @Test
    fun `a protected chat outvotes a message that says it can be saved`() {
        assertEquals(Verdict.StreamOnly, ContentProtection.verdict(canBeSaved = true, chatProtected = true, selfDestructs = false))
    }

    @Test
    fun `self-destructing media is hidden whatever else is true`() {
        assertEquals(Verdict.Hidden, ContentProtection.verdict(canBeSaved = true, chatProtected = false, selfDestructs = true))
        assertEquals(Verdict.Hidden, ContentProtection.verdict(canBeSaved = false, chatProtected = true, selfDestructs = true))
    }

    private data class Entry(val id: Long, val video: Boolean, val timer: Boolean)

    private fun item(id: Long) = MediaItem(
        chatId = 1, messageId = id, fileId = id.toInt(), title = "v$id", sizeBytes = 0,
        durationSec = 0, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 0,
    )

    private fun screen(vararg entries: Entry) =
        ContentProtection.screen(entries.toList(), { it.timer }) { if (it.video) item(it.id) else null }

    @Test
    fun `self-destructing videos are left out of the page and counted`() {
        val screened = screen(Entry(3, true, false), Entry(2, true, true), Entry(1, true, false))
        assertEquals(listOf(3L, 1L), screened.items.map { it.messageId })
        assertEquals(1, screened.selfDestructing)
    }

    @Test
    fun `a self-destructing message that is not a video is not counted as a missing video`() {
        val screened = screen(Entry(2, false, true), Entry(1, true, false))
        assertEquals(listOf(1L), screened.items.map { it.messageId })
        assertEquals(0, screened.selfDestructing)
    }

    @Test
    fun `the saving rule survives a change of locality`() {
        val locked = item(1).copy(canBeSaved = false)
        assertFalse(locked.withLocality(MediaItem.Locality.Cached).canBeSaved)
        assertTrue(item(1).withLocality(MediaItem.Locality.Downloaded).canBeSaved)
    }

    @Test
    fun `a locked video is not equal to the same video unlocked`() {
        // Equality drives what the grid redraws, so a chat turning protection on must reach the tile.
        assertFalse(item(1) == item(1).copy(canBeSaved = false))
    }
}
