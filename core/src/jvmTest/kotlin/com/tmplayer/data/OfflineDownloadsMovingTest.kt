package com.tmplayer.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The move into Downloads as a stage of the queue, and who words a refusal. */
class OfflineDownloadsMovingTest {

    private val request = DownloadRequest(
        fileId = 9101, title = "Episode", sizeBytes = 1000, chatId = 1, messageId = 2,
        chatTitle = "Chat", durationSec = 60, mimeType = "video/mp4", fileName = "e.mp4",
    )

    @After
    fun cleanUp() {
        OfflineDownloads.forget(request.fileId)
    }

    private fun running(downloaded: Long = 400) = OfflineDownloads.note(
        OfflineDownloads.Progress(request, downloaded, 1000, OfflineDownloads.Stage.Running, bytesPerSecond = 50),
    )

    @Test
    fun `a moving row is busy, whole, and reports its own progress`() {
        running()
        OfflineDownloads.moving(request.fileId, movedBytes = 250)
        val row = OfflineDownloads.active.value.getValue(request.fileId)
        assertEquals(OfflineDownloads.Stage.Moving, row.stage)
        assertTrue(row.busy)
        assertEquals(1000L, row.downloadedBytes)
        assertEquals(0.25f, row.moveFraction!!, 0.001f)
        assertEquals(0L, row.bytesPerSecond)
        assertFalse(row.heldByPlayer)
    }

    @Test
    fun `a move waiting on a player says so, and loses the flag when it leaves the stage`() {
        running()
        OfflineDownloads.moving(request.fileId, heldByPlayer = true)
        assertTrue(OfflineDownloads.active.value.getValue(request.fileId).heldByPlayer)
        OfflineDownloads.stage(request.fileId, OfflineDownloads.Stage.Failed, "Could not move")
        val row = OfflineDownloads.active.value.getValue(request.fileId)
        assertFalse(row.heldByPlayer)
        assertNull(row.moveFraction)
    }

    @Test
    fun `a progress tick arriving during the move does not drag it back to running`() {
        running()
        OfflineDownloads.moving(request.fileId)
        OfflineDownloads.sample(request.fileId, 999)
        assertEquals(OfflineDownloads.Stage.Moving, OfflineDownloads.active.value.getValue(request.fileId).stage)
    }

    @Test
    fun `moving an id nobody asked for does nothing`() {
        OfflineDownloads.moving(424242)
        assertFalse(OfflineDownloads.isDownloading(424242))
    }

    @Test
    fun `a runner that cannot start words the failed row itself`() {
        val refusing = object : DownloadRunner {
            override val refusal = "The platform said no."
            override fun download(request: DownloadRequest) = error("refused")
            override fun cancel(fileId: Int) = Unit
            override fun pause(fileId: Int) = Unit
        }
        OfflineDownloads.start(refusing, request)
        val row = OfflineDownloads.active.value.getValue(request.fileId)
        assertEquals(OfflineDownloads.Stage.Failed, row.stage)
        assertEquals("The platform said no.", row.failure)
    }

    @Test
    fun `a runner with nothing to add gets the plain wording`() {
        val refusing = object : DownloadRunner {
            override fun download(request: DownloadRequest) = error("refused")
            override fun cancel(fileId: Int) = Unit
            override fun pause(fileId: Int) = Unit
        }
        OfflineDownloads.start(refusing, request)
        assertEquals(DownloadRunner.REFUSED_ANYWHERE, OfflineDownloads.active.value.getValue(request.fileId).failure)
    }
}
