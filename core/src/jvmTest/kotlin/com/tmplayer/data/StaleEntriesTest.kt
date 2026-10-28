package com.tmplayer.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Kept entries whose message is gone (HX21): gone against merely unanswered, and what is let go. */
class StaleEntriesTest {

    private fun gone(code: Int, message: String) = StaleEntries.classify(code, message)

    @Test
    fun `a deleted message is gone`() {
        assertEquals(MessageLookup.Gone(GoneReason.Deleted), gone(404, "Not Found"))
        assertEquals(MessageLookup.Gone(GoneReason.Deleted), gone(404, ""))
        assertEquals(MessageLookup.Gone(GoneReason.Deleted), gone(400, "MESSAGE_ID_INVALID"))
        assertEquals(MessageLookup.Gone(GoneReason.Deleted), gone(400, "Message not found"))
    }

    @Test
    fun `a chat closed to the account is gone`() {
        for (text in listOf("CHANNEL_PRIVATE", "CHAT_FORBIDDEN", "USER_BANNED_IN_CHANNEL", "CHANNEL_INVALID", "Can't access the chat")) {
            assertEquals(text, MessageLookup.Gone(GoneReason.ChatClosed), gone(400, text))
        }
    }

    @Test
    fun `waits, outages and a chat not loaded yet are not gone`() {
        for ((code, text) in listOf(
            420 to "FLOOD_WAIT_30",
            429 to "Too Many Requests",
            400 to "FLOOD_WAIT_5",
            500 to "Internal Server Error",
            502 to "Message not found",
            -1 to "Timeout",
            400 to "Chat not found",
            400 to "Request aborted",
            401 to "Unauthorized",
            0 to "",
            400 to "SOMETHING_NEW",
        )) {
            assertEquals("$code $text", MessageLookup.Unreachable, gone(code, text))
        }
    }

    @Test
    fun `a flood wait on a message that also reads as missing is a wait`() {
        assertEquals(MessageLookup.Unreachable, gone(404, "FLOOD_WAIT_12"))
    }

    @Test
    fun `only a gone answer without a copy on the device lets an entry go`() {
        assertTrue(StaleEntries.shouldForget(MessageLookup.Gone(GoneReason.Deleted), keptOnDevice = false))
        assertFalse(StaleEntries.shouldForget(MessageLookup.Gone(GoneReason.ChatClosed), keptOnDevice = true))
        assertFalse(StaleEntries.shouldForget(MessageLookup.Unreachable, keptOnDevice = false))
        assertFalse(StaleEntries.shouldForget(MessageLookup.Found(video(1)), keptOnDevice = false))
    }

    @Test
    fun `a download's file against the disk`() {
        assertEquals(DownloadPlace.Present, StaleEntries.downloadPlace(true, 1_000, 1_000, true))
        assertEquals(DownloadPlace.Present, StaleEntries.downloadPlace(true, 1_200, 1_000, true))
        assertEquals(DownloadPlace.Present, StaleEntries.downloadPlace(true, 10, 0, true))
        assertEquals(DownloadPlace.Missing, StaleEntries.downloadPlace(true, 500, 1_000, true))
        assertEquals(DownloadPlace.Missing, StaleEntries.downloadPlace(true, 0, 1_000, true))
        assertEquals(DownloadPlace.Missing, StaleEntries.downloadPlace(false, 0, 1_000, true))
        // The folder went with it: an unplugged drive or a moved folder, which may come back.
        assertEquals(DownloadPlace.FolderGone, StaleEntries.downloadPlace(false, 0, 1_000, false))
    }

    // ---- what GoneVideos takes out ------------------------------------------------------------

    private class Device {
        val dir = Files.createTempDirectory("tm-gone").toFile()
        val settings = SettingsStore(SettingsStore.openDataStore(dir.resolve(SettingsStore.FILE_NAME)))
        val watched = WatchedStore(WatchedStore.openDataStore(dir.resolve(WatchedStore.FILE_NAME)))
    }

    private fun video(messageId: Long, chatId: Long = CHAT) = MediaItem(
        chatId = chatId, messageId = messageId, fileId = 7, title = "Film $messageId", sizeBytes = 2_000_000_000,
        durationSec = 6_300, mimeType = "video/x-matroska", thumbnailFileId = 9, miniThumbnail = null,
        date = 1_700_000_000, fileName = "Film.$messageId.2024.1080p.mkv",
    )

    private fun line(title: String, updatedAt: Long) = ResumeRecord.encode(
        fileId = 11, title = title, chatTitle = "Films", sizeBytes = 5_000, durationSec = 6_000, updatedAt = updatedAt,
        state = null,
    )

    private fun mark(messageId: Long) = WatchedRecord(
        chatId = CHAT, messageId = messageId, fileId = 7, title = "Ep $messageId", chatTitle = "Show",
        sizeBytes = 1_000, durationSec = 1_400, watchedAt = 100, manual = true,
    )

