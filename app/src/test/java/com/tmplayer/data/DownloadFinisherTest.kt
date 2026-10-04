package com.tmplayer.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The last stage of a download: what moves where, in what order, and what waits for the player.
 */
class DownloadFinisherTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val settings by lazy {
        SettingsStore(SettingsStore.openDataStore(File(folder.root, "prefs/${SettingsStore.FILE_NAME}")))
    }
    private val cache by lazy { File(folder.root, "cache/videos").apply { mkdirs() } }
    private val downloads by lazy { File(folder.root, "downloads") }

    private fun request(fileId: Int, title: String = "Episode $fileId") = DownloadRequest(
        fileId = fileId,
        title = title,
        sizeBytes = 0,
        chatId = -100L,
        messageId = fileId.toLong(),
        chatTitle = "Chat",
        durationSec = 60,
        mimeType = "video/x-matroska",
        fileName = "Episode.$fileId.mkv",
    )

    private fun cached(fileId: Int, bytes: Int = 64): File =
        File(cache, "$fileId.mkv").apply { writeBytes(ByteArray(bytes) { it.toByte() }) }

    private fun row(request: DownloadRequest) = OfflineDownloads.note(
        OfflineDownloads.Progress(
            request = request,
            downloadedBytes = 64,
            totalBytes = 64,
            stage = OfflineDownloads.Stage.Running,
        ),
    )

    @After
    fun clearQueue() {
        OfflineDownloads.active.value.keys.forEach(OfflineDownloads::forget)
    }

    @Test
    fun `a finished file moves into Downloads, is recorded there, and TDLib lets go of it`() = runBlocking {
        val source = cached(1)
        val td = FakeTdFiles(paths = mutableMapOf(1 to source.path), bytes = mutableMapOf(1 to 64L))
        settings.rememberCachedVideo(request(1).item(), "Chat")

        val outcome = DownloadFinisher(settings, { downloads }, td, { td.paths[it] }, { false })
            .finish(request(1))

        val moved = (outcome as DownloadFinisher.Outcome.Moved).file
        assertEquals(File(downloads, "Episode 1.mkv"), moved)
        assertTrue(moved.isFile)
        assertFalse(source.exists())
        val record = settings.downloadRecord(-100L, 1L)!!
        assertEquals(moved.absolutePath, record.localPath)
        assertEquals(64L, record.sizeBytes)
        assertTrue(settings.cachedVideosNow().none { it.messageId == 1L })
        assertEquals(listOf(1), td.deleted)
    }

    @Test
    fun `a file a player has open waits for it, and the row says so`() = runBlocking {
        val source = cached(2)
        val td = FakeTdFiles(paths = mutableMapOf(2 to source.path))
        row(request(2))
        var asks = 0
        var heldRow: OfflineDownloads.Progress? = null

        val outcome = DownloadFinisher(
            settings, { downloads }, td, { td.paths[it] },
            isPlaying = { ++asks <= 3 },
            pollMs = 1,
        ).finish(request(2), onHeld = { heldRow = OfflineDownloads.active.value[2] })

        assertTrue(outcome is DownloadFinisher.Outcome.Moved)
        assertEquals(OfflineDownloads.Stage.Moving, heldRow?.stage)
        assertEquals(true, heldRow?.heldByPlayer)
        // Moved once the player had gone, not before.
        assertTrue(asks > 3)
        assertFalse(OfflineDownloads.active.value[2]!!.heldByPlayer)
    }

    @Test
    fun `a file TDLib cannot find is a failure that leaves nothing recorded`() = runBlocking {
        val td = FakeTdFiles()
        val outcome = DownloadFinisher(settings, { downloads }, td, { null }, { false }).finish(request(3))

        assertEquals(DownloadFinisher.Outcome.Failed(DownloadFinisher.NOT_FOUND), outcome)
        assertNull(settings.downloadRecord(-100L, 3L))
        assertTrue(td.deleted.isEmpty())
    }

    @Test
    fun `a move that cannot happen leaves the file in the cache and TDLib holding it`() = runBlocking {
        val source = cached(4)
        val td = FakeTdFiles(paths = mutableMapOf(4 to source.path))
        // A file where the folder should be: nothing can be created inside it.
        val blocked = File(folder.root, "blocked").apply { writeText("not a folder") }

        val outcome = DownloadFinisher(settings, { blocked }, td, { td.paths[it] }, { false }).finish(request(4))

        assertEquals(DownloadFinisher.Outcome.Failed(DownloadFinisher.MOVE_FAILED), outcome)
        assertTrue(source.isFile)
        assertNull(settings.downloadRecord(-100L, 4L))
        assertTrue(td.deleted.isEmpty())
    }

    @Test
    fun `two downloads of one name do not overwrite each other`() = runBlocking {
        val first = cached(5)
        val second = cached(6)
        val td = FakeTdFiles(paths = mutableMapOf(5 to first.path, 6 to second.path))
        val finisher = DownloadFinisher(settings, { downloads }, td, { td.paths[it] }, { false })

        val a = finisher.finish(request(5, title = "Same name")) as DownloadFinisher.Outcome.Moved
        val b = finisher.finish(request(6, title = "Same name")) as DownloadFinisher.Outcome.Moved

        assertEquals("Same name.mkv", a.file.name)
        assertEquals("Same name (2).mkv", b.file.name)
    }
}
