package com.tmplayer.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        OfflineDownloads.resetForTest()
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
    fun `a row already in memory wins over the stored one of the same id`() = runBlocking {
        settings.saveDownloadQueue(listOf(request), database = "samedatabase")
        val live = OfflineDownloads.Progress(request, 500, request.sizeBytes, OfflineDownloads.Stage.Running)
        OfflineDownloads.note(live)
        OfflineDownloads.restore(settings, database = "samedatabase")
        assertEquals("a download asked before the restore ran keeps its place", live, OfflineDownloads.active.value[41])
    }

    @Test
    fun `two writes of the queue never cross, so the newer rows are the last written`() = runBlocking {
        OfflineDownloads.restore(settings, database = "samedatabase")
        OfflineDownloads.note(OfflineDownloads.Progress(request, 0, request.sizeBytes, OfflineDownloads.Stage.Queued))
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        // The first write has taken its snapshot (one row) and is held before it writes.
        val first = launch(Dispatchers.Default) {
            OfflineDownloads.persistQueue(settings) {
                entered.complete(Unit)
                gate.await()
            }
        }
        entered.await()
        // A newer row, and its write: it must wait behind the first, not overtake it.
        val newer = request.copy(fileId = 42, messageId = 8L)
        OfflineDownloads.note(OfflineDownloads.Progress(newer, 0, newer.sizeBytes, OfflineDownloads.Stage.Queued))
        val second = launch(Dispatchers.Default) { OfflineDownloads.persistQueue(settings) }
        delay(300)
        gate.complete(Unit)
        first.join()
        second.join()
        assertEquals(setOf(41, 42), settings.downloadQueueNow().map { it.fileId }.toSet())
    }

    @Test
    fun `restore runs once for each set of rows, so a recreated screen cannot bring a cancelled download back`() = runBlocking {
        settings.saveDownloadQueue(listOf(request), database = "samedatabase")
        OfflineDownloads.restore(settings, database = "samedatabase")
        assertEquals(setOf(41), OfflineDownloads.active.value.keys)
        // The viewer cancels: the row goes at once, its write is still to come.
        OfflineDownloads.forget(41)
        OfflineDownloads.restore(settings, database = "samedatabase")
        assertTrue("the stored queue still has it, the second restore must not read it", OfflineDownloads.active.value.isEmpty())
        // A new set of rows (a switch or a new database) reads the store again.
        OfflineDownloads.newDatabase()
        OfflineDownloads.restore(settings, database = "samedatabase")
        assertEquals(setOf(41), OfflineDownloads.active.value.keys)
    }

    @Test
    fun `two restores in flight merge once, so the second cannot bring back what was cancelled after the first`() = runBlocking {
        settings.saveDownloadQueue(listOf(request), database = "samedatabase")
        // The first restore has read the stored queue and waits before it merges; a second one runs
        // whole in that gap (the screen composing again), and the viewer cancels the row after it.
        OfflineDownloads.afterStoredRead = {
            OfflineDownloads.afterStoredRead = null
            OfflineDownloads.restore(settings, database = "samedatabase")
            assertEquals(setOf(41), OfflineDownloads.active.value.keys)
            OfflineDownloads.forget(41)
        }
        try {
            OfflineDownloads.restore(settings, database = "samedatabase")
        } finally {
            OfflineDownloads.afterStoredRead = null
        }
        assertTrue("the first restore's late merge brought a cancelled download back", OfflineDownloads.active.value.isEmpty())
    }

    @Test
    fun `a restore whose profile was switched away before its write never writes the next profile's rows under its marker`() = runBlocking {
        val bookB = testBook(File(dir, "book-b").apply { mkdirs() })
        bookB.update { it.copy(profiles = it.profiles + Profile("b", "b"), activeId = "b") }
        val other = request.copy(fileId = 42, messageId = 8L)
        // Profile A's restore has merged and not written yet; the viewer switches to B, whose own
        // restore merges and writes first. A's write then finds B's rows under B's generation.
        OfflineDownloads.afterMerge = {
            OfflineDownloads.afterMerge = null
            OfflineDownloads.dropRowsQuietly()
            Accounts.resetForTest(bookB)
            OfflineDownloads.note(OfflineDownloads.Progress(other, 0, other.sizeBytes, OfflineDownloads.Stage.Queued))
            OfflineDownloads.restore(settings, database = "bdatabase")
        }
        try {
            settings.saveDownloadQueue(listOf(request), database = "adatabase")
            OfflineDownloads.restore(settings, database = "adatabase")
            // B is the open profile (the queue is stored for each profile): its queue is B's rows
            // under B's marker, not written over by A's late write.
            assertEquals("B's queue was tagged with A's database", "bdatabase", settings.downloadQueueDatabaseNow())
            assertEquals(setOf(42), settings.downloadQueueNow().map { it.fileId }.toSet())
        } finally {
            OfflineDownloads.afterMerge = null
            Accounts.resetForTest(null)
        }
    }

    @Test
    fun `restore writes the merged queue under the persist lock, never alongside another write`() = runBlocking {
        settings.saveDownloadQueue(listOf(request), database = "samedatabase")
        OfflineDownloads.restore(settings, database = "samedatabase")
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        // A write holds the lock after its snapshot (rows of this set of rows).
        val held = launch(Dispatchers.Default) {
            OfflineDownloads.persistQueue(settings, database = "samedatabase") {
                entered.complete(Unit)
                gate.await()
            }
        }
        entered.await()
        // A new set of rows: a row asked at once, and the restore for it, whose write must wait for the lock.
        OfflineDownloads.newDatabase()
        val extra = request.copy(fileId = 42, messageId = 8L)
        OfflineDownloads.note(OfflineDownloads.Progress(extra, 0, extra.sizeBytes, OfflineDownloads.Stage.Queued))
        val restored = launch(Dispatchers.Default) { OfflineDownloads.restore(settings, database = "samedatabase") }
        delay(400)
        assertEquals("restore wrote while another write held the lock", setOf(41), settings.downloadQueueNow().map { it.fileId }.toSet())
        gate.complete(Unit)
        held.join()
        restored.join()
        assertEquals(setOf(41, 42), settings.downloadQueueNow().map { it.fileId }.toSet())
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
