package com.tmplayer.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A search of one chat meeting albums: the photo's caption finds the videos, even when the history splits the album. */
class AlbumSearchTest {

    private val chatId = 41L

    private fun video(message: Long, album: Long, name: String = "video.mp4") = MediaItem(
        chatId = chatId, messageId = message, fileId = message.toInt(), title = name, sizeBytes = 1_000_000_000L, durationSec = 3_000,
        mimeType = "video/mp4", thumbnailFileId = 900, miniThumbnail = null, date = 1_700_000_000, fileName = name, albumId = album,
    )

    private val photo = AlbumPhoto(chatId, 7, 20, 500, null, "Harbour Notes Season One")

    /** Telegram's own search finds nothing (it does not look at a photo's caption for a video); the history is read back. */
    private class History(private val pages: List<ChatHistoryPage>) : ChatSearchSource {
        var reads = 0
        override suspend fun search(query: String, cursors: MediaCursors, limit: Int) =
            MediaPage(emptyList(), cursors.copy(videoDone = true, documentDone = true, animationDone = true), true)

        override suspend fun history(fromMessageId: Long, limit: Int, onlyLocal: Boolean): ChatHistoryPage = pages[reads++]
        override suspend fun online() = true
    }

    @Test
    fun anAlbumSplitAcrossTwoHistoryPagesIsPutTogether() = runBlocking {
        // Newest page: the album's videos. The photo that names them sits on the next page back.
        val source = History(
            listOf(
                ChatHistoryPage(listOf(video(22, 7), video(21, 7), video(30, 0, "Unrelated.2020.mkv")), read = 100, next = 21),
                ChatHistoryPage(emptyList(), read = 100, next = 0, photos = listOf(photo)),
            ),
        )
        val answer = ChatSearchRun(source, "harbour notes", emptyList(), chatId).first()
        assertEquals(setOf(21L, 22L), answer.items.map { it.messageId }.toSet())
        assertTrue(answer.items.all { it.caption == "Harbour Notes Season One" && it.thumbnailFileId == 500 })
    }

    @Test
    fun theVideosOfAnotherAlbumDoNotTakeThePhoto() = runBlocking {
        val source = History(
            listOf(
                ChatHistoryPage(listOf(video(22, 8)), read = 100, next = 21),
                ChatHistoryPage(emptyList(), read = 100, next = 0, photos = listOf(photo)),
            ),
        )
        assertEquals(emptyList(), ChatSearchRun(source, "harbour notes", emptyList(), chatId).first().items)
    }
}
