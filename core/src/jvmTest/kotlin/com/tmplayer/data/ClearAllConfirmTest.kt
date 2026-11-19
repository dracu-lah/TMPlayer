package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a clear-all asks, and that it reads the list as it is when answered (HX59). */
class ClearAllConfirmTest {

    @Test
    fun `an empty list asks nothing and clears nothing`() {
        assertFalse(ClearAllConfirm.needed(0))
        assertFalse(ClearAllConfirm.needed(-3))
        assertFalse(ClearAllConfirm.press(asking = false, count = 0))
        assertFalse(ClearAllConfirm.confirmClears(0))
    }

    @Test
    fun `a list with one card asks`() {
        assertTrue(ClearAllConfirm.press(asking = false, count = 1))
        assertTrue(ClearAllConfirm.confirmClears(1))
    }

    @Test
    fun `a second press while the question is up changes nothing`() {
        assertFalse(ClearAllConfirm.press(asking = true, count = 5))
    }

    @Test
    fun `the question closes when the list empties meanwhile and stays while it has cards`() {
        assertTrue(ClearAllConfirm.stillAsking(asking = true, count = 2))
        assertFalse(ClearAllConfirm.stillAsking(asking = true, count = 0))
        assertFalse(ClearAllConfirm.stillAsking(asking = false, count = 2))
    }

    @Test
    fun `history counts the cards of both lists and never goes negative`() {
        assertEquals(7, ClearAllConfirm.historyCards(3, 4))
        assertEquals(4, ClearAllConfirm.historyCards(-1, 4))
        assertEquals(0, ClearAllConfirm.historyCards(0, 0))
    }

    private fun resume(messageId: Long, fileName: String, updatedAt: Long) = ResumeRecord(
        chatId = 1, messageId = messageId, fileId = messageId.toInt(), title = fileName, chatTitle = "Chat", sizeBytes = 1000,
        durationSec = 60, positionMs = 1000, durationMs = 60_000, updatedAt = updatedAt, fileName = fileName,
    )

    private fun watched(messageId: Long, fileName: String, at: Long) = WatchedRecord(
        chatId = 1, messageId = messageId, fileId = messageId.toInt(), title = fileName, chatTitle = "Chat", sizeBytes = 1000,
        durationSec = 60, watchedAt = at, manual = true, fileName = fileName,
    )

    @Test
    fun `copies of one video count once, in the raw lists and in lists already folded`() {
        val hd = "Loki.S02E05.1080p.WEB.x265.mkv"
        val sd = "Loki.S02E05.720p.WEB.x264.mkv"
        val continuing = listOf(resume(1, hd, 20), resume(2, sd, 10), resume(3, "Other.Show.S01E01.mkv", 5))
        val seen = listOf(watched(4, hd, 20), watched(5, sd, 10))
        // 2 Continue cards and 1 Watched card, not 3 and 2.
        assertEquals(3, ClearAllConfirm.historyCards(continuing, seen))
        assertEquals(3, ClearAllConfirm.historyCards(HomeRows.foldCopies(continuing), HomeRows.foldWatched(seen)))
        assertEquals(0, ClearAllConfirm.historyCards(emptyList(), emptyList()))
    }
}
