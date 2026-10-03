package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSnapshotTest {

    private fun chat(id: Long, title: String, kind: ChatKind = ChatKind.Channel) =
        ChatSummary(id = id, title = title, miniThumbnail = null, photoFileId = 7, kind = kind)

    @Test
    fun `a list survives a round trip`() {
        val chats = listOf(
            chat(1, "Films"),
            chat(-100, "The Group", ChatKind.Group),
            chat(42, "A friend", ChatKind.Direct),
        )
        assertEquals(chats, ChatSnapshot.decode(ChatSnapshot.encode(chats)))
    }

    @Test
    fun `a title carrying the separator comes back whole`() {
        val chats = listOf(chat(1, "Films | 4K | remuxes"))
        assertEquals(chats, ChatSnapshot.decode(ChatSnapshot.encode(chats)))
    }

    @Test
    fun `the blurred preview is deliberately not carried`() {
        val withArt = chat(1, "Films").copy(miniThumbnail = byteArrayOf(1, 2, 3))
        assertEquals(null, ChatSnapshot.decode(ChatSnapshot.encode(listOf(withArt))).first().miniThumbnail)
    }

    @Test
    fun `nothing stored and nothing malformed decodes to nothing`() {
        assertEquals(emptyList<ChatSummary>(), ChatSnapshot.decode(null))
        assertEquals(emptyList<ChatSummary>(), ChatSnapshot.decode(""))
        assertEquals(emptyList<ChatSummary>(), ChatSnapshot.decode("nonsense"))
        assertEquals(emptyList<ChatSummary>(), ChatSnapshot.decode("1|NotAKind|0|Films"))
        assertEquals(emptyList<ChatSummary>(), ChatSnapshot.decode("notanid|Channel|0|Films"))
    }

    @Test
    fun `pins, folders and unread counts survive a round trip`() {
        val chats = listOf(
            chat(1, "Films").copy(
                isPinned = true,
                unreadCount = 12,
                isMuted = true,
                folderIds = listOf(2, 5),
            ),
            chat(2, "Archive fodder").copy(isArchived = true),
        )
        assertEquals(chats, ChatSnapshot.decode(ChatSnapshot.encode(chats)))
    }

    @Test
    fun `a snapshot written by an older build is still readable`() {
        // The four-field form, which is what is sitting on every device that has the previous
        // release on it. Read as a chat with no pin, no archive and no folders, so the first
        // launch after an update still opens on a list rather than on an empty screen.
        val decoded = ChatSnapshot.decode("1|Channel|7|Films")
        assertEquals(listOf(chat(1, "Films")), decoded)
    }

    @Test
    fun `an older title carrying the separator survives too`() {
        assertEquals(
            listOf(chat(1, "Films | 4K | remuxes")),
            ChatSnapshot.decode("1|Channel|7|Films | 4K | remuxes"),
        )
    }

    @Test
    fun `Saved Messages goes first, then the pins, then everything else`() {
        val saved = chat(9, "Saved Messages", ChatKind.Saved)
        val pinned = chat(2, "Pinned").copy(isPinned = true)
        val ordinary = chat(3, "Ordinary")
        val alsoPinned = chat(4, "Also pinned").copy(isPinned = true)
        assertEquals(
            listOf(saved, pinned, alsoPinned, ordinary),
            arrangeChats(listOf(ordinary, pinned, saved, alsoPinned)),
        )
    }

    @Test
    fun `arranging leaves the order Telegram gave within each group alone`() {
        val chats = (1..5).map { chat(it.toLong(), "Chat $it") }
        assertEquals(chats, arrangeChats(chats))
    }

    @Test
    fun `the snapshot is capped so a sync is not a large write`() {
        val many = (1..ChatSnapshot.MAX_ENTRIES * 2).map { chat(it.toLong(), "Chat $it") }
        val decoded = ChatSnapshot.decode(ChatSnapshot.encode(many))
        assertEquals(ChatSnapshot.MAX_ENTRIES, decoded.size)
        // The cap takes from the front, which is the order Telegram put them in.
        assertTrue(decoded.first().title == "Chat 1")
    }
}
