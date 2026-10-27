package com.tmplayer.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Remove from history, Clear history and their Undo ([HistoryEdits]). */
class HistoryEditsTest {

    private class Device {
        val dir = Files.createTempDirectory("tm-history").toFile()
        val settings = SettingsStore(SettingsStore.openDataStore(dir.resolve(SettingsStore.FILE_NAME)))
        val watched = WatchedStore(WatchedStore.openDataStore(dir.resolve(WatchedStore.FILE_NAME)))
    }

    private fun line(title: String, updatedAt: Long) = ResumeRecord.encode(
        fileId = 11, title = title, chatTitle = "Films", sizeBytes = 5_000, durationSec = 6_000, updatedAt = updatedAt,
        state = ResumeState(audioTrack = 1, audioLanguage = "eng", speed = 1.25f),
    )

    private fun mark(messageId: Long, at: Long) = WatchedRecord(
        chatId = -100L, messageId = messageId, fileId = 7, title = "Ep $messageId", chatTitle = "Show",
        sizeBytes = 1_000, durationSec = 1_400, watchedAt = at, manual = true,
    )

    private fun later(messageId: Long, at: Long) = WatchLaterRecord(
        chatId = -100L, messageId = messageId, fileId = 9, title = "Film $messageId", fileName = "film$messageId.mkv",
        chatTitle = "Films", sizeBytes = 2_000, durationSec = 5_400, mimeType = "video/x-matroska", addedAt = at,
    )

    @Test
    fun `remove takes the card out and undo puts back the same place and order`() = runBlocking {
        val d = Device()
        d.settings.saveResumePosition(-100L, 1L, 600_000L, 3_600_000L, line("Alpha", 100))
        d.settings.saveResumePosition(-100L, 2L, 900_000L, 3_600_000L, line("Beta", 200))
        val before = d.settings.continueWatching.first()
        val beta = before.first { it.messageId == 2L }

        val undo = HistoryEdits.removeContinueCards(d.settings, d.watched, listOf(beta))
        assertEquals(1, undo.count)
        assertEquals(listOf(1L), d.settings.continueWatching.first().map { it.messageId })

        HistoryEdits.undo(d.settings, d.watched, undo)
        val after = d.settings.continueWatching.first()
        assertEquals(before, after)
        assertEquals(900_000L, d.settings.resumePosition(-100L, 2L))
        // The playing state kept in the description comes back with it.
        assertEquals(1.25f, after.first { it.messageId == 2L }.state?.speed)
    }

    @Test
    fun `removing the same card twice takes nothing the second time`() = runBlocking {
        val d = Device()
        d.settings.saveResumePosition(-100L, 1L, 600_000L, 3_600_000L, line("Alpha", 100))
        val card = d.settings.continueWatching.first().single()
        HistoryEdits.removeContinueCards(d.settings, d.watched, listOf(card))
        val second = HistoryEdits.removeContinueCards(d.settings, d.watched, listOf(card))
        assertTrue(second.isEmpty)
    }

    @Test
    fun `undo keeps a newer place written after the removal`() = runBlocking {
        val d = Device()
        d.settings.saveResumePosition(-100L, 1L, 600_000L, 3_600_000L, line("Alpha", 100))
        val card = d.settings.continueWatching.first().single()
        val undo = HistoryEdits.removeContinueCards(d.settings, d.watched, listOf(card))
        // Played again on this device before Undo was pressed.
        d.settings.saveResumePosition(-100L, 1L, 1_200_000L, 3_600_000L, line("Alpha", 300))
        HistoryEdits.undo(d.settings, d.watched, undo)
        assertEquals(1_200_000L, d.settings.resumePosition(-100L, 1L))
    }

    @Test
    fun `remove from history takes the watched tick of the same video too`() = runBlocking {
        val d = Device()
        d.settings.saveResumePosition(-100L, 5L, 600_000L, 3_600_000L, line("Rewatch", 100))
        d.watched.markWatched(mark(5L, 50))
        val card = d.settings.continueWatching.first().single()
        val undo = HistoryEdits.removeContinueCards(d.settings, d.watched, listOf(card))
        assertTrue(d.settings.continueWatching.first().isEmpty())
        assertTrue(d.watched.history.first().isEmpty())
        HistoryEdits.undo(d.settings, d.watched, undo)
        assertEquals(1, d.settings.continueWatching.first().size)
        assertEquals(listOf(mark(5L, 50)), d.watched.history.first())
    }

    @Test
    fun `bulk remove of watched cards and its undo`() = runBlocking {
        val d = Device()
        (1L..4L).forEach { d.watched.markWatched(mark(it, it * 10)) }
        val cards = d.watched.history.first().filter { it.messageId % 2 == 0L }
        val undo = HistoryEdits.removeWatchedCards(d.settings, d.watched, cards)
        assertEquals(2, undo.count)
        assertEquals(listOf(3L, 1L), d.watched.history.first().map { it.messageId })
        HistoryEdits.undo(d.settings, d.watched, undo)
        assertEquals(listOf(4L, 3L, 2L, 1L), d.watched.history.first().map { it.messageId })
    }

    @Test
    fun `clear history empties both lists, keeps watch later, and undo brings them back`() = runBlocking {
        val d = Device()
        d.settings.saveResumePosition(-100L, 1L, 600_000L, 3_600_000L, line("Alpha", 100))
        d.settings.saveResumePosition(-100L, 2L, 700_000L, 3_600_000L, line("Beta", 200))
        d.watched.markWatched(mark(3L, 30))
        d.watched.watchLater.add(later(9L, 90))
        val before = d.settings.continueWatching.first()

        val undo = HistoryEdits.clear(d.settings, d.watched)
        assertEquals(3, undo.count)
        assertTrue(d.settings.continueWatching.first().isEmpty())
        assertTrue(d.watched.history.first().isEmpty())
        assertEquals(1, d.watched.watchLater.items.first().size)

        HistoryEdits.undo(d.settings, d.watched, undo)
        assertEquals(before, d.settings.continueWatching.first())
        assertEquals(1, d.watched.history.first().size)
        // Twice is harmless.
        HistoryEdits.undo(d.settings, d.watched, undo)
        assertEquals(before, d.settings.continueWatching.first())
    }

    @Test
    fun `watch later remove, clear and undo keep each video's place in the list`() = runBlocking {
        val d = Device()
        (1L..3L).forEach { d.watched.watchLater.add(later(it, it * 10)) }
        val undo = HistoryEdits.removeLater(d.watched, listOf(later(2L, 0).key), 1)
        assertEquals(listOf(3L, 1L), d.watched.watchLater.items.first().map { it.messageId })
        HistoryEdits.undo(d.settings, d.watched, undo)
        assertEquals(listOf(3L, 2L, 1L), d.watched.watchLater.items.first().map { it.messageId })

        val cleared = HistoryEdits.clearLater(d.watched)
        assertTrue(d.watched.watchLater.items.first().isEmpty())
        HistoryEdits.undo(d.settings, d.watched, cleared)
        assertEquals(listOf(3L, 2L, 1L), d.watched.watchLater.items.first().map { it.messageId })
    }
}
