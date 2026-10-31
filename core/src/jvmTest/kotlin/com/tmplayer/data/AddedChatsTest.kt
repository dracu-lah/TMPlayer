package com.tmplayer.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddedChatsTest {

    @After
    fun clear() {
        AddedChats.set(AddedChoice())
        ChatGate.set(null)
    }

    @Test
    fun `stored chats read back in order, with names that held line breaks flattened`() {
        val chats = listOf(AddedChat(-1001234, "Film\tClub\nArchive"), AddedChat(42, "Lectures"))
        val back = AddedChats.decode(AddedChats.encode(chats))
        assertEquals(listOf(AddedChat(-1001234, "Film Club Archive"), AddedChat(42, "Lectures")), back)
    }

    @Test
    fun `lines that do not parse are skipped, and a chat is listed once`() {
        assertEquals(listOf(AddedChat(7, "a")), AddedChats.decode("nonsense\n7\ta\n7\tb\n\n"))
        assertEquals(emptyList<AddedChat>(), AddedChats.decode(null))
    }

    @Test
    fun `nobody has answered yet, so every chat is shown`() {
        assertNull(AddedChoice().ids)
        assertFalse(AddedChoice().answered)
        assertTrue(AddedChats.shows(null, 99))
    }

    @Test
    fun `keep all is an answer, and still shows every chat`() {
        val all = AddedChoice(AddedMode.All)
        assertNull(all.ids)
        assertTrue(all.answered)
    }

    @Test
    fun `an empty choice is an answer, and shows nothing`() {
        val none = AddedChoice(AddedMode.Picked, emptyList())
        assertEquals(emptySet<Long>(), none.ids)
        assertTrue(none.answered)
        assertFalse(AddedChats.shows(none.ids, 5))
    }

    @Test
    fun `the picked chats are the ones shown, in the order they were added`() {
        val choice = AddedChoice(AddedMode.Picked, listOf(AddedChat(3, "c"), AddedChat(1, "a")))
        assertEquals(listOf(3L, 1L), choice.ids?.toList())
        assertTrue(AddedChats.shows(choice.ids, 1))
        assertFalse(AddedChats.shows(choice.ids, 2))
    }

    @Test
    fun `the stored mode reads back, and anything else means nobody answered`() {
        assertEquals(AddedMode.All, AddedChats.modeOf(AddedChats.MODE_ALL))
        assertEquals(AddedMode.Picked, AddedChats.modeOf(AddedChats.MODE_PICKED))
        assertEquals(AddedMode.Unanswered, AddedChats.modeOf(null))
        assertEquals(AddedMode.Unanswered, AddedChats.modeOf(""))
        assertEquals(AddedMode.Unanswered, AddedChats.modeOf("something a bad file held"))
        assertNull(AddedChats.textOf(AddedMode.Unanswered))
        assertEquals(AddedMode.Picked, AddedChats.modeOf(AddedChats.textOf(AddedMode.Picked)))
    }

    @Test
    fun `the gate is the kids lock and the choice met in the middle`() {
        // Neither narrows anything.
        assertNull(AddedChats.gate(null, null))
        // One side only.
        assertEquals(setOf(1L, 2L), AddedChats.gate(setOf(1L, 2L), null))
        assertEquals(setOf(3L), AddedChats.gate(null, setOf(3L)))
        // Both: only the chats in both.
        assertEquals(setOf(2L), AddedChats.gate(setOf(1L, 2L), setOf(2L, 9L)))
        // No overlap is a real answer of nothing, not "show everything".
        assertEquals(emptySet<Long>(), AddedChats.gate(setOf(1L), setOf(2L)))
        // An empty side shows nothing, whichever side it is.
        assertEquals(emptySet<Long>(), AddedChats.gate(emptySet(), setOf(2L)))
        assertEquals(emptySet<Long>(), AddedChats.gate(setOf(2L), emptySet()))
    }

    @Test
    fun `the gate walks the smaller side, and the answer does not depend on which that is`() {
        val many = (1L..3_000L).toSet()
        val few = setOf(2_999L, 3_000L, 7_000L)
        assertEquals(setOf(2_999L, 3_000L), AddedChats.gate(many, few))
        assertEquals(setOf(2_999L, 3_000L), AddedChats.gate(few, many))
    }

    @Test
    fun `adding a chat again brings its name up to date rather than listing it twice`() {
        val list = AddedChats.with(listOf(AddedChat(1, "Old")), AddedChat(1, "New"))
        assertEquals(listOf(AddedChat(1, "New")), list)
        assertEquals(2, AddedChats.with(list, AddedChat(2, "Other")).size)
        assertEquals(listOf(AddedChat(2, "Other")), AddedChats.without(AddedChats.with(list, AddedChat(2, "Other")), 1))
    }

    @Test
    fun `items from chats that were not added are left out`() {
        AddedChats.set(AddedChoice(AddedMode.Picked, listOf(AddedChat(6, "six"))))
        assertEquals(listOf(6L), AddedChats.filter(listOf(5L, 6L, 7L)) { it })
        // Nobody answered: nothing is filtered.
        AddedChats.set(AddedChoice())
        assertEquals(listOf(5L, 6L, 7L), AddedChats.filter(listOf(5L, 6L, 7L)) { it })
    }

    @Test
    fun `the added chats travel in a backup`() {
        val settings = mapOf<String, Any>(
            AddedChats.KEY to AddedChats.encode(listOf(AddedChat(9, "Nine"))),
            AddedChats.MODE_KEY to AddedChats.MODE_PICKED,
        )
        assertTrue(Backups.SETTINGS.containsKey(AddedChats.KEY))
        assertTrue(Backups.SETTINGS.containsKey(AddedChats.MODE_KEY))
        assertEquals(
            listOf(AddedChat(9, "Nine")),
            AddedChats.decode(settings[AddedChats.KEY] as String),
        )
    }
}
