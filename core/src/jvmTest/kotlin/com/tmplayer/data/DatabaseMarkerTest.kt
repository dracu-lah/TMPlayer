package com.tmplayer.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * File ids never cross a TDLib database (HX88): the database marker changes whenever the database
 * does (Sign in again on a broken profile wipes its folder, a sign out and an ended session renew
 * it), a stored download queue of another database is dropped, never offered, and a database from
 * before markers keeps its queue, once.
 */
class DatabaseMarkerTest {

    private val dir: File = Files.createTempDirectory("db-marker").toFile()
    private val db = File(dir, "tdlib").apply { mkdirs() }
    private val settings = SettingsStore(SettingsStore.openDataStore(File(dir, SettingsStore.FILE_NAME)))
    private val request = DownloadRequest(
        fileId = 41, title = "Clip", sizeBytes = 1_000, chatId = -100L, messageId = 7L,
        chatTitle = "Chat", durationSec = 60, mimeType = "video/mp4", fileName = "clip.mp4",
    )

    @After
    fun tearDown() {
        OfflineDownloads.forget(request.fileId)
    }

    @Test
    fun `a database keeps its marker, and a new one has a new marker`() {
        val first = DatabaseMarker.ensure(db)
        assertEquals("the same database, the same marker", first, DatabaseMarker.ensure(db))
        // Sign in again on a broken profile: its folders are wiped and made again.
        AccountDirs.wipe(TempLayout(dir))
        AccountDirs.make(TempLayout(dir))
        assertNotEquals("a wiped and remade folder is a new database", first, DatabaseMarker.ensure(db))
        // A sign out, or a session Telegram ended: TDLib keeps the folder, the marker is renewed.
        val before = DatabaseMarker.ensure(db)
        assertNotEquals(before, DatabaseMarker.renew(db))
        assertNull("no database, no marker", DatabaseMarker.ensure(File(dir, "missing")))
    }

    @Test
    fun `a stored queue of another database is dropped, never offered`() = runBlocking {
        settings.saveDownloadQueue(listOf(request), database = "olddatabase1")
        OfflineDownloads.restore(settings, database = "newdatabase2")
        assertTrue("nothing offered from another database", OfflineDownloads.active.value.isEmpty())
        assertTrue("and the stale queue is gone", settings.downloadQueueNow().isEmpty())
    }

    @Test
    fun `the same database's queue comes back`() = runBlocking {
        settings.saveDownloadQueue(listOf(request), database = "samedatabase")
        OfflineDownloads.restore(settings, database = "samedatabase")
        assertEquals(setOf(41), OfflineDownloads.active.value.keys)
    }

    @Test
    fun `a queue from before markers is kept once and then carries the marker`() = runBlocking {
        settings.saveDownloadQueue(listOf(request), database = null)
        assertNull(settings.downloadQueueDatabaseNow())
        OfflineDownloads.restore(settings, database = "upgraded1234")
        assertEquals("a single profile user keeps the paused download", setOf(41), OfflineDownloads.active.value.keys)
        assertEquals("upgraded1234", settings.downloadQueueDatabaseNow())
    }

    @Test
    fun `stored markers match only the same database, and one from before markers`() {
        assertTrue(DatabaseMarker.matches("a1b2c3d4", "a1b2c3d4"))
        assertTrue(!DatabaseMarker.matches("a1b2c3d4", "z9y8x7w6"))
        assertTrue(DatabaseMarker.matches(null, "z9y8x7w6"))
    }

    /** The platform's folders under [root], as TempPaths lays them out. */
    private class TempLayout(val root: File) : com.tmplayer.platform.Paths {
        override val databaseDir: File get() = File(root, "tdlib")
        override val filesDir: File get() = File(root, "cache")
        override val downloadsDir: File get() = File(root, "downloads")
        override fun disk(): DiskInfo = DiskInfo(1L shl 30, 2L shl 30)
    }
}