    private fun goneOf(vararg ids: Long, reason: GoneReason = GoneReason.Deleted) = ids.map { GoneVideos.Gone(CHAT, it, reason) }

    @Test
    fun `a gone video leaves Continue watching, Watched and Watch later`() = runBlocking {
        val d = Device()
        d.settings.saveResumePosition(CHAT, 1L, 600_000L, 3_600_000L, line("Alpha", 100))
        d.settings.saveResumePosition(CHAT, 2L, 600_000L, 3_600_000L, line("Beta", 200))
        d.watched.markWatched(mark(3))
        d.watched.watchLater.add(WatchLaterRecord.of(video(4), chatTitle = "Films", addedAt = 10))
        d.watched.watchLater.add(WatchLaterRecord.of(video(1), chatTitle = "Films", addedAt = 11))

        val forgotten = GoneVideos.forget(d.settings, d.watched, goneOf(1, 3, 4)) { _, _ -> false }

        assertEquals(GoneVideos.Forgotten(history = 2, watchLater = 2, videos = 3), forgotten)
        assertEquals(listOf(2L), d.settings.continueWatching.first().map { it.messageId })
        assertTrue(d.watched.watched.first().isEmpty())
        assertTrue(d.watched.watchLater.items.first().isEmpty())
    }

    @Test
    fun `a gone video with a download still on the device stays`() = runBlocking {
        val d = Device()
        d.settings.saveResumePosition(CHAT, 1L, 600_000L, 3_600_000L, line("Alpha", 100))
        val forgotten = GoneVideos.forget(d.settings, d.watched, goneOf(1)) { _, messageId -> messageId == 1L }
        assertTrue(forgotten.isEmpty)
        assertEquals(listOf(1L), d.settings.continueWatching.first().map { it.messageId })
    }

    @Test
    fun `a delete of messages nobody kept changes nothing`() = runBlocking {
        val d = Device()
        d.settings.saveResumePosition(CHAT, 1L, 600_000L, 3_600_000L, line("Alpha", 100))
        // A channel clearing out a thousand posts: one read of each store, nothing written.
        val forgotten = GoneVideos.forget(d.settings, d.watched, goneOf(*(100L..1_100L).toList().toLongArray())) { _, _ -> false }
        assertTrue(forgotten.isEmpty)
        assertEquals(1, d.settings.continueWatching.first().size)
    }

    @Test
    fun `only the gone copy goes, not another chat's copy of the same film`() = runBlocking {
        val d = Device()
        d.settings.saveResumePosition(CHAT, 1L, 600_000L, 3_600_000L, line("Alpha", 100))
        d.settings.saveResumePosition(OTHER, 1L, 600_000L, 3_600_000L, line("Alpha", 200))
        GoneVideos.forget(d.settings, d.watched, goneOf(1)) { _, _ -> false }
        assertEquals(listOf(OTHER), d.settings.continueWatching.first().map { it.chatId })
    }

    @Test
    fun `a failing check of the device keeps the entry`() = runBlocking {
        val d = Device()
        d.settings.saveResumePosition(CHAT, 1L, 600_000L, 3_600_000L, line("Alpha", 100))
        val forgotten = GoneVideos.forget(d.settings, d.watched, goneOf(1)) { _, _ -> error("disk") }
        assertTrue(forgotten.isEmpty)
        assertEquals(1, d.settings.continueWatching.first().size)
    }

    @Test
    fun `reports in one window arrive as one list, each video once`() = runBlocking {
        val source = flow {
            emit(goneOf(1))
            emit(goneOf(2))
            emit(goneOf(1, reason = GoneReason.NotVideo))
            delay(300)
            emit(goneOf(3))
        }
        val windows = GoneVideos.windowed(source, 100).toList()
        assertEquals(listOf(listOf(1L, 2L), listOf(3L)), windows.map { w -> w.map { it.messageId } })
        assertEquals(GoneReason.NotVideo, windows.first().first { it.messageId == 1L }.reason)
    }

    @Test
    fun `the session remembers what it heard, and forgets it at sign out`() {
        GoneVideos.report(CHAT, 77L, GoneReason.ChatClosed)
        assertEquals(GoneReason.ChatClosed, GoneVideos.reasonFor(CHAT, 77L))
        assertNull(GoneVideos.reasonFor(CHAT, 78L))
        // Nonsense ids are not remembered.
        GoneVideos.report(0L, 5L, GoneReason.Deleted)
        GoneVideos.report(CHAT, -5L, GoneReason.Deleted)
        assertNull(GoneVideos.reasonFor(0L, 5L))
        assertNull(GoneVideos.reasonFor(CHAT, -5L))
        GoneVideos.clear()
        assertNull(GoneVideos.reasonFor(CHAT, 77L))
    }

    private companion object {
        const val CHAT = -100L
        const val OTHER = -200L
    }
}
