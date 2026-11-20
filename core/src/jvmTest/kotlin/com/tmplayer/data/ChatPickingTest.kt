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
    fun `the head start counts Saved Messages and the head of the list, and no more`() {
        val many = listOf(saved) + (10L..200L).map { chat(it) }
        val ahead = ChatPicking.headStart(many, ahead = 5)
        assertEquals(5, ahead.size)
        assertEquals(1L, ahead.first())
        assertEquals(listOf(1L, 10L, 11L, 12L, 13L), ahead)
    }

    @Test
    fun `the scan reads channels before groups before people, recency inside each, and every chat once`() {
        val mixed = listOf(
            chat(1, kind = ChatKind.Direct),
            chat(2, kind = ChatKind.Group),
            chat(3, kind = ChatKind.Channel),
            chat(4, kind = ChatKind.Saved),
            chat(5, kind = ChatKind.Channel),
            chat(6, kind = ChatKind.Group),
        )
        assertEquals(listOf(4L, 3L, 5L, 2L, 6L, 1L), ChatPicking.scanOrder(mixed).map { it.id })
        assertEquals(listOf(4L, 1L, 2L), ChatPicking.headStart(mixed, ahead = 3))
    }

    @Test
    fun `the counted chats carry Telegram's order, so a tie keeps it`() {
        val counted = ChatPicking.counted(account, mapOf(2L to ChatCount.Known(9), 4L to ChatCount.Unknown))
        assertEquals(listOf(0, 1, 2, 3, 4), counted.map { it.order })
        assertEquals(9, counted.first { it.id == 2L }.videos)
        assertEquals(null, counted.first { it.id == 4L }.videos)
        assertTrue(counted.first { it.id == 1L }.savedMessages)
    }

    @Test
    fun `the chats already added form their own band first, and are not listed a second time`() {
        val rows = ChatPicking.rows(
            chats = account,
            added = setOf(2L, 4L),
            starred = setOf(2L),
            recommended = listOf(2L, 3L),
            alreadyAdded = setOf(2L, 4L),
        )
        assertEquals(
            listOf(
                ChatPicking.Band.Saved to 1L,
                ChatPicking.Band.Added to 2L,
                ChatPicking.Band.Added to 4L,
                ChatPicking.Band.Recommended to 3L,
                ChatPicking.Band.Chats to 5L,
            ),
            bands(rows),
        )
        assertEquals("each chat is on one row", rows.size, rows.map { it.id }.toSet().size)
    }

    @Test
    fun `a search or a filter has no Added band, the chat sits where it is found`() {
        val searching = ChatPicking.rows(chats = account, added = setOf(2L), query = "harbour", alreadyAdded = setOf(2L))
        assertEquals(listOf(ChatPicking.Band.Chats to 2L), bands(searching))
        val filtered = ChatPicking.rows(chats = account, added = setOf(2L), scope = ChatPicking.Scope.Channels, alreadyAdded = setOf(2L))
        assertTrue(filtered.none { it.band == ChatPicking.Band.Added })
    }

    @Test
    fun `an added chat that is gone keeps its Gone row and one that is not added is untouched`() {
        val rows = ChatPicking.rows(
            chats = account,
            added = setOf(2L, 99L),
            addedNames = listOf(AddedChat(99L, "Left channel")),
            alreadyAdded = setOf(2L, 99L),
            listComplete = true,
        )
        assertEquals(ChatPicking.Band.Gone to 99L, bands(rows).last())
        assertEquals(ChatPicking.Band.Added to 2L, bands(rows)[1])
    }

    @Test
    fun `three thousand chats with a thousand added still list each chat once`() {
        val many = listOf(saved) + (10L..3_009L).map { chat(it) }
        val already = (10L..1_009L).toSet()
        val rows = ChatPicking.rows(chats = many, added = already, recommended = (500L..1_500L).toList(), alreadyAdded = already)
        assertEquals(many.size, rows.size)
        assertEquals(many.size, rows.map { it.id }.toSet().size)
        assertEquals(1_000, rows.count { it.band == ChatPicking.Band.Added })
        assertEquals(491, rows.count { it.band == ChatPicking.Band.Recommended })
    }

    // ---- Telegram folders as a filter (HX29) ------------------------------------------------

    private fun inFolders(chat: ChatSummary, vararg ids: Int) = chat.copy(folderIds = ids.toList())

    private val folded = listOf(
        saved,
        inFolders(chat(2, "Harbour Films"), 7),
        inFolders(chat(3, "Night Lantern Club", ChatKind.Group), 7, 8),
        inFolders(chat(4, "Archived Reels", archived = true), 7),
        chat(5, "Anya", ChatKind.Direct),
    )

    @Test
    fun `a folder lists what Telegram puts in it, the archived chats among them, in one band`() {
        val rows = ChatPicking.rows(folded, emptySet(), recommended = listOf(2L), folder = 7)
        // No Recommended, Starred or Archived band inside a folder: the folder is the question.
        assertEquals(listOf(2L, 3L, 4L), rows.map { it.id })
        assertTrue(rows.all { it.band == ChatPicking.Band.Chats })
    }

    @Test
    fun `a chat in several folders is listed in each, and ticking it is one tick`() {
        val first = ChatPicking.rows(folded, emptySet(), folder = 7).map { it.id }
        val second = ChatPicking.rows(folded, emptySet(), folder = 8).map { it.id }
        assertEquals(listOf(3L), second)
        assertTrue(3L in first)
        val ticked = ChatPicking.selectAll(emptySet(), ChatPicking.visible(ChatPicking.rows(folded, emptySet(), folder = 7)))
        // Taking folder 8 after folder 7 adds nothing for the chat both hold.
        assertEquals(ticked, ChatPicking.selectAll(ticked, ChatPicking.visible(ChatPicking.rows(folded, emptySet(), folder = 8))))
    }

    @Test
    fun `Select all in a folder ticks that folder only, and a search inside it stays inside`() {
        val visible = ChatPicking.visible(ChatPicking.rows(folded, emptySet(), folder = 8))
        assertEquals(setOf(3L), ChatPicking.selectAll(setOf(5L), visible) - setOf(5L))
        val searched = ChatPicking.rows(folded, emptySet(), folder = 7, query = "harbour")
        assertEquals(listOf(2L), searched.map { it.id })
    }

    @Test
    fun `a folder nobody holds is empty, and one never read lists nothing yet`() {
        assertTrue(ChatPicking.rows(folded, emptySet(), folder = 99).isEmpty())
        // Membership not read: no chat knows its folders, so the folder has no rows, which the screen words as reading.
        assertTrue(ChatPicking.rows(account, emptySet(), folder = 7).isEmpty())
    }

    @Test
    fun `Telegram's suggested kind folders are left out as chips, and a custom folder of the same name stays`() {
        val folders = listOf(
            ChatFolderSummary(1, "Channels", icon = "Channels"),
            ChatFolderSummary(2, "Groups", icon = "Groups"),
            ChatFolderSummary(3, "Personal", icon = "Private"),
            ChatFolderSummary(4, "Groups", icon = "Custom"),
            ChatFolderSummary(5, "Unread", icon = "Unread"),
            ChatFolderSummary(4, "Groups again", icon = "Custom"),
        )
        assertEquals(listOf(4, 5), ChatPicking.pickable(folders).map { it.id })
        assertEquals(ChatPicking.Scope.Channels, ChatPicking.scopeOf(folders[0]))
        assertEquals(null, ChatPicking.scopeOf(folders[3]))
    }

    @Test
    fun `three thousand chats in folders are filtered in one pass`() {
        val many = listOf(saved) + (10L..3_009L).map { inFolders(chat(it), (it % 5).toInt()) } +
            (4_000L..5_499L).map { inFolders(chat(it, archived = true), 3, 4) }
        val rows = ChatPicking.rows(many, emptySet(), folder = 3)
        // The ids ending in 3 and 8 are folder 3, and every archived chat is in it.
        assertEquals(600 + 1_500, rows.size)
        assertEquals(rows.size, rows.map { it.id }.toSet().size)
        assertTrue(rows.all { it.band == ChatPicking.Band.Chats })
    }

    // ---- All chats (HX86) -------------------------------------------------------------------

    @Test
    fun `a fresh start ticks Saved Messages alone, and nothing while the list has not brought it`() {
        assertEquals(setOf(1L), ChatPicking.freshTicks(account))
        assertEquals(emptySet<Long>(), ChatPicking.freshTicks(account.drop(1)))
        assertEquals(1L, ChatPicking.savedId(account))
    }

    @Test
    fun `switching all chats off brings the kept ticks back, minus a chat since left`() {
        assertEquals(setOf(2L, 3L), ChatPicking.afterAllOff(setOf(2L, 3L), account, listComplete = true))
        // 99 is not in the account any more: dropped quietly, once the list is whole.
        assertEquals(setOf(2L), ChatPicking.afterAllOff(setOf(2L, 99L), account, listComplete = true))
        // A list still on its way never calls a chat gone.
        assertEquals(setOf(2L, 99L), ChatPicking.afterAllOff(setOf(2L, 99L), account, listComplete = false))
    }

    @Test
    fun `switching all chats off never leaves an empty set, it returns to Saved Messages`() {
        assertEquals(setOf(1L), ChatPicking.afterAllOff(emptySet(), account, listComplete = true))
        assertEquals(setOf(1L), ChatPicking.afterAllOff(setOf(99L), account, listComplete = true))
        // No Saved Messages in the list yet: empty, and the picker's Done stays off until it comes.
        assertEquals(emptySet<Long>(), ChatPicking.afterAllOff(emptySet(), account.drop(1), listComplete = true))
    }
}
