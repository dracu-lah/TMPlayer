package com.tmplayer.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * The pure books (the browser build's stores) over the apps' own DataStore files: what one writes,
 * the other reads, key for key, so a backup or a file moves between them.
 */
class BooksMatchStoresTest {

    private val dir = Files.createTempDirectory("tm-books").toFile()

    private fun record(messageId: Long, at: Long, manual: Boolean = false) = WatchedRecord(
        chatId = -100L, messageId = messageId, fileId = 7, title = "Ep $messageId", chatTitle = "Show",
        sizeBytes = 1_000, durationSec = 1_400, watchedAt = at, manual = manual,
    )

    @Test
    fun `the watched book and the watched store read each other`() = runBlocking {
        val file = WatchedStore.openDataStore(dir.resolve(WatchedStore.FILE_NAME))
        val store = WatchedStore(file)
        val book = WatchedBook(DataStoreKeyValueStore(file))
        store.markWatched(record(1, at = 10))
        book.markWatched(record(2, at = 20, manual = true))
        assertEquals(listOf(2L, 1L), store.history.first().map { it.messageId })
        assertEquals(listOf(2L, 1L), book.history.first().map { it.messageId })
        assertTrue(book.isWatched(-100L, 1L))
        assertEquals(store.watched.first(), book.watched.first())

        val later = WatchLaterRecord.of(record(3, at = 0).toMediaItem(), "Show", addedAt = 30)
        book.watchLater.add(later)
        assertTrue(store.watchLater.contains(-100L, 3L))
        store.markWatched(record(3, at = 40))
        assertFalse(book.watchLater.contains(-100L, 3L))

        book.markUnwatched(-100L, 1L)
        assertFalse(store.isWatched(-100L, 1L))
    }

    @Test
    fun `a stored minimum migrates in both stores, the old default reads as not set`() = runBlocking {
        val file = WatchedStore.openDataStore(dir.resolve("settings-sizes.preferences_pb"))
        val store = SettingsStore(file)
        val kv = DataStoreKeyValueStore(file)
        val book = SettingsBook(kv)
        val mb = SizeFilter.MB
        // The default until 2026-11 was never a choice, so a stored 400 MB is "not set".
        for ((stored, shown) in listOf(400 * mb to 0L, 0L to 0L, 50 * mb to 50 * mb, 700 * mb to 700 * mb, 1024 * mb to 1024 * mb)) {
            kv.edit { it["min_size_bytes"] = stored }
            assertEquals("store reads $stored", shown, store.minSizeBytes.first())
            assertEquals("book reads $stored", shown, book.minSizeBytes.first())
        }
        kv.edit { it["max_size_bytes"] = 2048 * mb }
        assertEquals(2048 * mb, store.maxSizeBytes.first())
        assertEquals(2048 * mb, book.maxSizeBytes.first())
        // Reset removes both, so every video is listed again.
        store.resetSizeLimits()
        assertEquals(0L, book.minSizeBytes.first())
        assertEquals(SizeFilter.CEILING, book.maxSizeBytes.first())
        // The clip switch writes 50 MB and nothing else; off removes the key.
        book.setHideSmallClips(true)
        assertEquals(SizeFilter.CLIP_MIN, store.minSizeBytes.first())
        store.setHideSmallClips(false)
        assertEquals(0L, book.minSizeBytes.first())
        // A custom floor survives the switch going on, in both stores; off removes it.
        kv.edit { it["min_size_bytes"] = 300 * mb }
        store.setHideSmallClips(true)
        assertEquals(300 * mb, book.minSizeBytes.first())
        book.setHideSmallClips(true)
        assertEquals(300 * mb, store.minSizeBytes.first())
        book.setHideSmallClips(false)
        assertEquals(0L, store.minSizeBytes.first())
    }

    @Test
    fun `the settings book and the settings store read each other`() = runBlocking {
        val file = WatchedStore.openDataStore(dir.resolve("settings.preferences_pb"))
        val store = SettingsStore(file)
        val book = SettingsBook(DataStoreKeyValueStore(file))

        store.toggleFavorite(42L)
        assertEquals(setOf(42L), book.favorites.first())
        book.toggleFavorite(7L)
        assertEquals(setOf(42L, 7L), store.favorites.first())

        book.setAccent(Accent.entries.last())
        assertEquals(Accent.entries.last(), store.accent.first())
        store.setThemeChoice(ThemeChoice.Dark)
        assertEquals(ThemeChoice.Dark, book.themeChoice.first())
        book.setPureBlack(true)
        assertTrue(store.pureBlack.first())

        assertEquals("every video by default", 0L, store.minSizeBytes.first())
        assertEquals(store.minSizeBytes.first(), book.minSizeBytes.first())
        store.setHideSmallClips(true)
        assertEquals(SizeFilter.CLIP_MIN, store.minSizeBytes.first())
        assertEquals(store.minSizeBytes.first(), book.minSizeBytes.first())
        book.setHideSmallClips(true)
        book.resetSizeLimits()
        store.resetSizeLimits()
        assertEquals(0L, store.minSizeBytes.first())
        assertEquals(store.minSizeBytes.first(), book.minSizeBytes.first())
        assertEquals(SizeFilter.CEILING, store.maxSizeBytes.first())
        book.setVideoSort(5L, VideoSort.entries.last())
        assertEquals(VideoSort.entries.last(), store.videoSort(5L).first())

        val item = record(9, at = 0).toMediaItem()
        val line = book.describe(item, "Show")
        book.saveResumePosition(-100L, 9L, positionMs = 600_000, durationMs = 1_400_000, description = line)
        assertEquals(600_000L, store.resumePosition(-100L, 9L))
        assertEquals(store.continueWatching.first(), book.continueWatching.first())
        assertEquals(store.watchProgress.first(), book.watchProgress.first())
        store.clearResumePosition(-100L, 9L)
        assertTrue(book.continueWatching.first().isEmpty())

        book.addRecentSearch("harbour")
        assertEquals(listOf("harbour"), store.recentSearches.first())

        // The added chats (HX27), with the three states the gate tells apart.
        assertEquals(AddedMode.Unanswered, book.addedChats.first().mode)
        assertEquals(AddedMode.Unanswered, store.addedChats.first().mode)
        store.setAddedChats(listOf(AddedChat(-100L, "Show"), AddedChat(42L, "Clips")))
        assertEquals(AddedMode.Picked, book.addedChats.first().mode)
        assertEquals(setOf(-100L, 42L), book.addedChats.first().ids)
        book.keepAllChats()
        assertEquals(AddedMode.All, store.addedChats.first().mode)
        assertEquals(null, store.addedChats.first().ids)
        // All chats on keeps the ticks beside the switch, in both stores (HX86), and off brings them back.
        val kept = listOf(AddedChat(-100L, "Show"), AddedChat(42L, "Clips"))
        store.setAddedChoice(AddedChoice(AddedMode.All, kept))
        assertEquals(AddedChoice(AddedMode.All, kept), store.addedChats.first())
        assertEquals(null, store.addedChats.first().ids)
        book.setAddedChoice(AddedChoice(AddedMode.Picked, kept))
        assertEquals(AddedChoice(AddedMode.Picked, kept), store.addedChats.first())
        book.setAddedChoice(AddedChoice(AddedMode.All, kept))
        assertEquals(AddedChoice(AddedMode.All, kept), book.addedChats.first())
        // An empty choice stays an answer in both: the key is not dropped with its last chat.
        book.setAddedChats(emptyList())
        assertEquals(AddedMode.Picked, store.addedChats.first().mode)
        assertEquals(emptySet<Long>(), store.addedChats.first().ids)
        store.addChat(7L, "Added one")
        assertEquals(setOf(7L), book.addedChats.first().ids)
        book.setAddedChats(AddedChats.without(book.addedChats.first().chats, 7L))
        assertEquals(emptySet<Long>(), store.addedChats.first().ids)

        book.setPin("2580")
        assertEquals(PinCheck.Right, store.checkPin("2580"))
        store.setPin("1470")
        assertEquals(PinCheck.Right, book.checkPin("1470"))
        assertTrue(book.checkPin("0000") is PinCheck.Wrong)
    }

    @Test
    fun `the bookmark book and the bookmark store read each other`() = runBlocking {
        val file = BookmarkStore.openDataStore(dir.resolve(BookmarkStore.FILE_NAME))
        val store = BookmarkStore(file)
        val book = BookmarkBook(DataStoreKeyValueStore(file))
        store.add(-100L, 1L, 60_000, "the answer", now = 10)
        book.add(-100L, 1L, 120_000, "the twist", now = 20)
        assertEquals(store.bookmarksNow(-100L, 1L), book.bookmarksNow(-100L, 1L))
        assertEquals(listOf("the answer", "the twist"), store.bookmarksNow(-100L, 1L).map { it.note })
        book.delete(-100L, 1L, store.bookmarksNow(-100L, 1L).first().id)
        assertEquals(1, store.bookmarksNow(-100L, 1L).size)
    }
}
