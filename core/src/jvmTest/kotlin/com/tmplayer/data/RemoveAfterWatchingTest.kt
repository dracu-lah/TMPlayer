package com.tmplayer.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoveAfterWatchingTest {

    private fun download(messageId: Long, path: String? = "/dl/$messageId.mkv") = ResumeRecord(
        chatId = -100L, messageId = messageId, fileId = messageId.toInt(), title = "Ep $messageId",
        chatTitle = "Show", sizeBytes = 1_000, durationSec = 1_400, positionMs = 0, durationMs = 0,
        updatedAt = 0, localPath = path,
    )

    private fun mark(messageId: Long) = WatchedRecord(
        chatId = -100L, messageId = messageId, fileId = messageId.toInt(), title = "Ep $messageId",
        chatTitle = "Show", sizeBytes = 1_000, durationSec = 1_400, watchedAt = messageId, manual = true,
    )

    private fun marks(vararg ids: Long) = ids.associate { mark(it).key to mark(it) }

    /** A running watcher over fake flows, recording what it deleted. */
    private class Rig(
        on: Boolean,
        initial: Map<String, WatchedRecord>,
        var downloads: List<ResumeRecord>,
        var deleteWorks: Boolean = true,
        var busy: Set<Long> = emptySet(),
    ) {
        val enabled = MutableStateFlow(on)
        val watched = MutableStateFlow(initial)
        val deleted = mutableListOf<Long>()
        val watcher = RemoveAfterWatching(
            enabled = enabled,
            watched = watched,
            downloads = { downloads },
            busy = { it.messageId in busy },
            delete = { record ->
                if (deleteWorks) {
                    deleted += record.messageId
                    downloads = downloads - record
                }
                deleteWorks
            },
        )
    }

    @Test
    fun `marking a downloaded video watched deletes the download`() = runTest {
        val rig = Rig(on = true, initial = emptyMap(), downloads = listOf(download(1), download(2)))
        val job = launch { rig.watcher.run() }
        runCurrent()
        rig.watched.value = marks(1)
        runCurrent()
        assertEquals(listOf(1L), rig.deleted)
        job.cancel()
    }

    @Test
    fun `a video only in the watch cache is never deleted`() = runTest {
        // Message 5 is cached from playback and has no download record: nothing may go.
        val rig = Rig(on = true, initial = emptyMap(), downloads = listOf(download(1)))
        val job = launch { rig.watcher.run() }
        runCurrent()
        rig.watched.value = marks(5)
        runCurrent()
        assertTrue(rig.deleted.isEmpty())
        job.cancel()
    }

    @Test
    fun `due picks downloads only, matched by chat and message`() {
        val other = download(1).copy(chatId = -200L)
        val due = RemoveAfterWatching.due(setOf(mark(1).key, mark(9).key), listOf(download(1), other, download(2)))
        assertEquals(listOf(download(1)), due)
    }

    @Test
    fun `nothing is deleted while the setting is off, and those marks never count later`() = runTest {
        val rig = Rig(on = false, initial = emptyMap(), downloads = listOf(download(1)))
        val job = launch { rig.watcher.run() }
        runCurrent()
        rig.watched.value = marks(1)
        runCurrent()
        rig.enabled.value = true
        runCurrent()
        assertTrue(rig.deleted.isEmpty())
        job.cancel()
    }

    @Test
    fun `videos already watched at start are left alone`() = runTest {
        val rig = Rig(on = true, initial = marks(1), downloads = listOf(download(1), download(2)))
        val job = launch { rig.watcher.run() }
        runCurrent()
        rig.watched.value = marks(1, 2)
        runCurrent()
        assertEquals(listOf(2L), rig.deleted)
        job.cancel()
    }

    @Test
    fun `a download still being fetched is kept`() = runTest {
        val rig = Rig(on = true, initial = emptyMap(), downloads = listOf(download(1)), busy = setOf(1L))
        val job = launch { rig.watcher.run() }
        runCurrent()
        rig.watched.value = marks(1)
        runCurrent()
        assertTrue(rig.deleted.isEmpty())
        job.cancel()
    }

    @Test
    fun `a file that would not go is tried again on the next change`() = runTest {
        val rig = Rig(on = true, initial = emptyMap(), downloads = listOf(download(1), download(2)), deleteWorks = false)
        val job = launch { rig.watcher.run() }
        runCurrent()
        rig.watched.value = marks(1)
        runCurrent()
        assertTrue(rig.deleted.isEmpty())
        rig.deleteWorks = true
        rig.watched.value = marks(1, 2)
        runCurrent()
        assertEquals(setOf(1L, 2L), rig.deleted.toSet())
        job.cancel()
    }

    @Test
    fun `free space reads in GB, MB, or not at all before a measurement`() {
        val mb = 1024L * 1024
        val gb = 1024L * mb
        assertEquals("12.3 GB free", DiskInfo.freeLabel(12 * gb + 300 * mb))
        assertEquals("1.0 GB free", DiskInfo.freeLabel(gb))
        assertEquals("230 GB free", DiskInfo.freeLabel(230 * gb + 700 * mb))
        assertEquals("850 MB free", DiskInfo.freeLabel(850 * mb))
        assertEquals("Less than 1 MB free", DiskInfo.freeLabel(4_096))
        assertNull(DiskInfo.freeLabel(0))
        assertNull(DiskInfo.freeLabel(DiskInfo.EMPTY.freeBytes))
    }
}
