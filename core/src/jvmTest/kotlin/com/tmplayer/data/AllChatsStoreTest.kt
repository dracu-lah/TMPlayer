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
