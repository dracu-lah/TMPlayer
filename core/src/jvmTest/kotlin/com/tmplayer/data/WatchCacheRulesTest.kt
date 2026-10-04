package com.tmplayer.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The watch cache rules both platforms run: the one video rule the phone and TV keep, the byte cap
 * the desktop uses, and the stray sweep under both.
 */
class WatchCacheRulesTest {

    private val dir = Files.createTempDirectory("tm-cache").toFile()
    private val settings = SettingsStore(SettingsStore.openDataStore(dir.resolve(SettingsStore.FILE_NAME)))
    private val root = dir.resolve("cache").apply { mkdirs() }

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
    private var clock = NOW
    private var rule: CacheRule = CacheRule.OneVideo

    private fun rules(sparePlaying: Boolean = true) = WatchCacheRules(
        settings, { root }, td, busy = { busy }, playing = { playing }, now = { clock }, rule = { rule },
        sparePlaying = sparePlaying,
    )

    private val cache = rules()

    private fun item(id: Int, chatId: Long = 7) = MediaItem(
        chatId = chatId, messageId = id.toLong(), fileId = id, title = "Episode $id", sizeBytes = 100,
        durationSec = 60, mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null, date = 0,
    )

    private fun cachedIds() = runBlocking { settings.cachedVideosNow().map { it.fileId }.toSet() }

    /** Claims with a clock that moves, so "least recently played" has an order to go by. */
    private suspend fun play(id: Int, bytes: Long = 100, rules: WatchCacheRules = cache) {
        td.bytes[id] = bytes
        rules.claim(item(id), "Chat")
        Thread.sleep(2)
    }

    // ---- one video ---------------------------------------------------------------------------

    @Test
    fun `playing a second video gives up the first`() = runBlocking {
        play(1)
        assertEquals(setOf(1), cachedIds())
        play(2)
        assertEquals(listOf(1), td.deleted)
        assertEquals(setOf(2), cachedIds())
    }

    @Test
    fun `claiming the same video twice deletes nothing`() = runBlocking {
        play(1)
        play(1)
        assertTrue(td.deleted.isEmpty())
    }

    @Test
    fun `a download is never recorded as cache nor evicted`() = runBlocking {
        td.bytes[1] = 100
        settings.noteDownload(item(1), "Chat")
        cache.claim(item(1), "Chat")
        assertTrue(cachedIds().isEmpty())
        play(2)
        assertFalse(1 in td.deleted)
    }

    @Test
    fun `a video downloaded after it was cached keeps its file and loses the record`() = runBlocking {
        play(1)
        settings.noteDownload(item(1), "Chat")
        play(2)
        assertFalse(1 in td.deleted)
        assertEquals(setOf(2), cachedIds())
    }

    @Test
    fun `the same file downloaded from another chat is not deleted through this one`() = runBlocking {
        play(1)
        // One Telegram file, forwarded: chat 9 holds the download, chat 7 the cached copy.
        settings.noteDownload(item(1, chatId = 9), "Other chat")
        play(2)
        assertFalse(1 in td.deleted)
    }

    @Test
    fun `the queue and an open player are spared`() = runBlocking {
        play(1)
        play(3)
        // 1 went; now 3 is cached. A download fetching 3 and a player on 4 must both survive.
        busy = setOf(3)
        play(4)
        assertFalse(3 in td.deleted)
        playing = setOf(4)
        busy = emptySet()
        cache.evictAllBut(5)
        assertFalse(4 in td.deleted)
    }

    @Test
    fun `without sparing players, as on Android, only the claim keeps a video`() = runBlocking {
        val android = rules(sparePlaying = false)
        play(1, rules = android)
        playing = setOf(1)
        play(2, rules = android)
        assertTrue(1 in td.deleted)
        assertEquals(setOf(2), cachedIds())
    }

    @Test
    fun `a refused delete keeps the record`() = runBlocking {
        td.refuse += 1
        play(1)
        play(2)
        assertTrue(1 in cachedIds())
    }

    // ---- under a cap -------------------------------------------------------------------------

