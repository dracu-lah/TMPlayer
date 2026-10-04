package com.tmplayer.desktop

import com.tmplayer.data.CacheShelf
import com.tmplayer.data.LocalFileAvailability
import com.tmplayer.data.MediaItem
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.StorageRelocationPlan
import com.tmplayer.data.TdFiles
import com.tmplayer.desktop.ui.GridSelection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The desktop's downloads index, the one time migration of old downloads, the room check before
 * play, the Cache limit stepper and the grid's picking: each over real folders or plain values.
 */
class DesktopStorageTest {

    private val tmp = Files.createTempDirectory("tm-desk-storage").toFile()
    private val settings = SettingsStore(SettingsStore.openDataStore(File(tmp, "store/" + SettingsStore.FILE_NAME)))
    private val gb = 1024L * 1024 * 1024

    private fun record(id: Long, path: String?, size: Long = 4) =
        ResumeRecord(-1, id, id.toInt(), "v$id", "c", size, 0, 0, 0, 0, path)

    private fun item(id: Long, size: Long = 4) = MediaItem(
        chatId = -1, messageId = id, fileId = id.toInt(), title = "Film $id.mkv", sizeBytes = size,
        durationSec = 0, mimeType = "", thumbnailFileId = 0, miniThumbnail = null, date = 0,
    )

    // ---- the index --------------------------------------------------------------------------

    @Test
    fun `a record is present, missing, the wrong size, or from before downloads had a folder`() {
        val file = File(tmp, "a.mkv").apply { writeBytes(ByteArray(4)) }
        assertEquals(DownloadIndex.FileState.Present, DownloadIndex.state(record(1, file.path)))
        assertEquals(DownloadIndex.FileState.Missing, DownloadIndex.state(record(2, File(tmp, "gone.mkv").path)))
        assertEquals(DownloadIndex.FileState.Missing, DownloadIndex.state(record(3, file.path, size = 9)))
        assertEquals(DownloadIndex.FileState.Legacy, DownloadIndex.state(record(4, null)))
    }

    @Test
    fun `files the index does not name are offered, part files and the marker are not`() = runBlocking {
        val dir = File(tmp, "downloads").apply { mkdirs() }
        val known = File(dir, "known.mkv").apply { writeText("x") }
        File(dir, "stranger.mp4").writeText("x")
        File(dir, "half.mkv.part").writeText("x")
        File(dir, StorageRelocationPlan.MARKER).writeText("x")
        settings.noteDownload(item(1), "c", known.absolutePath)
        val found = DownloadIndex.scan(settings, dir)
        assertEquals(listOf("stranger.mp4"), found.map { it.name })
        DownloadIndex.keep(settings, found.single())
        assertTrue(DownloadIndex.unlistedIn(dir, settings.downloadsNow()).isEmpty())
        val kept = settings.downloadsNow().single { it.chatId == 0L }
        assertEquals(File(dir, "stranger.mp4").absolutePath, kept.localPath)
    }

    @Test
    fun `deleting a download takes the file and then the record`() = runBlocking {
        val file = File(tmp, "b.mkv").apply { writeBytes(ByteArray(4)) }
        settings.noteDownload(item(5), "c", file.absolutePath)
        assertTrue(DownloadIndex.delete(settings, settings.downloadRecord(-1, 5)!!))
        assertFalse(file.exists())
        assertNull(settings.downloadRecord(-1, 5))
    }

    // ---- migration ----------------------------------------------------------------------------

    private class FakeFiles(val paths: Map<Int, String>) : TdFiles {
        val deleted = mutableListOf<Int>()
        override suspend fun currentFileId(chatId: Long, messageId: Long, storedFileId: Int) = storedFileId
        override suspend fun deleteFile(fileId: Int) { deleted += fileId }
        override suspend fun localDownloadedBytes(fileId: Int) = 0L
        override suspend fun localPathAnyway(fileId: Int) = paths[fileId]
    }

