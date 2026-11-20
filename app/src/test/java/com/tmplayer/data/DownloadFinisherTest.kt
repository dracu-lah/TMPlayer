package com.tmplayer.data

import kotlinx.coroutines.CancellationException
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
import java.io.FileNotFoundException
import java.io.IOException

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

    // ---- HX69: every failure ends as a row, never as a crash ---------------------------------------

    /** A picked folder whose copy throws [failure] after "writing" a part file it cleans up itself. */
    private fun folderThatFails(failure: () -> Throwable, free: Long? = null) = object : DownloadFinisher.TreeTarget {
        override val label = "Movies"
        override fun freeBytes(): Long? = free
        override suspend fun put(source: File, name: String, onProgress: (Long, Long) -> Unit): String {
            onProgress(10, source.length())
            throw failure()
        }
    }

    private fun finishInto(fileId: Int, target: DownloadFinisher.TreeTarget): Pair<DownloadFinisher.Outcome, File> {
        val source = cached(fileId)
        val td = FakeTdFiles(paths = mutableMapOf(fileId to source.path))
        val outcome = runBlocking {
            DownloadFinisher(settings, { downloads }, td, { td.paths[it] }, { false }, tree = { target }).finish(request(fileId))
        }
        // Whatever the failure, the whole file is where it was, TDLib still holds it and nothing is recorded.
        assertTrue(source.isFile)
        assertEquals(64L, source.length())
        assertNull(runBlocking { settings.downloadRecord(-100L, fileId.toLong()) })
        assertTrue(td.deleted.isEmpty())
        return outcome to source
    }

    @Test
    fun `a folder permission taken back is a failed row that says so`() {
        val (outcome, _) = finishInto(10, folderThatFails({ SecurityException("grant revoked") }))
        assertEquals(DownloadFinisher.Outcome.Failed(DownloadFinisher.MOVE_FAILED_PERMISSION), outcome)
    }

    @Test
    fun `a security exception inside an IOException still counts as the permission`() {
        val (outcome, _) = finishInto(11, folderThatFails({ IOException("Folder permission lost", SecurityException("x")) }))
        assertEquals(DownloadFinisher.Outcome.Failed(DownloadFinisher.MOVE_FAILED_PERMISSION), outcome)
    }

    @Test
    fun `an IllegalStateException from a provider is contained and logged, not thrown`() {
        val before = com.tmplayer.platform.JobFailures.count
        val (outcome, _) = finishInto(12, folderThatFails({ IllegalStateException("provider died") }))
        assertEquals(DownloadFinisher.Outcome.Failed(DownloadFinisher.MOVE_FAILED_UNEXPECTED), outcome)
        assertEquals(before + 1, com.tmplayer.platform.JobFailures.count)
    }

    @Test
    fun `running out of memory is a failed row, not a dead process`() {
        val (outcome, _) = finishInto(13, folderThatFails({ OutOfMemoryError("Map failed") }))
        assertEquals(DownloadFinisher.Outcome.Failed(DownloadFinisher.MOVE_FAILED_MEMORY), outcome)
    }

    @Test
    fun `a full drive in the picked folder names the folder`() {
        val (outcome, _) = finishInto(14, folderThatFails({ IOException("write failed: ENOSPC (No space left on device)") }))
        val reason = (outcome as DownloadFinisher.Outcome.Failed).reason
        assertTrue(reason, reason.contains("Movies"))
    }

    @Test
    fun `a folder with too little room is refused before anything is copied`() {
        var copied = false
        val folder = object : DownloadFinisher.TreeTarget {
            override val label = "Movies"
            override fun freeBytes(): Long? = 10L
            override suspend fun put(source: File, name: String, onProgress: (Long, Long) -> Unit): String {
                copied = true
                return "content://x"
            }
        }
        val (outcome, _) = finishInto(15, folder)
        assertTrue(outcome is DownloadFinisher.Outcome.Failed)
        assertFalse(copied)
    }

    @Test
    fun `a folder that cannot even say its free space or name still ends in a failure`() {
        val folder = object : DownloadFinisher.TreeTarget {
            override val label: String get() = throw IllegalStateException("no label")
            override fun freeBytes(): Long? = throw SecurityException("no access")
            override suspend fun put(source: File, name: String, onProgress: (Long, Long) -> Unit): String =
                throw IOException("Folder not reachable")
        }
        val (outcome, _) = finishInto(16, folder)
        assertEquals(DownloadFinisher.Outcome.Failed(DownloadFinisher.MOVE_FAILED), outcome)
    }

    @Test
    fun `a file that vanished during the copy is the file missing, not the folder`() {
        val source = cached(17)
        val td = FakeTdFiles(paths = mutableMapOf(17 to source.path))
        val folder = object : DownloadFinisher.TreeTarget {
            override val label = "Movies"
            override fun freeBytes(): Long? = null
            override suspend fun put(source: File, name: String, onProgress: (Long, Long) -> Unit): String {
                source.delete()
                throw FileNotFoundException("gone")
            }
        }
        val outcome = runBlocking {
            DownloadFinisher(settings, { downloads }, td, { td.paths[it] }, { false }, tree = { folder }).finish(request(17))
        }
        assertEquals(DownloadFinisher.Outcome.Failed(DownloadFinisher.NOT_FOUND), outcome)
        assertNull(runBlocking { settings.downloadRecord(-100L, 17L) })
    }

    @Test
    fun `a cancel is not a failure and goes on to whoever cancelled`() {
        val source = cached(18)
        val td = FakeTdFiles(paths = mutableMapOf(18 to source.path))
        try {
            runBlocking {
                DownloadFinisher(
                    settings, { downloads }, td, { td.paths[it] }, { false },
                    tree = { folderThatFails({ CancellationException("cancelled") }) },
                ).finish(request(18))
            }
            org.junit.Assert.fail("expected the cancellation")
        } catch (_: CancellationException) {
        }
        assertTrue(source.isFile)
    }

    @Test
    fun `a Downloads folder that cannot be reached is a failed row on every path`() = runBlocking {
        val source = cached(19)
        val td = FakeTdFiles(paths = mutableMapOf(19 to source.path))
        val revoked = DownloadFinisher(settings, { throw SecurityException("storage revoked") }, td, { td.paths[it] }, { false })
        assertEquals(DownloadFinisher.Outcome.Failed(DownloadFinisher.MOVE_FAILED_PERMISSION), revoked.finish(request(19)))

        val broken = DownloadFinisher(settings, { throw IllegalStateException("no storage") }, td, { td.paths[it] }, { false })
        assertEquals(DownloadFinisher.Outcome.Failed(DownloadFinisher.MOVE_FAILED_UNEXPECTED), broken.finish(request(19)))
        assertTrue(source.isFile)
        assertNull(settings.downloadRecord(-100L, 19L))
    }

    @Test
    fun `TDLib throwing while asked whether the file may be kept is a failed row`() = runBlocking {
        val source = cached(20)
        val td = FakeTdFiles(paths = mutableMapOf(20 to source.path))
        val outcome = DownloadFinisher(
            settings, { downloads }, td, { td.paths[it] }, { false },
            maySave = { _, _ -> throw IllegalStateException("td closed") },
        ).finish(request(20))
        assertEquals(DownloadFinisher.Outcome.Failed(DownloadFinisher.MOVE_FAILED_UNEXPECTED), outcome)
        assertTrue(source.isFile)
    }

    @Test
    fun `a cached file that is gone from disk fails as missing and creates nothing`() = runBlocking {
        val td = FakeTdFiles(paths = mutableMapOf(21 to File(cache, "nothing.mkv").path))
        val outcome = DownloadFinisher(settings, { downloads }, td, { td.paths[it] }, { false }).finish(request(21))
        assertEquals(DownloadFinisher.Outcome.Failed(DownloadFinisher.NOT_FOUND), outcome)
        assertFalse(downloads.exists() && downloads.list().orEmpty().isNotEmpty())
    }

    @Test
    fun `a revoked folder or a full heap are expected conditions and are not reported as bugs`() {
        val before = com.tmplayer.platform.JobFailures.count
        finishInto(22, folderThatFails({ SecurityException("grant revoked") }))
        finishInto(23, folderThatFails({ OutOfMemoryError("Map failed") }))
        assertEquals(before, com.tmplayer.platform.JobFailures.count)
    }

    @Test
    fun `a folder with no readable name and a full drive gives the plain full-storage sentence`() {
        val folder = object : DownloadFinisher.TreeTarget {
            override val label: String get() = throw IllegalStateException("no label")
            override fun freeBytes(): Long? = null
            override suspend fun put(source: File, name: String, onProgress: (Long, Long) -> Unit): String =
                throw IOException("write failed: ENOSPC (No space left on device)")
        }
        val (outcome, _) = finishInto(24, folder)
        assertEquals(DownloadFinisher.Outcome.Failed(com.tmplayer.i18n.L.errorsNoSpace), outcome)
    }

    private val brokenRecord: suspend (MediaItem, String, String) -> Unit = { _, _, _ -> throw IOException("disk full") }

    @Test
    fun `a record that cannot be written after the move still counts the download as done`() = runBlocking {
        val source = cached(25)
        val td = FakeTdFiles(paths = mutableMapOf(25 to source.path))
        val before = com.tmplayer.platform.JobFailures.count
        val outcome = DownloadFinisher(settings, { downloads }, td, { td.paths[it] }, { false }, pollMs = 1, noteDownload = brokenRecord)
            .finish(request(25))

        val moved = (outcome as DownloadFinisher.Outcome.Moved).file
        assertTrue(moved.isFile)
        assertFalse(source.exists())
        assertEquals(listOf(25), td.deleted)
        assertEquals(before + 1, com.tmplayer.platform.JobFailures.count)
    }

    @Test
    fun `in a picked folder a record that cannot be written does not make a second copy`() = runBlocking {
        val source = cached(26)
        val td = FakeTdFiles(paths = mutableMapOf(26 to source.path))
        var puts = 0
        val folder = object : DownloadFinisher.TreeTarget {
            override val label = "Movies"
            override fun freeBytes(): Long? = null
            override suspend fun put(source: File, name: String, onProgress: (Long, Long) -> Unit): String =
                "content://movies/${++puts}"
        }
        val outcome = DownloadFinisher(settings, { downloads }, td, { td.paths[it] }, { false }, pollMs = 1, tree = { folder }, noteDownload = brokenRecord)
            .finish(request(26))

        assertEquals(DownloadFinisher.Outcome.MovedToTree("content://movies/1"), outcome)
        assertEquals(1, puts)
        assertFalse(source.exists())
    }
}
