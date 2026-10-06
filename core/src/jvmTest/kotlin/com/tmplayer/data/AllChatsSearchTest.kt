package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AllChatsSearchTest {

    private fun chat(id: Long, title: String, archived: Boolean = false) =
        ChatSummary(id, title, null, 0, ChatKind.Channel, isArchived = archived)

    private fun video(chatId: Long, messageId: Long, fileName: String, date: Int = messageId.toInt()) = MediaItem(
        chatId = chatId,
        messageId = messageId,
        fileId = 1,
        title = fileName,
        sizeBytes = 1,
        durationSec = 60,
        mimeType = "video/mp4",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = date,
        fileName = fileName,
    )

    private val chats = listOf(
        chat(1, "Weekend Clips"),
        chat(2, "Harbour Notes Fans"),
        chat(3, "Recipe Notes"),
        chat(4, "Old harbour photos", archived = true),
    )

    @Test
    fun `the results split into the chats named like the query and the videos found`() {
        val videos = listOf(video(1, 10, "Harbour.Notes.S01E01.mkv"), video(3, 11, "harbour-walk.mp4"))
        val results = AllChatsSearch.split("harbour", chats, videos)
        assertEquals("harbour", results.query)
        // Archived chats are found too: this search is across every chat.
        assertEquals(listOf(2L, 4L), results.chats.map { it.id }.sorted())
        assertEquals(videos, results.videos)
        assertFalse(results.isEmpty)
    }

    @Test
    fun `chats are matched forgivingly and best first`() {
        val found = AllChatsSearch.matchingChats(chats, "harbor notes")
        assertEquals(2L, found.first().id)
        assertTrue(AllChatsSearch.matchingChats(chats, "").isEmpty())
        assertTrue(AllChatsSearch.matchingChats(chats, "zzzz").isEmpty())
    }

    @Test
    fun `the chats half stops at its limit, leaving the screen to the videos`() {
        val many = (1L..20L).map { chat(it, "Notes $it") }
        assertEquals(AllChatsSearch.CHAT_LIMIT, AllChatsSearch.matchingChats(many, "notes").size)
    }

    @Test
    fun `a search with no matching chats and no videos is empty`() {
        assertTrue(AllChatsSearch.split("zzzz", chats, emptyList()).isEmpty)
    }

    @Test
    fun `pages merge each video once and keep what is on screen in place`() {
        val first = AllChatsSearch.merge(emptyList(), listOf(video(1, 5, "a.mp4", date = 50), video(2, 9, "b.mp4", date = 90)))
        assertEquals(listOf(9L, 5L), first.map { it.messageId })
        val second = AllChatsSearch.merge(first, listOf(video(1, 5, "a.mp4", date = 50), video(3, 2, "c.mp4", date = 95)))
        // The repeat is dropped; the new one goes after, even though it is newer.
        assertEquals(listOf(9L, 5L, 2L), second.map { it.messageId })
    }

    @Test
    fun `the fallback asks for the longest word, and only for queries of several words`() {
        assertEquals("harbour", AllChatsSearch.fallbackQuery("harbour notes 2"))
        assertNull(AllChatsSearch.fallbackQuery("harbour"))
        assertNull(AllChatsSearch.fallbackQuery("  "))
    }

    @Test
    fun `a fallback page is held to the whole query, a full one is not`() {
        val page = listOf(video(1, 1, "Harbour.Notes.S02E01.mkv"), video(1, 2, "lighthouse-sunset.mp4"))
        assertEquals(page, AllChatsSearch.keep(page, "harbour notes", fallback = false))
        assertEquals(listOf(1L), AllChatsSearch.keep(page, "harbour notes", fallback = true).map { it.messageId })
    }

    @Test
    fun `the scope has the chat list and all videos`() {
        assertEquals(listOf(SearchScope.Chats, SearchScope.AllVideos), SearchScope.entries.toList())
    }

    @Test
    fun `a search is done once both kinds have run out`() {
        assertFalse(AllChatsCursor().done)
        assertFalse(AllChatsCursor(videoDone = true).done)
        assertTrue(AllChatsCursor(videoDone = true, documentDone = true).done)
    }
}
