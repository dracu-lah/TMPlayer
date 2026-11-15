package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The order the viewer gives Home's rows (EG-F7, EG-F23, EG-F27): stored as keys, trusted for nothing else. */
class HomeOrderTest {

    private val defaults = listOf("continue", "recent", "chat-1", "chat-2", "chat-3")

    private fun chat(id: Long) = ChatSummary(id, "Chat $id", null, 0, ChatKind.Channel)

    @Test
    fun `no stored order is Home's own`() {
        assertEquals(defaults, HomeOrder.arrange(defaults, emptyList()))
    }

    @Test
    fun `a stored order is followed`() {
        val stored = listOf("chat-3", "continue", "chat-1", "recent", "chat-2")
        assertEquals(stored, HomeOrder.arrange(defaults, stored))
    }

    @Test
    fun `a row id from a newer version is ignored`() {
        val stored = listOf("future-row", "chat-2", "hologram", "continue")
        val out = HomeOrder.arrange(defaults, stored)
        assertEquals(defaults.toSet(), out.toSet())
        assertEquals(defaults.size, out.size)
        assertTrue(out.indexOf("chat-2") < out.indexOf("continue"))
    }

    @Test
    fun `a new row is appended at its default place`() {
        // chat-2 was starred after the order was saved: it stands right after chat-1, its default neighbour.
        val out = HomeOrder.arrange(defaults, listOf("chat-3", "chat-1", "continue", "recent"))
        assertEquals(listOf("chat-3", "chat-1", "chat-2", "continue", "recent"), out)
    }

    @Test
    fun `a new first row goes to the top`() {
        assertEquals(listOf("continue", "recent", "chat-1"), HomeOrder.arrange(listOf("continue", "recent", "chat-1"), listOf("recent", "chat-1")))
    }

    @Test
    fun `an unstarred chat drops out of the order Settings lists`() {
        val stored = listOf("chat-3", "chat-2", "continue", "recent", "chat-1")
        val listed = HomeOrder.editorRows(listOf(chat(1), chat(3)), stored).map { it.key }
        assertEquals(listOf("chat-3", "continue", "recent", "chat-1"), listed)
    }

    @Test
    fun `an unstarred chat has no row on Home and the others keep their order`() {
        val rows = HomeRows.build(
            continueWatching = emptyList(), favourites = listOf(chat(1), chat(3)), loaded = emptyMap(), recent = null,
            starred = setOf(1, 3), autoFill = true, layout = HomeLayout(order = listOf("chat-3", "chat-2", "chat-1")),
        )
        assertEquals(listOf("chat-3", "chat-1"), rows.map { it.key }.filter { it.startsWith("chat-") })
    }

    @Test
    fun `Settings lists the rows Home can draw with the chats' titles`() {
        val listed = HomeOrder.editorRows(listOf(chat(1), chat(2)), emptyList())
        assertEquals(listOf("continue", "recent", "chat-1", "chat-2"), listed.map { it.key })
        assertEquals(listOf(null, null, "Chat 1", "Chat 2"), listed.map { it.chatTitle })
    }

    @Test
    fun `move up and down stop at the ends and ignore a key that is not there`() {
        assertEquals(listOf("recent", "continue", "chat-1"), HomeOrder.move(listOf("continue", "recent", "chat-1"), "recent", -1))
        assertEquals(listOf("continue", "recent", "chat-1"), HomeOrder.move(listOf("continue", "recent", "chat-1"), "continue", -1))
        assertEquals(listOf("continue", "recent", "chat-1"), HomeOrder.move(listOf("continue", "recent", "chat-1"), "chat-1", 1))
        assertEquals(listOf("continue", "recent", "chat-1"), HomeOrder.move(listOf("continue", "recent", "chat-1"), "chat-9", 1))
        assertEquals(listOf("chat-1", "continue", "recent"), HomeOrder.moveTo(listOf("continue", "recent", "chat-1"), 2, -5))
    }

    @Test
    fun `parse and encode round trip, and keep only keys`() {
        val order = listOf("chat-3", "continue", "recent")
        assertEquals(order, HomeOrder.parse(HomeOrder.encode(order)))
        assertEquals(listOf("chat-3", "recent"), HomeOrder.parse("chat-3,,Bad Key, ,recent,chat-3,\u0000,${"x".repeat(100)}"))
        assertEquals(emptyList<String>(), HomeOrder.parse(null))
    }

    @Test
    fun `a huge stored order is capped`() {
        val huge = (1..10_000).joinToString(",") { "chat-$it" }
        assertEquals(HomeOrder.MAX_KEYS, HomeOrder.parse(huge).size)
    }

    @Test
    fun `a layout reads its stored order and a garbage one is Home's own`() {
        assertEquals(listOf("recent", "continue"), HomeLayout.of(null, null, null, null, "recent,continue").order)
        assertEquals(emptyList<String>(), HomeLayout.of(null, null, null, null, "!!!").order)
    }

    @Test
    fun `Home builds its rows in the stored order`() {
        val one = MediaItem(
            chatId = 1, messageId = 1, fileId = 1, title = "A", sizeBytes = 1, durationSec = 60,
            mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 1, fileName = "Holiday 1.mp4",
        )
        val two = one.copy(chatId = 2, messageId = 2, date = 2, fileName = "Holiday 2.mp4")
        val resume = ResumeRecord(
            chatId = 1, messageId = 1, fileId = 1, title = "A", chatTitle = "", sizeBytes = 1, durationSec = 60,
            positionMs = 10_000, durationMs = 60_000, updatedAt = 1,
        )
        fun keys(order: List<String>) = HomeRows.build(
            continueWatching = listOf(resume), favourites = listOf(chat(1), chat(2)), loaded = mapOf(1L to listOf(one), 2L to listOf(two)),
            recent = listOf(one, two), recentMinStarred = 1, starred = setOf(1, 2), autoFill = true, layout = HomeLayout(order = order),
        ).map { it.key }
        assertEquals(listOf("continue", "recent", "chat-2"), keys(emptyList()))
        assertEquals(listOf("chat-2", "continue", "recent"), keys(listOf("chat-2", "continue", "recent")))
        // The chat row that came after Recently added keeps what Continue took first, whatever the order.
        assertEquals(listOf("recent", "chat-2", "continue"), keys(listOf("recent", "chat-2", "continue")))
    }
}
