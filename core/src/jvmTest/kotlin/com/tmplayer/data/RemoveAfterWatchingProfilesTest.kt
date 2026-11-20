package com.tmplayer.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * "Remove after watching" acts on marks made here and now, never on a change of the watched list
 * (HX88): a sign out and a switch, a sync import, a restore from a backup and the app starting
 * delete nothing; a mark the viewer makes deletes its download; a mark of a profile no longer open
 * deletes nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoveAfterWatchingProfilesTest {

    private val a = AccountStamp(1L, null)
    private val b = AccountStamp(2L, "pbbbbb")

    private fun download(messageId: Long) = ResumeRecord(
        chatId = -100L, messageId = messageId, fileId = messageId.toInt(), title = "Ep $messageId",
        chatTitle = "Show", sizeBytes = 1_000, durationSec = 1_400, positionMs = 0, durationMs = 0,
        updatedAt = 0, localPath = "/dl/$messageId.mkv",
    )

    private fun record(id: Long) = WatchedRecord(
        chatId = -100L, messageId = id, fileId = id.toInt(), title = "Ep $id",
        chatTitle = "Show", sizeBytes = 1_000, durationSec = 1_400, watchedAt = id, manual = true,
    )

    private fun key(id: Long) = record(id).key

    private class Rig(var open: AccountStamp, downloads: List<ResumeRecord>) {
        val events = MutableSharedFlow<WatchedMark>(extraBufferCapacity = 16)
        val list = MutableStateFlow<Map<String, WatchedRecord>>(emptyMap())
        var downloads = downloads.toMutableList()
        val deleted = mutableListOf<Long>()
        val watcher = RemoveAfterWatching(
            enabled = MutableStateFlow(true),
            watched = list,
            downloads = { this.downloads.toList() },
            busy = { false },
            profile = { open },
            marks = events,
            delete = { record ->
                deleted += record.messageId
                this.downloads.remove(record)
                true
            },
        )
    }

    @Test
    fun `a sign out of A then the switch to B deletes none of B's downloads`() = runTest {
        val rig = Rig(a, listOf(download(1), download(2), download(3)))
        rig.list.value = mapOf(key(1) to record(1))
        val job = launch { rig.watcher.run() }
        runCurrent()
        // The sign out wipes A's list, the stamp moves, A's empty list is processed late, then B's.
        rig.list.value = emptyMap()
        rig.open = b
        rig.list.value = emptyMap()
        rig.list.value = mapOf(key(2) to record(2), key(3) to record(3))
        runCurrent()
        assertTrue("B's watched videos are not newly watched: ${rig.deleted}", rig.deleted.isEmpty())
        // B marks 1 watched here: that one goes, as the setting asks.
        rig.events.emit(WatchedMark(setOf(key(1)), b))
        runCurrent()
        assertEquals(listOf(1L), rig.deleted)
        job.cancel()
    }

    @Test
    fun `a mark of a profile no longer open deletes nothing`() = runTest {
        val rig = Rig(b, listOf(download(1)))
        val job = launch { rig.watcher.run() }
        runCurrent()
        rig.events.emit(WatchedMark(setOf(key(1)), a))
        runCurrent()
        assertTrue(rig.deleted.isEmpty())
        job.cancel()
    }

    @Test
    fun `the app starting with videos already watched deletes nothing`() = runTest {
        val rig = Rig(a, listOf(download(1), download(2)))
        rig.list.value = mapOf(key(1) to record(1), key(2) to record(2))
        val job = launch { rig.watcher.run() }
        runCurrent()
        assertTrue(rig.deleted.isEmpty())
        job.cancel()
    }

    /** A real watched store, and the first mark event it raises within [ms], or null. */
    private fun store(): WatchedStore = WatchedStore(openSafeDataStore(File(Files.createTempDirectory("marks").toFile(), WatchedStore.FILE_NAME)))

    private suspend fun firstMarkWithin(ms: Long, act: suspend () -> Unit): WatchedMark? {
        var got: WatchedMark? = null
        kotlinx.coroutines.coroutineScope {
            val listen = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { got = WatchedMarks.events.first() }
            act()
            withTimeoutOrNull(ms) { listen.join() }
            listen.cancel()
        }
        return got
    }

    @Test
    fun `a sync import marking 200 videos watched raises no mark, so no download goes`() = runBlocking {
        val watched = store()
        val mark = firstMarkWithin(500L) { watched.applySync(SyncApply(watchedIn = (1L..200L).map(::record))) }
        assertNull("another device's marks are not the viewer finishing a video here", mark)
        assertEquals(200, watched.watched.first().size)
    }

    @Test
    fun `a restore from a backup raises no mark`() = runBlocking {
        val watched = store()
        val mark = firstMarkWithin(500L) { watched.restoreEntries { mapOf("w_-100_7" to record(7).encode()) } }
        assertNull(mark)
    }

    @Test
    fun `the viewer's own mark raises one, for the open profile`() = runBlocking {
        val watched = store()
        val mark = firstMarkWithin(2_000L) { watched.markWatched(record(9)) }
        assertNotNull(mark)
        assertEquals(setOf(key(9)), mark!!.keys)
        assertEquals(Accounts.stamp, mark.owner)
    }

    @Test
    fun `replaying a video already watched, or leaving it past the end again, raises no mark and keeps its download`() = runBlocking {
        val watched = store()
        assertNotNull("the first completion is a mark", firstMarkWithin(2_000L) { watched.markWatched(record(11).copy(manual = false)) })
        assertNull("watched again: no mark", firstMarkWithin(500L) { watched.markWatched(record(11).copy(manual = false)) })
        assertNull("marked by hand after: no mark", firstMarkWithin(500L) { watched.markWatched(record(11)) })
    }

    @Test
    fun `a mark written for a profile no longer open raises nothing`() = runBlocking {
        val watched = store()
        val stale = AccountStamp(Long.MAX_VALUE, "pgone1")
        val mark = firstMarkWithin(500L) { kotlinx.coroutines.withContext(AccountOwner(stale)) { watched.markWatched(record(12)) } }
        assertNull(mark)
    }
}