    @Test
    fun `under a cap videos stay until the cap is passed, then the oldest played goes`() = runBlocking {
        rule = CacheRule.UnderCap(capBytes = 250)
        play(1)
        play(2)
        assertTrue(td.deleted.isEmpty())
        assertEquals(setOf(1, 2), cachedIds())
        play(3)
        assertEquals(listOf(1), td.deleted)
        assertEquals(setOf(2, 3), cachedIds())
    }

    @Test
    fun `playing an old video again makes it the newest`() = runBlocking {
        rule = CacheRule.UnderCap(capBytes = 250)
        play(1)
        play(2)
        play(1)
        play(3)
        assertEquals(listOf(2), td.deleted)
        assertEquals(setOf(1, 3), cachedIds())
    }

    @Test
    fun `the cap counts what is on disk, not the full size`() = runBlocking {
        rule = CacheRule.UnderCap(capBytes = 1000)
        play(1, bytes = 400)
        play(2, bytes = 50)
        play(3, bytes = 600)
        assertEquals(listOf(1), td.deleted)
    }

    @Test
    fun `a video bigger than the cap empties the rest but is itself kept`() = runBlocking {
        rule = CacheRule.UnderCap(capBytes = 100)
        play(1, bytes = 60)
        play(2, bytes = 60)
        play(3, bytes = 500)
        assertEquals(setOf(1, 2), td.deleted.toSet())
        assertEquals(setOf(3), cachedIds())
    }

    @Test
    fun `under a cap the queue, a player and downloads are spared`() = runBlocking {
        rule = CacheRule.UnderCap(capBytes = 250)
        play(1)
        play(2)
        busy = setOf(1)
        playing = setOf(2)
        play(3)
        assertTrue(td.deleted.isEmpty())
        busy = emptySet()
        playing = emptySet()
        settings.noteDownload(item(2), "Chat")
        play(4)
        assertFalse(2 in td.deleted)
        assertTrue(1 in td.deleted)
    }

    @Test
    fun `the sweep brings the cache back under a lowered cap`() = runBlocking {
        rule = CacheRule.UnderCap(capBytes = 1000)
        play(1)
        play(2)
        play(3)
        rule = CacheRule.UnderCap(capBytes = 150)
        cache.sweep()
        assertEquals(listOf(1, 2), td.deleted)
        assertEquals(setOf(3), cachedIds())
    }

    @Test
    fun `least recently played is chosen oldest first and never from the spared`() {
        val held = listOf(
            CacheShelf.Held(1, 100, updatedAt = 10),
            CacheShelf.Held(2, 100, updatedAt = 30),
            CacheShelf.Held(3, 100, updatedAt = 20),
            CacheShelf.Held(4, 100, updatedAt = 1),
        )
        assertEquals(emptyList<Int>(), WatchCacheRules.lruVictims(held, 400, emptySet()))
        assertEquals(listOf(4, 1), WatchCacheRules.lruVictims(held, 200, emptySet()))
        assertEquals(listOf(1, 3), WatchCacheRules.lruVictims(held, 200, setOf(4)))
        assertEquals(listOf(1, 3, 2), WatchCacheRules.lruVictims(held, 0, setOf(4)))
    }

    // ---- strays ------------------------------------------------------------------------------

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
    fun `a known file reached through a link to the cache is not a stray`() = runBlocking {
        // TDLib may still spell a file with the cache's old name, now a link to the new one.
        val legacy = dir.resolve("tdlib-files")
        Files.createSymbolicLink(legacy.toPath(), root.toPath())
        val file = root.resolve("videos").apply { mkdirs() }.resolve("1.mkv").apply { writeText("known") }
        file.setLastModified(0)
        val found = cache.strays(setOf(legacy.resolve("videos/1.mkv").absolutePath))
        assertTrue(found.isEmpty())
    }

    @Test
    fun `strays are named from their file`() {
        val videos = root.resolve("videos").apply { mkdirs() }
        videos.resolve("Harbour_Notes.mkv").writeText("x")
        videos.resolve("75").writeText("xx")
        val found = WatchCacheRules.straysIn(root, emptySet())
        assertEquals(setOf("Harbour Notes", "Cached video"), found.map { it.title }.toSet())
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
