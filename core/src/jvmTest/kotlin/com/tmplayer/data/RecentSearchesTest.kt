package com.tmplayer.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RecentSearchesTest {

    @Test
    fun `newest first, blank ignored`() {
        var list = RecentSearches.add(emptyList(), "severance")
        list = RecentSearches.add(list, "   ")
        list = RecentSearches.add(list, "dark")
        assertEquals(listOf("dark", "severance"), list)
    }

    @Test
    fun `Undo after Clear all puts the list back under searches made since`() {
        val before = listOf("dark", "severance", "lost")
        assertEquals(before, RecentSearches.restore(before, emptyList()))
        // Searched "andor" in the seconds before Undo, and "Dark" again: nothing lost, nothing twice.
        assertEquals(listOf("andor", "Dark", "severance", "lost"), RecentSearches.restore(before, listOf("andor", "Dark")))
        assertEquals(listOf("a", "b"), RecentSearches.restore(listOf("c", "d"), listOf("a", "b"), limit = 2))
    }

    @Test
    fun `the same search again moves to the front instead of repeating`() {
        val list = RecentSearches.add(listOf("dark", "severance", "lost"), "  Severance ")
        assertEquals(listOf("Severance", "dark", "lost"), list)
    }

    @Test
    fun `spacing is tidied before comparing`() {
        assertEquals(listOf("the long road"), RecentSearches.add(listOf("the long road"), "the   long\troad"))
    }

    @Test
    fun `keeps only the last ten`() {
        var list = emptyList<String>()
        val words = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven")
        for (word in words) list = RecentSearches.add(list, word)
        assertEquals(RecentSearches.LIMIT, list.size)
        assertEquals(words.drop(1).reversed(), list)
    }

    @Test
    fun `removes one, whatever its case or spacing`() {
        assertEquals(listOf("dark", "lost"), RecentSearches.remove(listOf("dark", "Harbour Notes", "lost"), " harbour   notes "))
        assertEquals(listOf("dark"), RecentSearches.remove(listOf("dark"), "absent"))
    }

    @Test
    fun `a query typed letter by letter is one entry`() {
        var list = listOf("dark")
        list = RecentSearches.add(list, "sev")
        list = RecentSearches.add(list, "severance")
        assertEquals(listOf("severance", "dark"), list)
        // A backspace on the way to something else is not a search of its own.
        assertEquals(list, RecentSearches.add(list, "seve"))
    }

    @Test
    fun `an older entry that starts the same way is kept`() {
        // Only the newest entry is treated as the one being typed.
        assertEquals(listOf("severance", "dark", "sev"), RecentSearches.add(listOf("dark", "sev"), "severance"))
    }

    @Test
    fun `encode and decode round trip, and decode tolerates junk`() {
        val list = listOf("a b", "c")
        assertEquals(list, RecentSearches.decode(RecentSearches.encode(list)))
        assertEquals(emptyList<String>(), RecentSearches.decode(null))
        assertEquals(listOf("x", "y"), RecentSearches.decode("x\n\n X \ny"))
    }

    @Test
    fun `stored, capped and cleared through SettingsStore`() = runBlocking {
        val dir = Files.createTempDirectory("tm-recent").toFile()
        val settings = SettingsStore(SettingsStore.openDataStore(dir.resolve(SettingsStore.FILE_NAME)))
        assertTrue(settings.recentSearches.first().isEmpty())
        val words = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "two")
        for (word in words) settings.addRecentSearch(word)
        assertEquals(listOf("two", "eleven", "ten", "nine", "eight", "seven", "six", "five", "four", "three"), settings.recentSearches.first())
        settings.removeRecentSearch("ten")
        assertEquals(listOf("two", "eleven", "nine", "eight", "seven", "six", "five", "four", "three"), settings.recentSearches.first())
        settings.clearRecentSearches()
        assertTrue(settings.recentSearches.first().isEmpty())
        // Undo of the Clear all: the list comes back under a search made in between.
        settings.addRecentSearch("fresh")
        settings.restoreRecentSearches(listOf("two", "eleven"))
        assertEquals(listOf("fresh", "two", "eleven"), settings.recentSearches.first())
    }

    @Test
    fun `signing out forgets them`() = runBlocking {
        val dir = Files.createTempDirectory("tm-recent-out").toFile()
        val settings = SettingsStore(SettingsStore.openDataStore(dir.resolve(SettingsStore.FILE_NAME)))
        settings.addRecentSearch("private")
        settings.clearEverything()
        assertTrue(settings.recentSearches.first().isEmpty())
    }
}
