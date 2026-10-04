package com.tmplayer.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class WatchedStoreTest {

    private val dir = Files.createTempDirectory("tm-watched").toFile()
    private val store = WatchedStore(WatchedStore.openDataStore(dir.resolve(WatchedStore.FILE_NAME)))

    private fun record(messageId: Long, at: Long, manual: Boolean = false, title: String = "Ep $messageId") =
        WatchedRecord(
            chatId = -100L, messageId = messageId, fileId = 7, title = title, chatTitle = "Show",
            sizeBytes = 1_000, durationSec = 1_400, watchedAt = at, manual = manual,
        )

    @Test
    fun `marked videos come back newest first and keyed for the grid`() = runBlocking {
        store.markWatched(record(1, at = 10))
        store.markWatched(record(2, at = 20, manual = true))
        assertEquals(listOf(2L, 1L), store.history.first().map { it.messageId })
        assertTrue(store.watched.first().containsKey(SettingsStore.progressKey(-100L, 2L)))
        assertTrue(store.isWatched(-100L, 1L))
    }

    @Test
    fun `unmarking removes only that video`() = runBlocking {
        store.markWatched(record(1, at = 10))
        store.markWatched(record(2, at = 20))
        store.markUnwatched(-100L, 1L)
        assertFalse(store.isWatched(-100L, 1L))
        assertEquals(listOf(2L), store.history.first().map { it.messageId })
    }

    @Test
    fun `finishing a video the viewer already marked keeps it marked by hand`() = runBlocking {
        store.markWatched(record(1, at = 10, manual = true))
        store.markWatched(record(1, at = 30, manual = false))
        val only = store.history.first().single()
        assertTrue(only.manual)
        assertEquals(30L, only.watchedAt)
    }

    @Test
    fun `titles full of separators survive the round trip`() = runBlocking {
        val odd = "Show.S01E09.1080p [x265] - \u001F part"
        store.markWatched(record(9, at = 10, title = odd))
        assertEquals(odd.replace('\u001F', ' '), store.history.first().single().title)
    }

    @Test
    fun `clear empties the list`() = runBlocking {
        store.markWatched(record(1, at = 10))
        store.clear()
        assertTrue(store.history.first().isEmpty())
    }
}
