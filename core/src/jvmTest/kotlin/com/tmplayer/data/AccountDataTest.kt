package com.tmplayer.data

import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Each profile's data stays its own (HX88): favourites, resume places, the chats picked and the
 * All chats switch, the watched list; the device's settings are everyone's; the adopted profile's
 * keys keep their old names, so an older version still reads them.
 */
class AccountDataTest {

    private val dir: File = Files.createTempDirectory("account-data").toFile()
    private val folder = MutableStateFlow<String?>(null)
    private val raw = openSafeDataStore(File(dir, SettingsStore.FILE_NAME))
    private val settings = SettingsStore(AccountScopedDataStore(raw, folder))
    private val watched = WatchedStore(
        AccountScopedDataStore(openSafeDataStore(File(dir, WatchedStore.FILE_NAME)), folder, AccountScopedDataStore.ALL_ACCOUNT),
    )

    private suspend fun names(): Set<String> = raw.data.first().asMap().keys.map(Preferences.Key<*>::name).toSet()

    private fun record(chat: Long, message: Long) =
        WatchedRecord(chat, message, 1, "Clip", "Chat", 10L, 60, 1_000L, manual = true)

    @Test
    fun `favourites, resume places and the chats picked do not leak between two profiles`() = runBlocking {
        settings.toggleFavorite(100L)
        settings.saveResumePosition(100L, 5L, positionMs = 600_000L, durationMs = 3_600_000L)
        settings.setAddedChoice(AddedChoice(AddedMode.All, listOf(AddedChat(100L, "Films"))))
        watched.markWatched(record(100L, 6L))

        folder.value = "p2"
        assertEquals(emptySet<Long>(), settings.favorites.first())
        assertEquals(0L, settings.resumePosition(100L, 5L))
        assertEquals("never asked on this profile", AddedChats.modeOf(null), settings.addedChats.first().mode)
        assertTrue(watched.watched.first().isEmpty())

        settings.toggleFavorite(200L)
        settings.setAddedChoice(AddedChoice(AddedMode.Picked, listOf(AddedChat(200L, "Talks"))))
        watched.markWatched(record(200L, 7L))
        assertEquals(setOf(200L), settings.favorites.first())

        folder.value = null
        assertEquals(setOf(100L), settings.favorites.first())
        assertEquals(600_000L, settings.resumePosition(100L, 5L))
        val first = settings.addedChats.first()
        assertEquals(AddedMode.All, first.mode)
        assertEquals("the kept picks under All chats", listOf(AddedChat(100L, "Films")), first.chats)
        assertEquals(setOf(ProgressKeys.of(100L, 6L)), watched.watched.first().keys)

        folder.value = "p2"
        assertEquals(listOf(AddedChat(200L, "Talks")), settings.addedChats.first().chats)
        assertEquals(setOf(ProgressKeys.of(200L, 7L)), watched.watched.first().keys)
    }

    @Test
    fun `the device's settings are shared and the adopted profile keeps the old key names`() = runBlocking {
        settings.setThemeChoice(ThemeChoice.Light)
        settings.toggleFavorite(1L)
        folder.value = "p2"
        assertEquals(ThemeChoice.Light, settings.themeChoice.first())
        settings.setThemeChoice(ThemeChoice.Dark)
        settings.toggleFavorite(2L)
        folder.value = null
        assertEquals(ThemeChoice.Dark, settings.themeChoice.first())
        val stored = names()
        assertTrue("the adopted profile's own name", "favorite_chats" in stored)
        assertTrue("the other profile's, marked", "@p2:favorite_chats" in stored)
        assertFalse("never a marked device key", stored.any { it.startsWith("@p2:theme") })
    }

    @Test
    fun `signing out of one profile clears its data only, and keeps the device's`() = runBlocking {
        settings.setThemeChoice(ThemeChoice.Light)
        settings.toggleFavorite(1L)
        folder.value = "p2"
        settings.toggleFavorite(2L)
        settings.saveResumePosition(9L, 9L, positionMs = 600_000L, durationMs = 3_600_000L)
        settings.clearAccountData()
        watched.clear()
        assertTrue(settings.favorites.first().isEmpty())
        assertEquals(0L, settings.resumePosition(9L, 9L))
        assertEquals(ThemeChoice.Light, settings.themeChoice.first())
        folder.value = null
        assertEquals(setOf(1L), settings.favorites.first())
        assertFalse(names().any { it.startsWith("@p2:favorite") || it.startsWith("@p2:resume_") })
    }

    @Test
    fun `with the adopted profile alone the store passes through untouched`() = runBlocking {
        settings.toggleFavorite(3L)
        val plain = raw.data.first()
        val view = AccountViews.view(plain, null, AccountKeys::isAccount)
        assertTrue(view === plain)
    }
}
