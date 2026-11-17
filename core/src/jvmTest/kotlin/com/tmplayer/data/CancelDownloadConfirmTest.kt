package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Which downloads ask before Cancel, and that the answer reads the queue as it is then. */
class CancelDownloadConfirmTest {

    private val request = DownloadRequest(
        fileId = 7, title = "Episode", sizeBytes = 1000, chatId = 1, messageId = 2,
        chatTitle = "Chat", durationSec = 60, mimeType = "video/mp4", fileName = "e.mp4",
    )

    private fun row(stage: OfflineDownloads.Stage) = OfflineDownloads.Progress(request, 400, 1000, stage)

    @Test
    fun `a download being fetched or moved asks, every other stage goes at once`() {
        val asking = OfflineDownloads.Stage.entries.filter { CancelDownloadConfirm.needed(row(it)) }
        assertEquals(setOf(OfflineDownloads.Stage.Running, OfflineDownloads.Stage.Moving), asking.toSet())
        assertFalse(CancelDownloadConfirm.needed(row(OfflineDownloads.Stage.Failed)))
        assertFalse(CancelDownloadConfirm.needed(row(OfflineDownloads.Stage.Queued)))
    }

    @Test
    fun `a download that left the queue while the question was open is not cancelled`() {
        val queue = mapOf(7 to row(OfflineDownloads.Stage.Running))
        assertEquals(OfflineDownloads.Stage.Running, CancelDownloadConfirm.liveRow(7, queue)?.stage)
        // Finished, or cancelled from the notification: nothing is there to cancel (or to delete).
        assertNull(CancelDownloadConfirm.liveRow(7, emptyMap()))
        assertNull(CancelDownloadConfirm.liveRow(8, queue))
    }

    @Test
    fun `the answer sees a stage that changed meanwhile`() {
        val queue = mapOf(7 to row(OfflineDownloads.Stage.Paused))
        assertEquals(OfflineDownloads.Stage.Paused, CancelDownloadConfirm.liveRow(7, queue)?.stage)
    }
}
