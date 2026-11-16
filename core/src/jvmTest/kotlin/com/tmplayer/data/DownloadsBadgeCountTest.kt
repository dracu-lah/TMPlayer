package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Downloads badge on the phone, TV and desktop bars counts what is coming down now, nothing waiting or held. */
class DownloadsBadgeCountTest {

    private fun row(id: Int, stage: OfflineDownloads.Stage) = OfflineDownloads.Progress(
        DownloadRequest(
            fileId = id, title = "v$id", sizeBytes = 10, chatId = 1, messageId = 2,
            chatTitle = "Chat", durationSec = 1, mimeType = "video/mp4", fileName = "v$id.mp4",
        ),
        downloadedBytes = 0, totalBytes = 10, stage = stage,
    )

    @Test
    fun `only Running rows are counted, whatever stage the others are in`() {
        for (stage in OfflineDownloads.Stage.entries) {
            val expected = if (stage == OfflineDownloads.Stage.Running) 1 else 0
            assertEquals("stage $stage", expected, OfflineDownloads.runningCount(mapOf(1 to row(1, stage))))
        }
    }

    @Test
    fun `a mixed queue counts its running downloads`() {
        val queue = listOf(
            OfflineDownloads.Stage.Running, OfflineDownloads.Stage.Queued, OfflineDownloads.Stage.NoWifi,
            OfflineDownloads.Stage.Paused, OfflineDownloads.Stage.Running, OfflineDownloads.Stage.Failed,
        ).mapIndexed { i, stage -> i to row(i, stage) }.toMap()
        assertEquals(2, OfflineDownloads.runningCount(queue))
        assertEquals(0, OfflineDownloads.runningCount(emptyMap()))
    }
}
