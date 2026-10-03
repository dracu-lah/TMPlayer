package com.tmplayer.desktop

import com.tmplayer.data.MediaItem
import com.tmplayer.data.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DesktopWatchCacheTest {

    private val dir = Files.createTempDirectory("tm-cache").toFile()
    private val settings = SettingsStore(SettingsStore.openDataStore(dir.resolve(SettingsStore.FILE_NAME)))
    private val root = dir.resolve("tdlib-files").apply { mkdirs() }

    /** TDLib as a map of file id to bytes on disk; a deleted file is gone, a refused one stays. */
    private class FakeTd(val root: File) : TdFiles {
        val bytes = mutableMapOf<Int, Long>()
        val refuse = mutableSetOf<Int>()
        val deleted = mutableListOf<Int>()
        val unresolvable = mutableSetOf<Int>()
        override suspend fun currentFileId(chatId: Long, messageId: Long, storedFileId: Int) = storedFileId
        override suspend fun deleteFile(fileId: Int) {
            deleted += fileId
            if (fileId !in refuse) bytes.remove(fileId)
        }
        override suspend fun localDownloadedBytes(fileId: Int) = bytes[fileId] ?: 0L
        override suspend fun localPathAnyway(fileId: Int): String? =
            if (fileId in unresolvable) null else File(root, "videos/$fileId.mkv").absolutePath
    }

    private val td = FakeTd(root)
    private var busy = emptySet<Int>()
    private var playing = emptySet<Int>()
    private val cache = DesktopWatchCache(settings, root, td, busy = { busy }, playing = { playing }, now = { NOW })

    private fun item(id: Int) = MediaItem(
        chatId = 7, messageId = id.toLong(), fileId = id, title = "Episode $id", sizeBytes = 100,
        durationSec = 60, mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null, date = 0,
    )

    private fun cachedIds() = runBlocking { settings.cachedVideosNow().map { it.fileId }.toSet() }

    @Test
    fun `playing a second video gives up the first`() = runBlocking {
        td.bytes[1] = 100
        td.bytes[2] = 100
        cache.claim(item(1), "Chat")
        assertEquals(setOf(1), cachedIds())
        cache.claim(item(2), "Chat")
        assertEquals(listOf(1), td.deleted)
        assertEquals(setOf(2), cachedIds())
    }

    @Test
    fun `claiming the same video twice deletes nothing`() = runBlocking {
        td.bytes[1] = 100
        cache.claim(item(1), "Chat")
        cache.claim(item(1), "Chat")
        assertTrue(td.deleted.isEmpty())
    }

    @Test
    fun `a download is never recorded as cache nor evicted`() = runBlocking {
        td.bytes[1] = 100
        settings.noteDownload(item(1), "Chat")
        cache.claim(item(1), "Chat")
        assertTrue(cachedIds().isEmpty())
        cache.claim(item(2), "Chat")
        assertFalse(1 in td.deleted)
    }

    @Test
    fun `a video downloaded after it was cached keeps its file and loses the record`() = runBlocking {
        td.bytes[1] = 100
        cache.claim(item(1), "Chat")
        settings.noteDownload(item(1), "Chat")
        cache.claim(item(2), "Chat")
        assertFalse(1 in td.deleted)
        assertEquals(setOf(2), cachedIds())
    }

    @Test
    fun `the queue and an open player are spared`() = runBlocking {
        td.bytes[1] = 100
        td.bytes[3] = 100
        cache.claim(item(1), "Chat")
        cache.claim(item(3), "Chat")
        // 1 went; now 3 is cached. A download fetching 3 and a player on 4 must both survive.
        busy = setOf(3)
        cache.claim(item(4), "Chat")
        assertFalse(3 in td.deleted)
        playing = setOf(4)
        busy = emptySet()
        cache.evictAllBut(5)
        assertFalse(4 in td.deleted)
    }

    @Test
    fun `a refused delete keeps the record`() = runBlocking {
        td.bytes[1] = 100
        td.refuse += 1
        cache.claim(item(1), "Chat")
        cache.claim(item(2), "Chat")
        assertTrue(1 in cachedIds())
    }

    @Test
    fun `the sweep takes old strays only`() = runBlocking {
        val videos = root.resolve("videos").apply { mkdirs() }
        val known = videos.resolve("1.mkv").apply { writeText("known") }
        val oldStray = videos.resolve("old.mkv").apply { writeText("stray"); setLastModified(NOW - 60 * 60_000L) }
        val freshStray = videos.resolve("fresh.mkv").apply { writeText("fresh"); setLastModified(NOW - 60_000L) }
        val photo = root.resolve("photos").apply { mkdirs() }.resolve("p.jpg").apply { writeText("pic"); setLastModified(0) }
        known.setLastModified(0)
        td.bytes[1] = 5
        settings.noteDownload(item(1), "Chat")

        val freed = cache.sweep()

        assertEquals(5L, freed)
        assertTrue(known.exists())
        assertFalse(oldStray.exists())
        assertTrue(freshStray.exists())
        assertTrue(photo.exists())
    }

    @Test
    fun `the sweep waits when a known file cannot be resolved`() = runBlocking {
        val videos = root.resolve("videos").apply { mkdirs() }
        val stray = videos.resolve("old.mkv").apply { writeText("stray"); setLastModified(0) }
        settings.noteDownload(item(1), "Chat")
        td.unresolvable += 1
        assertEquals(0L, cache.sweep())
        assertTrue(stray.exists())
    }

    @Test
    fun `clear all keeps downloads`() = runBlocking {
        td.bytes[1] = 100
        td.bytes[2] = 50
        settings.noteDownload(item(1), "Chat")
        settings.rememberCachedVideo(item(2), "Chat")
        val freed = cache.clearAll()
        assertEquals(50L, freed)
        assertEquals(listOf(2), td.deleted)
        assertTrue(settings.cachedVideosNow().isEmpty())
        assertEquals(1, settings.downloadHistory.first().size)
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
