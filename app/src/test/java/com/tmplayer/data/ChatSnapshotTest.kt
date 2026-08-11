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
    fun `the snapshot is capped so a sync is not a large write`() {
        val many = (1..ChatSnapshot.MAX_ENTRIES * 2).map { chat(it.toLong(), "Chat $it") }
        val decoded = ChatSnapshot.decode(ChatSnapshot.encode(many))
        assertEquals(ChatSnapshot.MAX_ENTRIES, decoded.size)
        // The cap takes from the front, which is the order Telegram put them in.
        assertTrue(decoded.first().title == "Chat 1")
    }
}
