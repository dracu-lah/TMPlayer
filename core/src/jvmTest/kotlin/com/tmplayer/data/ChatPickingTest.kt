package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPickingTest {

    private fun chat(
        id: Long,
        title: String = "Chat $id",
        kind: ChatKind = ChatKind.Channel,
        archived: Boolean = false,
    ) = ChatSummary(
        id = id,
        title = title,
        miniThumbnail = null,
        photoFileId = 0,
        kind = kind,
        isArchived = archived,
    )

    private val saved = chat(1, "Saved Messages", ChatKind.Saved)
    private val account = listOf(
        saved,
        chat(2, "Harbour Films"),
        chat(3, "Night Lantern Club", ChatKind.Group),
        chat(4, "Archived Reels", archived = true),
        chat(5, "Anya", ChatKind.Direct),
    )

    private fun bands(rows: List<ChatPicking.Row>) = rows.map { it.band to it.id }

    @Test
    fun `Saved Messages comes first, then the recommended, the starred and the rest, the archive last`() {
        val rows = ChatPicking.rows(
            chats = account,
            added = emptySet(),
            starred = setOf(3L),
            recommended = listOf(2L),
        )
        assertEquals(
            listOf(
                ChatPicking.Band.Saved to 1L,
                ChatPicking.Band.Recommended to 2L,
                ChatPicking.Band.Starred to 3L,
                ChatPicking.Band.Chats to 5L,
                ChatPicking.Band.Archived to 4L,
            ),
            bands(rows),
        )
    }

    @Test
    fun `a chat is listed once, whichever band could have claimed it`() {
        val rows = ChatPicking.rows(account, emptySet(), starred = setOf(2L), recommended = listOf(2L))
        assertEquals(1, rows.count { it.id == 2L })
        assertEquals(ChatPicking.Band.Recommended, rows.first { it.id == 2L }.band)
        // The star still shows on the row, wherever the row sits.
        assertTrue(rows.first { it.id == 2L }.starred)
    }

    @Test
    fun `an archived chat is never starred into the band above the rest`() {
        val rows = ChatPicking.rows(account, emptySet(), starred = setOf(4L), recommended = emptyList())
        assertEquals(ChatPicking.Band.Archived, rows.first { it.id == 4L }.band)
    }

    @Test
    fun `a search answers about every chat, with no bands in the way`() {
        val rows = ChatPicking.rows(account, emptySet(), starred = setOf(3L), recommended = listOf(2L), query = "harbour")
        assertEquals(listOf(2L), rows.map { it.id })
        assertEquals(listOf(ChatPicking.Band.Chats), rows.map { it.band })
    }

    @Test
    fun `the search forgives the accent nobody types`() {
        val chats = listOf(chat(9, "Cinéma Paradiso"))
        assertEquals(listOf(9L), ChatPicking.rows(chats, emptySet(), query = "cinema").map { it.id })
    }

    @Test
    fun `a filter narrows to its kind, and the archive filter lists the archive as the plain band`() {
        assertEquals(
            listOf(3L),
            ChatPicking.rows(account, emptySet(), scope = ChatPicking.Scope.Groups).map { it.id },
        )
        val archived = ChatPicking.rows(account, emptySet(), scope = ChatPicking.Scope.Archived)
        assertEquals(listOf(4L), archived.map { it.id })
        assertEquals(listOf(ChatPicking.Band.Chats), archived.map { it.band })
        // Saved Messages is a person, as it is everywhere else in the app.
        assertEquals(listOf(1L, 5L), ChatPicking.rows(account, emptySet(), scope = ChatPicking.Scope.People).map { it.id })
    }

    @Test
    fun `an added chat that is gone keeps a row, with the name it had, but only once the list is whole`() {
        val added = setOf(2L, 77L)
        val names = listOf(AddedChat(77L, "Old Film Club"))
        val half = ChatPicking.rows(account, added, names, listComplete = false)
        assertFalse("a chat still on its way is not gone", half.any { it.band == ChatPicking.Band.Gone })

        val whole = ChatPicking.rows(account, added, names, listComplete = true)
        val gone = whole.single { it.band == ChatPicking.Band.Gone }
        assertEquals(77L, gone.id)
        assertEquals("Old Film Club", gone.title)
        assertEquals(null, gone.chat)
    }

    @Test
    fun `select all and clear act on what is on the screen, and nothing else`() {
        val shown = ChatPicking.visible(ChatPicking.rows(account, emptySet(), scope = ChatPicking.Scope.Groups))
        assertEquals(setOf(3L), shown)
        // A tick made elsewhere survives Select all here.
        assertEquals(setOf(9L, 3L), ChatPicking.selectAll(setOf(9L), shown))
        // And survives Clear here.
        assertEquals(setOf(9L), ChatPicking.clear(setOf(9L, 3L), shown))
        assertTrue(ChatPicking.allTicked(setOf(3L, 9L), shown))
        assertFalse(ChatPicking.allTicked(setOf(9L), shown))
        // Nothing on screen: Select all has nothing to say.
        assertFalse(ChatPicking.allTicked(setOf(9L), emptySet()))
    }

    @Test
    fun `a gone row is not something Select all ticks`() {
        val rows = ChatPicking.rows(account, setOf(77L), listOf(AddedChat(77L, "Old")), listComplete = true)
        assertFalse(77L in ChatPicking.visible(rows))
    }

    @Test
    fun `a very large Select all is worth a word first`() {
        assertFalse(ChatPicking.needsConfirm(ChatPicking.CONFIRM_OVER))
        assertTrue(ChatPicking.needsConfirm(ChatPicking.CONFIRM_OVER + 1))
    }

    @Test
    fun `the foot counts the chats ticked and the videos known of them`() {
        val counts = mapOf<Long, ChatCount>(
            2L to ChatCount.Known(40),
            3L to ChatCount.Known(2),
            4L to ChatCount.Unknown,
        )
        val total = ChatPicking.total(setOf(2L, 3L, 4L, 5L), counts)
        assertEquals(4, total.chats)
        assertEquals(42, total.videos)
        assertEquals(2, total.counted)
        assertFalse("still filling in", total.whole)
        assertTrue(ChatPicking.total(setOf(2L), counts).whole)
    }

    @Test
    fun `the head start counts Saved Messages and the head of the list, and no more`() {
        val many = listOf(saved) + (10L..200L).map { chat(it) }
        val ahead = ChatPicking.headStart(many, ahead = 5)
        assertEquals(5, ahead.size)
        assertEquals(1L, ahead.first())
        assertEquals(listOf(1L, 10L, 11L, 12L, 13L), ahead)
    }

    @Test
    fun `the counted chats carry Telegram's order, so a tie keeps it`() {
        val counted = ChatPicking.counted(account, mapOf(2L to ChatCount.Known(9), 4L to ChatCount.Unknown))
        assertEquals(listOf(0, 1, 2, 3, 4), counted.map { it.order })
        assertEquals(9, counted.first { it.id == 2L }.videos)
        assertEquals(null, counted.first { it.id == 4L }.videos)
        assertTrue(counted.first { it.id == 1L }.savedMessages)
    }
}
