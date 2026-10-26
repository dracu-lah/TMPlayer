package com.tmplayer.data

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The settings, watched and bookmark files cut short or written over with rubbish (a full disk,
 * a power cut, an old backup) start again empty instead of throwing at every read, which on
 * Android was a crash at every launch (HX10).
 */
class SafeDataStoreTest {

    @Test
    fun aCorruptSettingsFileStartsAgainEmpty() = runBlocking<Unit> {
        val file = Files.createTempDirectory("tm-safe").resolve(SettingsStore.FILE_NAME).toFile()
        file.writeBytes(byteArrayOf(0x0A, 0x7F, -1, -1, -1, -1, 0x12, 0x00, 0x33))
        val store = SettingsStore.openDataStore(file)
        assertTrue(store.data.first().asMap().isEmpty())
        // And it is usable afterwards: a write lands and reads back.
        val key = stringPreferencesKey("probe")
        store.edit { it[key] = "kept" }
        assertEquals("kept", store.data.first()[key])
        // The store's own readers work over it too.
        SettingsStore(store).language.first()
    }

    @Test
    fun theOtherStoresRecoverTheSameWay() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("tm-safe-other").toFile()
        val watched = dir.resolve(WatchedStore.FILE_NAME).apply { writeText("not a protobuf at all") }
        val bookmarks = dir.resolve(BookmarkStore.FILE_NAME).apply { writeBytes(ByteArray(64) { -1 }) }
        assertTrue(WatchedStore.openDataStore(watched).data.first().asMap().isEmpty())
        assertTrue(BookmarkStore.openDataStore(bookmarks).data.first().asMap().isEmpty())
    }

    @Test
    fun aWriteThatCannotReachTheDiskIsPassedOver() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("tm-safe-ro").toFile()
        // The store's file sits under a path that is a file, so no folder can be made for it.
        val blocker = dir.resolve("blocker").apply { writeText("x") }
        val store = SettingsStore.openDataStore(blocker.resolve(SettingsStore.FILE_NAME))
        store.edit { it[stringPreferencesKey("probe")] = "lost" }
        store.data.first()
    }
}
