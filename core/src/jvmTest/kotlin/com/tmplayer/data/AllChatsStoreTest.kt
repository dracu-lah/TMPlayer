package com.tmplayer.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

/**
 * The "All chats" switch as the store keeps it (HX86): on is the stored mode `all` with the ticked
 * chats still beside it, so switching it off brings them back; a sign out wipes both, since the
 * ticks belong to the account; and a chat joined while it is on needs nothing stored.
 */
class AllChatsStoreTest {

    private val dir = Files.createTempDirectory("tm-allchats").toFile()
    private val settings = SettingsStore(SettingsStore.openDataStore(dir.resolve(SettingsStore.FILE_NAME)))
    private val kept = listOf(AddedChat(-100L, "Show"), AddedChat(42L, "Clips"))

    @Test
    fun `all chats on keeps the ticks beside it and shows every chat`() = runBlocking {
        settings.setAddedChoice(AddedChoice(AddedMode.All, kept))
        val now = settings.addedChats.first()
        assertEquals(AddedMode.All, now.mode)
        assertEquals(kept, now.chats)
        // The gate reads null as every chat, whatever is kept: a chat joined later is in at once.
        assertNull(now.ids)
        assertNull(AddedChats.gate(null, now.ids))
        assertEquals(true, AddedChats.shows(now.ids, 1_234_567L))
    }

    @Test
    fun `off again gives the kept ticks back as the pick, in their order`() = runBlocking {
        settings.setAddedChoice(AddedChoice(AddedMode.All, kept))
        val back = settings.addedChats.first().chats
        settings.setAddedChoice(AddedChoice(AddedMode.Picked, back))
        val now = settings.addedChats.first()
        assertEquals(AddedMode.Picked, now.mode)
        assertEquals(setOf(-100L, 42L), now.ids)
        assertEquals(kept, now.chats)
    }

    @Test
    fun `on and off in a row writes the last state, once and whole`() = runBlocking {
        settings.setAddedChoice(AddedChoice(AddedMode.All, kept))
        settings.setAddedChoice(AddedChoice(AddedMode.Picked, kept))
        settings.setAddedChoice(AddedChoice(AddedMode.All, kept))
        assertEquals(AddedChoice(AddedMode.All, kept), settings.addedChats.first())
    }

    private fun chat(id: Long, kind: ChatKind = ChatKind.Channel) = ChatSummary(
        id = id,
        title = "Chat $id",
        miniThumbnail = null,
        photoFileId = 0,
        kind = kind,
    )

    private val account = listOf(chat(1, ChatKind.Saved), chat(-100L), chat(42L), chat(7L))

    @Test
    fun `a chat menu offers Remove only while the switch is off`() {
        assertEquals(false, AddedChats.removable(AddedChoice(AddedMode.All, kept)))
        assertEquals(true, AddedChats.removable(AddedChoice(AddedMode.Picked, kept)))
        assertEquals(true, AddedChats.removable(AddedChoice()))
    }

    @Test
    fun `removing a chat while the switch is on leaves the store as it was`() = runBlocking {
        settings.setAddedChoice(AddedChoice(AddedMode.All, kept))
        settings.removeChat(7L, account.map { AddedChat(it.id, it.title) })
        assertEquals(AddedChoice(AddedMode.All, kept), settings.addedChats.first())
    }

    @Test
    fun `turning the switch off from a chat menu brings the kept picks back and Remove returns`() = runBlocking {
        settings.setAddedChoice(AddedChoice(AddedMode.All, kept))
        settings.turnOffAllChats(account, listComplete = true)
        val now = settings.addedChats.first()
        assertEquals(AddedMode.Picked, now.mode)
        // The kept picks, in their order, named as the live list names them.
        assertEquals(listOf(-100L, 42L), now.chats.map { it.id })
        assertEquals(true, AddedChats.removable(now))
        // A second press, or the picker having turned it off already, writes nothing.
        settings.turnOffAllChats(account, listComplete = true)
        assertEquals(now, settings.addedChats.first())
    }

    @Test
    fun `turning it off with nothing kept, or nothing left of it, never gives an empty set`() = runBlocking {
        settings.setAddedChoice(AddedChoice(AddedMode.All, emptyList()))
        settings.turnOffAllChats(account, listComplete = true)
        assertEquals(setOf(1L), settings.addedChats.first().ids)
        // A kept chat the viewer has since left is dropped once the list is whole, not before.
        settings.setAddedChoice(AddedChoice(AddedMode.All, listOf(AddedChat(99L, "Left"))))
        assertEquals(setOf(99L), AddedChats.turningOffAll(AddedChoice(AddedMode.All, listOf(AddedChat(99L, "Left"))), account, listComplete = false).ids)
        settings.turnOffAllChats(account, listComplete = true)
        assertEquals(setOf(1L), settings.addedChats.first().ids)
    }

    @Test
    fun `turning it off when it is not on changes nothing`() {
        val picked = AddedChoice(AddedMode.Picked, kept)
        assertEquals(picked, AddedChats.turningOffAll(picked, account, listComplete = true))
        assertEquals(AddedChoice(), AddedChats.turningOffAll(AddedChoice(), account, listComplete = true))
    }

    @Test
    fun `a sign out wipes the switch and the kept ticks with the rest of the account`() = runBlocking {
        settings.setAddedChoice(AddedChoice(AddedMode.All, kept))
        settings.clearEverything()
        val now = settings.addedChats.first()
        assertEquals(AddedMode.Unanswered, now.mode)
        assertEquals(emptyList<AddedChat>(), now.chats)
    }

    @Test
    fun `an answer stored before the switch reads as it did, every chat with no ticks kept`() = runBlocking {
        settings.keepAllChats()
        val now = settings.addedChats.first()
        assertEquals(AddedMode.All, now.mode)
        assertEquals(emptyList<AddedChat>(), now.chats)
    }
}
