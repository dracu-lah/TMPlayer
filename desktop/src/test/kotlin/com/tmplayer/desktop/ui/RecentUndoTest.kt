package com.tmplayer.desktop.ui

import com.tmplayer.data.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Clear on the chat search's recent searches has an Undo toast ([clearRecentWithUndo]): the list
 * goes at once, the toast says so and offers it back, Undo restores it under any search made since,
 * and Clear on a list that is already empty (a second press) says nothing.
 */
class RecentUndoTest {

    private val dir = Files.createTempDirectory("tm-recent-undo").toFile()
    private val settings = SettingsStore(SettingsStore.openDataStore(dir.resolve(SettingsStore.FILE_NAME)))
    private val toasts = java.util.concurrent.CopyOnWriteArrayList<Triple<String, String, () -> Unit>>()
    private val undoToast: (String, String, () -> Unit) -> Unit = { m, a, f -> toasts += Triple(m, a, f) }

    private fun <T> eventually(block: suspend () -> T?): T = runBlocking {
        withTimeout(5_000) {
            var found = block()
            while (found == null) {
                kotlinx.coroutines.delay(20)
                found = block()
            }
            found
        }
    }

    private suspend fun recents() = settings.recentSearches.first()

    @Test
    fun `Clear all empties the list and the toast brings it back`() {
        runBlocking { listOf("lost", "severance", "dark").forEach { settings.addRecentSearch(it) } }
        clearRecentWithUndo(settings, undoToast, "Recent searches cleared", "Undo")
        eventually { toasts.firstOrNull() }
        assertEquals("Recent searches cleared", toasts[0].first)
        assertEquals("Undo", toasts[0].second)
        assertTrue(runBlocking { recents() }.isEmpty())
        toasts[0].third()
        assertEquals(listOf("dark", "severance", "lost"), eventually { recents().takeIf { it.isNotEmpty() } })
    }

    @Test
    fun `Undo keeps a search made after the clear`() {
        runBlocking { listOf("lost", "dark").forEach { settings.addRecentSearch(it) } }
        clearRecentWithUndo(settings, undoToast, "cleared", "Undo")
        eventually { toasts.firstOrNull() }
        runBlocking { settings.addRecentSearch("andor") }
        toasts[0].third()
        assertEquals(listOf("andor", "dark", "lost"), eventually { recents().takeIf { it.size == 3 } })
    }

    @Test
    fun `Clear on an empty list says nothing, and a second press does not stack a second toast`() {
        clearRecentWithUndo(settings, undoToast, "cleared", "Undo")
        Thread.sleep(300)
        assertTrue(toasts.isEmpty())
        runBlocking { settings.addRecentSearch("dark") }
        clearRecentWithUndo(settings, undoToast, "cleared", "Undo")
        eventually { toasts.firstOrNull() }
        clearRecentWithUndo(settings, undoToast, "cleared", "Undo")
        Thread.sleep(300)
        assertEquals(1, toasts.size)
    }
}
