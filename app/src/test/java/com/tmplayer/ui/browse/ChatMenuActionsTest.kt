package com.tmplayer.ui.browse

import com.tmplayer.data.AddedChat
import com.tmplayer.data.AddedChoice
import com.tmplayer.data.AddedMode
import com.tmplayer.i18n.L
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The chat menu's Remove entry on the phone, the tablet and the television (HX86): with "All chats"
 * on it is replaced by "Turn off All chats", which explains why, so the choice (strictly every chat or
 * the viewer's own picks) is never quietly turned into a pick. One entry in the same slot, so the
 * remote's order (Open, Star, this) does not change with the switch.
 */
class ChatMenuActionsTest {

    private val picks = listOf(AddedChat(1L, "One"))

    private fun action(choice: AddedChoice, removed: MutableList<String>) =
        chatRemoveAction(L, choice, onRemove = { removed += "remove" }, onTurnOffAllChats = { removed += "off" })

    @Test
    fun `with picks the entry removes the chat`() {
        val done = mutableListOf<String>()
        val entry = action(AddedChoice(AddedMode.Picked, picks), done)
        assertEquals(L.browseRemoveChat, entry.label)
        entry.onSelect()
        assertEquals(listOf("remove"), done)
    }

    @Test
    fun `with All chats on the entry explains it and turns the switch off, never removing`() {
        val done = mutableListOf<String>()
        val entry = action(AddedChoice(AddedMode.All, picks), done)
        assertEquals(L.browseTurnOffAllChats, entry.label)
        assertEquals(L.browseTurnOffAllChatsDetail, entry.detail)
        assertNotEquals(L.browseRemoveChat, entry.label)
        entry.onSelect()
        assertEquals(listOf("off"), done)
    }

    @Test
    fun `before anything is answered Remove is still offered, as it was`() {
        val done = mutableListOf<String>()
        assertEquals(L.browseRemoveChat, action(AddedChoice(), done).label)
    }
}
