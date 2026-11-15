package com.tmplayer.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** The two switches of EG-F30 and EG-F52: off by default, shared by the store and the book, kept in a backup. */
class ChatOrderSettingsTest {

    @Test
    fun `both are off until turned on, and the store and the book read each other`() = runBlocking {
        val dir = Files.createTempDirectory("tm-chat-order").toFile()
        val file = WatchedStore.openDataStore(dir.resolve("settings.preferences_pb"))
        val store = SettingsStore(file)
        val book = SettingsBook(DataStoreKeyValueStore(file))
        assertFalse(store.playChatOrderNow())
        assertFalse(store.keepRepeatNow())
        assertFalse(book.playChatOrder.first())
        store.setPlayChatOrder(true)
        assertTrue(book.playChatOrder.first())
        book.setKeepRepeat(true)
        assertTrue(store.keepRepeatNow())
        assertTrue(store.keepRepeat.first())
        store.setPlayChatOrder(false)
        assertEquals(false, book.playChatOrder.first())
    }

    @Test
    fun `both travel in a backup as switches`() {
        assertEquals(Backups.Kind.BOOL, Backups.SETTINGS["play_chat_order"])
        assertEquals(Backups.Kind.BOOL, Backups.SETTINGS["keep_repeat"])
    }
}
