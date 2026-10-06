package com.tmplayer.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoChangesTest {

    private fun video(messageId: Long, date: Int = messageId.toInt(), chatId: Long = CHAT, title: String = "Episode $messageId") =
        MediaItem(
            chatId = chatId,
            messageId = messageId,
            fileId = messageId.toInt(),
            title = title,
            sizeBytes = 900_000_000,
            durationSec = 2_400,
            mimeType = "video/x-matroska",
            thumbnailFileId = 0,
            miniThumbnail = null,
            date = date,
        )

    @Test
    fun `a post and its deletion in one window never show`() {
        val batches = VideoChanges.coalesce(
            listOf(
                VideoChange.Added(CHAT, video(10)),
                VideoChange.Removed(CHAT, setOf(10)),
            ),
        )
        assertTrue(batches.single().added.isEmpty())
        assertEquals(setOf(10L), batches.single().removed)
    }

    @Test
    fun `an edit to a deleted message is dropped`() {
        val batch = VideoChanges.coalesce(
            listOf(VideoChange.Edited(CHAT, 7), VideoChange.Removed(CHAT, setOf(7))),
        ).single()
        assertTrue(batch.edited.isEmpty())
        assertEquals(setOf(7L), batch.removed)
    }

    @Test
    fun `a burst lands newest first, one batch per chat, a repeat listed once`() {
        val batches = VideoChanges.coalesce(
            listOf(
                VideoChange.Added(CHAT, video(1)),
                VideoChange.Added(OTHER, video(5, chatId = OTHER)),
                VideoChange.Added(CHAT, video(3)),
                VideoChange.Added(CHAT, video(2)),
                VideoChange.Added(CHAT, video(3, title = "Episode 3, fixed")),
            ),
        )
        assertEquals(listOf(CHAT, OTHER), batches.map { it.chatId })
        assertEquals(listOf(3L, 2L, 1L), batches[0].added.map { it.messageId })
        assertEquals("Episode 3, fixed", batches[0].added.first().title)
    }

    @Test
    fun `a new post goes to the top and a deleted one goes`() {
        val listing = listOf(video(9), video(8), video(7))
        val batch = VideoBatch(CHAT, added = listOf(video(12)), edited = emptySet(), removed = setOf(8))
        assertEquals(listOf(12L, 9L, 7L), VideoChanges.merge(listing, batch).map { it.messageId })
    }

    @Test
    fun `an edit replaces in place, and one that is no longer a video leaves`() {
        val listing = listOf(video(9), video(8), video(7))
        val batch = VideoBatch(CHAT, added = emptyList(), edited = setOf(9, 8), removed = emptySet())
        val merged = VideoChanges.merge(listing, batch, edits = mapOf(9L to video(9, title = "Renamed"), 8L to null))
        assertEquals(listOf(9L, 7L), merged.map { it.messageId })
        assertEquals("Renamed", merged.first().title)
    }

    @Test
    fun `an edit that made a video of an unlisted message adds it where its date says`() {
        val listing = listOf(video(9), video(7))
        val batch = VideoBatch(CHAT, added = emptyList(), edited = setOf(8), removed = emptySet())
        val merged = VideoChanges.merge(listing, batch, edits = mapOf(8L to video(8)))
        assertEquals(listOf(9L, 8L, 7L), merged.map { it.messageId })
    }

    @Test
    fun `nothing listed twice, and an unchanged listing is handed back as it was`() {
        val listing = listOf(video(9), video(8))
        val repeat = VideoBatch(CHAT, added = listOf(video(9)), edited = emptySet(), removed = setOf(100))
        assertSame(listing, VideoChanges.merge(listing, repeat))
        // Filtered out by the caller (the size limits): nothing to add.
        val filtered = VideoBatch(CHAT, added = listOf(video(12)), edited = emptySet(), removed = emptySet())
        assertSame(listing, VideoChanges.merge(listing, filtered, added = emptyList()))
    }

    @Test
    fun `another chat's change leaves a listing alone`() {
        val listing = listOf(video(9), video(8))
        val elsewhere = VideoBatch(OTHER, added = listOf(video(20, chatId = OTHER)), edited = emptySet(), removed = setOf(9))
        assertEquals(listOf(9L, 8L, 20L).sorted(), VideoChanges.merge(listing, elsewhere).map { it.messageId }.sorted())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a burst inside the window is handed over once, after the window`() = runTest {
        val events = MutableSharedFlow<VideoChange>(extraBufferCapacity = 16)
        val seen = mutableListOf<List<VideoBatch>>()
        val job = launch { events.batched(2_000).collect { seen += it } }
        runCurrent()
        events.emit(VideoChange.Added(CHAT, video(1)))
        advanceTimeBy(500)
        events.emit(VideoChange.Added(CHAT, video(2)))
        advanceTimeBy(1_000)
        assertTrue(seen.isEmpty())
        advanceTimeBy(600)
        runCurrent()
        assertEquals(1, seen.size)
        assertEquals(listOf(2L, 1L), seen.single().single().added.map { it.messageId })

        // A later post opens a window of its own.
        events.emit(VideoChange.Removed(CHAT, setOf(1)))
        advanceTimeBy(2_100)
        runCurrent()
        assertEquals(2, seen.size)
        assertEquals(setOf(1L), seen.last().single().removed)
        job.cancel()
    }

    private companion object {
        const val CHAT = -1001L
        const val OTHER = -1002L
    }
}
