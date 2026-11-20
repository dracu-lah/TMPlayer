package com.tmplayer.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * HX88's cache races: work of one profile that finishes after a switch never lands in the next
 * profile's file or memory. Each case starts a save or a fetch, switches, then lets it finish.
 */
class AccountCacheRaceTest {

    private val dir: File = Files.createTempDirectory("account-race").toFile()

    /** Opens profile [folder] process wide, as a switch does. */
    private fun switchTo(folder: String?) {
        val book = testBook(File(dir, "book-${folder ?: "main"}").apply { mkdirs() })
        if (folder != null) book.update { it.copy(profiles = it.profiles + Profile(folder, folder), activeId = folder) }
        Accounts.resetForTest(book)
    }

    @After
    fun back() {
        Accounts.resetForTest(null)
        HomeCache.file = null
    }

    private val item = MediaItem(
        chatId = 9, messageId = 77, fileId = 5, title = "Clip", sizeBytes = 1_000, durationSec = 60,
        mimeType = "video/mp4", thumbnailFileId = 3, miniThumbnail = null, date = 10, fileName = "a.mp4",
    )

    @Test
    fun `a Home snapshot of the last profile is never written as the next one's`() {
        switchTo(null)
        val base = File(dir, "home-cache.json")
        HomeCache.file = base
        val mine = HomeCache.Snapshot(mapOf(9L to listOf(item)), null, emptySet(), emptyMap(), 1L)
        HomeCache.save(mine)
        val before = base.readText()
        // Gathered for the adopted profile, finished after the switch to p2.
        val late = HomeCache.Snapshot(mapOf(10L to listOf(item.copy(chatId = 10))), null, emptySet(), emptyMap(), 2L, stamp = Accounts.stamp)
        switchTo("p2")
        HomeCache.save(late)
        assertFalse("the next profile's file is untouched", File(dir, "home-cache-p2.json").exists())
        assertEquals("the last profile's holds its own, never the late one", before, base.readText())
        assertNull("nor the next profile's memory", HomeCache.load())
        // The next profile's own Home is written to its own file.
        HomeCache.save(HomeCache.Snapshot(mapOf(11L to listOf(item.copy(chatId = 11))), null, emptySet(), emptyMap(), 3L))
        assertTrue(File(dir, "home-cache-p2.json").isFile)
        assertEquals(before, base.readText())
        switchTo(null)
        assertEquals(setOf(9L), HomeCache.load()?.loaded?.keys)
    }

    @Test
    fun `chat listings gathered before a switch are not written into the next profile's file`() {
        switchTo(null)
        val disk = ChatGridDisk(File(dir, "chat-grids.json"))
        val grid = ChatGrid(items = listOf(item), cursors = MediaCursors(), endReached = true)
        disk.save(listOf(SavedGrid(9, 0, grid, 1)), Accounts.stamp)
        val before = File(dir, "chat-grids.json").readText()
        val gathered = Accounts.stamp
        switchTo("p3")
        disk.save(listOf(SavedGrid(10, 0, grid, 2)), gathered)
        assertFalse(File(dir, "chat-grids-p3.json").exists())
        assertEquals(before, File(dir, "chat-grids.json").readText())
        assertTrue("the next profile reads nothing of the last", disk.load().isEmpty())
    }

    @Test
    fun `an answer that lands after the cache was cleared for a switch is not kept`() = runBlocking {
        val cache = QueryCache(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val key = QueryKey.ChatInfo(9)
        val release = CompletableDeferred<Unit>()
        val asking = async(Dispatchers.Default) { cache.fetch(key) { release.await(); "the last profile's" } }
        delay(50)
        cache.clear()
        release.complete(Unit)
        assertEquals("its own caller still gets it", "the last profile's", asking.await())
        assertNull(cache.data<String>(key))
        // An empty table before and after must not let it through either.
        val epoch = cache.epoch()
        cache.clear()
        assertFalse(cache.putIf(epoch, key, "late"))
        assertNull(cache.data<String>(key))
    }

    @Test
    fun `work of a profile no longer open writes nothing to the account stores`() = runBlocking {
        val folder = MutableStateFlow<String?>(null)
        var stamp = AccountStamp(1, null)
        val raw = openSafeDataStore(File(dir, SettingsStore.FILE_NAME))
        val settings = SettingsStore(AccountScopedDataStore(raw, folder, stamp = { stamp }))
        val owner = AccountOwner(stamp)
        withContext(owner) { settings.toggleFavorite(1L) }
        assertEquals(setOf(1L), settings.favorites.first())
        // The switch: the old profile's coroutine carries on.
        stamp = AccountStamp(2, "p4")
        folder.value = "p4"
        withContext(owner) { settings.toggleFavorite(2L) }
        assertTrue("not the next profile's", settings.favorites.first().isEmpty())
        folder.value = null
        assertEquals("nor added to the last one's after it closed", setOf(1L), settings.favorites.first())
    }

    @Test
    fun `the profile scope is cancelled on a switch and carries its owner`() = runBlocking {
        switchTo(null)
        val scope = Accounts.profileScope
        val stamp = Accounts.stamp
        val owner = async(scope.coroutineContext) { kotlin.coroutines.coroutineContext[AccountOwner]?.stamp }.await()
        assertEquals(stamp, owner)
        switchTo("p5")
        assertFalse(scope.coroutineContext[kotlinx.coroutines.Job]!!.isActive)
        assertTrue(Accounts.profileScope.coroutineContext[kotlinx.coroutines.Job]!!.isActive)
    }

    @Test
    fun `downloads, counts, gone videos and subtitle answers of the last profile do not cross a switch`() = runBlocking {
        switchTo(null)
        val request = DownloadRequest(7, "Episode", 1000, 1, 2, "Chat", 60, "video/mp4", "e.mp4")
        OfflineDownloads.note(OfflineDownloads.Progress(request, 400, 1000, OfflineDownloads.Stage.Paused))
        assertTrue(OfflineDownloads.isDownloading(7))
        GoneVideos.report(1, 2, GoneReason.Deleted)
        val calls = object : ChatCountCalls {
            override suspend fun videoCount(chatId: Long, localOnly: Boolean): CountAnswer = CountAnswer.Counted(12)
        }
        ChatVideoCount.of(calls, 1L) { 1_000L }
        assertEquals(ChatCount.Known(12), ChatVideoCount.cached(1L, 1_000L))
        switchTo("p6")
        assertFalse("file ids are the last database's", OfflineDownloads.isDownloading(7))
        assertNull(GoneVideos.reasonFor(1, 2))
        assertNull(ChatVideoCount.cached(1L, 1_000L))
    }
}
