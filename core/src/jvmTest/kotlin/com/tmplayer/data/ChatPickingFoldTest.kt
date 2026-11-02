package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPickingFoldTest {

    private fun row(id: Long, band: ChatPicking.Band = ChatPicking.Band.Chats, starred: Boolean = false) =
        ChatPicking.Row(id, "Chat $id", band, null, starred)

    private val none = ChatCount.Known(0)
    private val some = ChatCount.Known(5)

    private fun ids(items: List<ChatPicking.Item>) = items.map {
        when (it) {
            is ChatPicking.Item.Chat -> it.row.id.toString()
            is ChatPicking.Item.Folded -> "fold${it.count}"
        }
    }

    @Test
    fun `chats counted at no videos fold into one row at the end of their band`() {
        val rows = listOf(row(1), row(2), row(3), row(4))
        val counts = mapOf(1L to none, 2L to some, 3L to none, 4L to some)
        assertEquals(listOf("2", "4", "fold2"), ids(ChatPicking.fold(rows, counts, emptySet())))
    }

    @Test
    fun `ticked, starred, uncounted, unknown, Saved and Gone never fold`() {
        val rows = listOf(
            row(1), row(2, starred = true), row(3), row(4), row(5, ChatPicking.Band.Saved), row(6, ChatPicking.Band.Gone),
        )
        val counts = mapOf(1L to none, 2L to none, 4L to ChatCount.Unknown, 5L to none, 6L to none)
        val items = ChatPicking.fold(rows, counts, ticked = setOf(1L))
        assertTrue(items.none { it is ChatPicking.Item.Folded })
        assertEquals(6, items.size)
    }

    @Test
    fun `each band folds on its own and an open band shows them in place at its end`() {
        val rows = listOf(
            row(1, ChatPicking.Band.Recommended), row(2, ChatPicking.Band.Recommended),
            row(3), row(4),
        )
        val counts = mapOf(1L to some, 2L to none, 3L to none, 4L to some)
        assertEquals(listOf("1", "fold1", "4", "fold1"), ids(ChatPicking.fold(rows, counts, emptySet())))
        assertEquals(
            listOf("1", "fold1", "4", "3"),
            ids(ChatPicking.fold(rows, counts, emptySet(), open = setOf(ChatPicking.Band.Chats))),
        )
    }

    @Test
    fun `foldedIn lists what the fold opens, first chat first`() {
        val rows = listOf(row(1), row(2), row(3))
        val counts = mapOf(1L to none, 2L to some, 3L to none)
        assertEquals(listOf(1L, 3L), ChatPicking.foldedIn(rows, counts, emptySet(), ChatPicking.Band.Chats).map { it.id })
    }

    @Test
    fun `toCount skips counted and gone chats and honours the limit`() {
        val rows = listOf(row(1), row(2), row(3, ChatPicking.Band.Gone), row(4), row(5))
        val counts = mapOf(1L to some)
        assertEquals(listOf(2L, 4L, 5L), ChatPicking.toCount(rows, counts))
        assertEquals(listOf(2L, 4L), ChatPicking.toCount(rows, counts, limit = 2))
    }
}