    @Test
    fun `old downloads in the cache move into the folder once, partial ones stay for Resume`() = runBlocking {
        val cache = File(tmp, "cache/videos").apply { mkdirs() }
        val whole = File(cache, "12.mkv").apply { writeBytes(ByteArray(10)) }
        val half = File(cache, "13.mkv").apply { writeBytes(ByteArray(5)) }
        settings.noteDownload(item(12, 10), "c")
        settings.noteDownload(item(13, 10), "c")
        val files = FakeFiles(mapOf(12 to whole.path, 13 to half.path))
        val dir = File(tmp, "Downloads/TMPlayer")
        val migration = DownloadMigration(
            settings,
            downloadsDir = { dir },
            files = files,
            availability = { if (it == 12) LocalFileAvailability.Complete else LocalFileAvailability.Partial },
            isOpen = { false },
        )

        val outcome = migration.run()!!

        assertEquals(1, outcome.moved)
        assertTrue(File(dir, "Film 12.mkv").isFile)
        assertFalse(whole.exists())
        assertEquals(File(dir, "Film 12.mkv").absolutePath, settings.downloadRecord(-1, 12)!!.localPath)
        assertNull(settings.downloadRecord(-1, 13)!!.localPath)
        assertEquals(listOf(12), files.deleted)
        assertTrue(settings.downloadsMigratedNow())
        assertNull(migration.run())
    }

    @Test
    fun `a download a player has open is left for the next run`() = runBlocking {
        val whole = File(tmp, "cache/20.mkv").apply { parentFile.mkdirs(); writeBytes(ByteArray(3)) }
        settings.noteDownload(item(20, 3), "c")
        val migration = DownloadMigration(
            settings,
            downloadsDir = { File(tmp, "dl") },
            files = FakeFiles(mapOf(20 to whole.path)),
            availability = { LocalFileAvailability.Complete },
            isOpen = { true },
        )
        assertEquals(1, migration.run()!!.left)
        assertTrue(whole.isFile)
        assertFalse(settings.downloadsMigratedNow())
    }

    // ---- room before play ---------------------------------------------------------------------

    @Test
    fun `room is made from the least recently played, and only as much as needed`() {
        val cached = listOf(CacheShelf.Held(1, 2 * gb, updatedAt = 30), CacheShelf.Held(2, 2 * gb, updatedAt = 10), CacheShelf.Held(3, 2 * gb, updatedAt = 20))
        assertEquals(RoomOnDisk.Decision.Proceed, RoomOnDisk.decide(1 * gb, 0, cached, emptySet(), 10 * gb, 100 * gb))
        assertEquals(RoomOnDisk.Decision.Evict(listOf(2)), RoomOnDisk.decide(3 * gb, 0, cached, emptySet(), 2 * gb, 100 * gb))
        // The spared one is skipped even though it is the oldest.
        assertEquals(RoomOnDisk.Decision.Evict(listOf(3)), RoomOnDisk.decide(3 * gb, 0, cached, setOf(2), 2 * gb, 100 * gb))
        val short = RoomOnDisk.decide(20 * gb, 0, cached, emptySet(), 1 * gb, 100 * gb)
        assertTrue(short is RoomOnDisk.Decision.NotEnoughSpace)
        // A disk that could not be read is not a reason to refuse.
        assertEquals(RoomOnDisk.Decision.Proceed, RoomOnDisk.decide(20 * gb, 0, cached, emptySet(), 0, 0))
    }

    // ---- settings -----------------------------------------------------------------------------

    @Test
    fun `the cache limit steps through its range and a pending move survives a restart`() {
        assertEquals(3 * gb, DesktopSettings.stepCacheLimit(2 * gb, 1))
        assertEquals(15 * gb, DesktopSettings.stepCacheLimit(10 * gb, 1))
        assertEquals(15 * gb, DesktopSettings.stepCacheLimit(12 * gb, 1))
        assertEquals(2 * gb, DesktopSettings.stepCacheLimit(2 * gb, -1))
        assertEquals(100 * gb, DesktopSettings.stepCacheLimit(100 * gb, 1))
        assertEquals(90 * gb, DesktopSettings.stepCacheLimit(100 * gb, -1))
        val file = File(tmp, "desktop.properties")
        DesktopPrefs(file).update { it.copy(storageRoot = "/mnt/big", storageRootPending = "pending") }
        val read = DesktopPrefs.read(file)
        assertEquals("/mnt/big", read.storageRoot)
        assertEquals("pending", read.storageRootPending)
    }

    // ---- picking in the grid ------------------------------------------------------------------

    @Test
    fun `ctrl picks one, shift takes in the range from the last one picked`() {
        val ids = (0 until 10).map { "c:$it" }
        val selection = GridSelection { ids.getOrNull(it) }
        selection.toggle(2)
        selection.extend(5)
        assertEquals(setOf("c:2", "c:3", "c:4", "c:5"), selection.ids)
        selection.toggle(3)
        assertFalse(selection.isSelected("c:3"))
        selection.extend(1)
        assertTrue(selection.isSelected("c:1") && selection.isSelected("c:2"))
        selection.clear()
        assertFalse(selection.active)
    }
}
