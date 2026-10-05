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
    fun `the same search again moves to the front instead of repeating`() {
        val list = RecentSearches.add(listOf("dark", "severance", "lost"), "  Severance ")
        assertEquals(listOf("Severance", "dark", "lost"), list)
    }

    @Test
    fun `spacing is tidied before comparing`() {
        assertEquals(listOf("the long road"), RecentSearches.add(listOf("the long road"), "the   long\troad"))
    }

    @Test
    fun `keeps only the last five`() {
        var list = emptyList<String>()
        for (word in listOf("one", "two", "three", "four", "five", "six")) list = RecentSearches.add(list, word)
        assertEquals(RecentSearches.LIMIT, list.size)
        assertEquals(listOf("six", "five", "four", "three", "two"), list)
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
        for (word in listOf("one", "two", "three", "four", "five", "six", "two")) settings.addRecentSearch(word)
        assertEquals(listOf("two", "six", "five", "four", "three"), settings.recentSearches.first())
        settings.clearRecentSearches()
        assertTrue(settings.recentSearches.first().isEmpty())
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
