package com.tmplayer.data

import com.tmplayer.online.MetaSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * The retired hidden chats folded once into the added chats (2026-11-01), and the chat menu's
 * "Remove from TMPlayer", which unticks a chat in that same list.
 */
class HiddenChatsMigrationTest {

    private val dir = Files.createTempDirectory("tm-hidden").toFile()

    private val everything = listOf(AddedChat(1, "One"), AddedChat(2, "Two"), AddedChat(3, "Three"))

    /** The hidden chats as the old setting stored them: a line per chat, the id, a tab, the name. */
    private val oldText = "2\tTwo\n99\tLeft long ago\n"

    @Test
    fun `old text reads back to its ids, and lines that do not parse are skipped`() {
        assertEquals(setOf(2L, 99L), AddedChats.hiddenIds(oldText))
        assertEquals(setOf(7L), AddedChats.hiddenIds("nonsense\n7\ta\n7\tb\n\n"))
        assertTrue(AddedChats.hiddenIds(null).isEmpty())
    }

    @Test
    fun `under every chat the fold keeps every chat that was not hidden`() {
        for (mode in listOf(AddedMode.Unanswered, AddedMode.All)) {
            val folded = AddedChats.withoutHidden(AddedChoice(mode), setOf(2L, 99L), everything)
            assertEquals(AddedMode.Picked, folded?.mode)
            assertEquals(setOf(1L, 3L), folded?.ids)
        }
    }

    @Test
    fun `under a pick the hidden chats leave it`() {
        val picked = AddedChoice(AddedMode.Picked, listOf(AddedChat(2, "Two"), AddedChat(3, "Three")))
        assertEquals(setOf(3L), AddedChats.withoutHidden(picked, setOf(2L), everything)?.ids)
    }

    @Test
    fun `the fold waits for a chat list, and nothing hidden changes nothing`() {
        assertNull(AddedChats.withoutHidden(AddedChoice(), setOf(2L), emptyList()))
        val all = AddedChoice(AddedMode.All)
        assertSame(all, AddedChats.withoutHidden(all, emptySet(), everything))
    }

    @Test
    fun `removing a chat from its menu unticks it in the one list`() {
        val picked = AddedChoice(AddedMode.Picked, everything)
        assertEquals(setOf(1L, 3L), AddedChats.removing(picked, 2, emptyList()).ids)
        // Every chat: the rest of the list becomes the pick, so only this one goes.
        val fromAll = AddedChats.removing(AddedChoice(AddedMode.All), 2, everything)
        assertEquals(AddedMode.Picked, fromAll.mode)
        assertEquals(setOf(1L, 3L), fromAll.ids)
        // No chat list to keep: left alone rather than emptying the library over one chat.
        assertEquals(AddedChoice(), AddedChats.removing(AddedChoice(), 2, emptyList()))
        assertEquals(AddedChoice(), AddedChats.removing(AddedChoice(), 2, listOf(AddedChat(2, "Two"))))
    }

    @Test
    fun `the store folds the old setting once and deletes it`() = runBlocking {
        val file = WatchedStore.openDataStore(dir.resolve("settings.preferences_pb"))
        val store = SettingsStore(file)
        val book = SettingsBook(DataStoreKeyValueStore(file))
        DataStoreKeyValueStore(file).edit { it[AddedChats.HIDDEN_KEY] = oldText }

        // No chat list yet: nothing changes and the setting waits.
        store.migrateHiddenChats(emptyList())
        assertEquals(AddedMode.Unanswered, store.addedChats.first().mode)
        assertEquals(oldText, DataStoreKeyValueStore(file).snapshot()[AddedChats.HIDDEN_KEY])

        store.migrateHiddenChats(everything)
        assertEquals(setOf(1L, 3L), store.addedChats.first().ids)
        assertNull(DataStoreKeyValueStore(file).snapshot()[AddedChats.HIDDEN_KEY])

        // The browser's book folds the same way.
        DataStoreKeyValueStore(file).edit { it[AddedChats.HIDDEN_KEY] = "3\tThree" }
        book.migrateHiddenChats(everything)
        assertEquals(setOf(1L), book.addedChats.first().ids)
        assertNull(DataStoreKeyValueStore(file).snapshot()[AddedChats.HIDDEN_KEY])

        // Remove from TMPlayer, through the store, under every chat.
        store.keepAllChats()
        store.removeChat(1, everything)
        assertEquals(setOf(2L, 3L), store.addedChats.first().ids)
    }

    @Test
    fun `an older backup still carries the hidden chats in, to be folded on this device`() {
        val settings = mapOf<String, Any>(AddedChats.HIDDEN_KEY to oldText)
        val backup = Backups.collect(settings, emptyMap(), MetaSettings(), createdAt = 0, appVersion = "test")
        assertEquals(setOf(2L, 99L), AddedChats.hiddenIds(backup.settings[AddedChats.HIDDEN_KEY] as String))
    }
}
