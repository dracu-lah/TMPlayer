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
        OfflineDownloads.restore(settings, database = "upgraded1234", beforeMarkers = true)
        assertEquals("a single profile user keeps the paused download", setOf(41), OfflineDownloads.active.value.keys)
        assertEquals("upgraded1234", settings.downloadQueueDatabaseNow())
    }

    @Test
    fun `an unmarked queue is taken only by a database from before markers, never a renewed or remade one`() {
        // TDLib's files there, no marker: a database from before markers.
        File(db, "td.binlog").writeText("x")
        val first = DatabaseMarker.ensure(db)!!
        assertTrue(DatabaseMarker.fromBeforeMarkers(db, first))
        assertTrue(DatabaseMarker.matches(null, first, beforeMarkers = true))
        // A sign out or an ended session: a new database in the same folder.
        val renewed = DatabaseMarker.renew(db)!!
        assertTrue("renewed: an unmarked queue is another database's", !DatabaseMarker.fromBeforeMarkers(db, renewed))
        // Sign in again: the folder wiped and made again, marked as it is made.
        AccountDirs.wipe(TempLayout(dir))
        AccountDirs.make(TempLayout(dir))
        File(db, "td.binlog").writeText("x")
        val remade = DatabaseMarker.ensure(db)!!
        assertTrue("remade: never from before markers", !DatabaseMarker.fromBeforeMarkers(db, remade))
    }

    @Test
    fun `a write whose marker cannot be read keeps the tag there was`() = runBlocking {
        settings.saveDownloadQueue(listOf(request), database = "taggedqueue1")
        settings.saveDownloadQueue(listOf(request, request.copy(fileId = 42)), database = null)
        assertEquals("never downgraded to a queue from before markers", "taggedqueue1", settings.downloadQueueDatabaseNow())
    }

    @Test
    fun `the marker read is the one on the disk, the same from every thread`() {
        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val threads = (1..8).map { Thread { DatabaseMarker.ensure(File(dir, "racing").apply { mkdirs() })?.let(seen::add) } }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        assertEquals("one marker for one database", 1, seen.size)
        assertEquals(seen.single(), File(File(dir, "racing"), DatabaseMarker.FILE_NAME).readText().trim())
    }

    @Test
    fun `stored markers match only the same database, and none on a database from before markers`() {
        assertTrue(DatabaseMarker.matches("a1b2c3d4", "a1b2c3d4", beforeMarkers = false))
        assertTrue(!DatabaseMarker.matches("a1b2c3d4", "z9y8x7w6", beforeMarkers = true))
        assertTrue(DatabaseMarker.matches(null, "z9y8x7w6", beforeMarkers = true))
        assertTrue("unmarked on a database made with markers", !DatabaseMarker.matches(null, "z9y8x7w6", beforeMarkers = false))
    }

    /** The platform's folders under [root], as TempPaths lays them out. */
    private class TempLayout(val root: File) : com.tmplayer.platform.Paths {
        override val databaseDir: File get() = File(root, "tdlib")
        override val filesDir: File get() = File(root, "cache")
        override val downloadsDir: File get() = File(root, "downloads")
        override fun disk(): DiskInfo = DiskInfo(1L shl 30, 2L shl 30)
    }
}
