package com.tmplayer.ui.browse

import com.tmplayer.data.ChatFolderSummary
import com.tmplayer.data.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class NavGroupsTest {

    private fun tab(tab: BrowseTab) = NavEntry.Section(BrowseSection.of(tab))

    @Test
    fun `every tab sits in the group the plan gives it`() {
        val watch = listOf(BrowseTab.Continue, BrowseTab.Watched, BrowseTab.Favorites)
        for (entry in BrowseTab.entries) {
            val expected = if (entry in watch) NavGroup.Watch else NavGroup.Chats
            assertEquals(entry.name, expected, navGroupOf(BrowseSection.of(entry)))
        }
        assertEquals(NavGroup.Folders, navGroupOf(BrowseSection.Folder(3, "Films")))
        assertEquals(NavGroup.Watch, navGroupOf(NavEntry.Downloads))
    }

    @Test
    fun `groups in order, Downloads closing Watch, Recent among the chats`() {
        val groups = navGroups(browseSections(emptyList(), withWatched = true))
        assertEquals(listOf(NavGroup.Watch, NavGroup.Chats), groups.map { it.group })
        assertEquals(
            listOf(tab(BrowseTab.Continue), tab(BrowseTab.Watched), tab(BrowseTab.Favorites), NavEntry.Downloads),
            groups[0].entries,
        )
        assertEquals(
            listOf(
                BrowseTab.Recent, BrowseTab.Unread, BrowseTab.Saved, BrowseTab.Channels,
                BrowseTab.Groups, BrowseTab.People, BrowseTab.All, BrowseTab.Archived,
            ).map(::tab),
            groups[1].entries,
        )
    }

    @Test
    fun `Folders only when the account has some, and every section appears exactly once`() {
        val sections = browseSections(listOf(ChatFolderSummary(3, "Films"), ChatFolderSummary(7, "Family")), withWatched = true)
        val groups = navGroups(sections)
        assertEquals(listOf(NavGroup.Watch, NavGroup.Chats, NavGroup.Folders), groups.map { it.group })
        assertEquals(
            listOf(BrowseSection.Folder(3, "Films"), BrowseSection.Folder(7, "Family")).map { NavEntry.Section(it) },
            groups[2].entries,
        )
        val listed = groups.flatMap { it.entries }.filterIsInstance<NavEntry.Section>().map { it.section }
        assertEquals(sections.toSet(), listed.toSet())
        assertEquals(sections.size, listed.size)
    }

    @Test
    fun `Downloads can be left out`() {
        val groups = navGroups(browseSections(emptyList()), withDownloads = false)
        assertFalse(NavEntry.Downloads in groups[0].entries)
        // Without the Watched tab the group still holds what there is.
        assertEquals(listOf(tab(BrowseTab.Continue), tab(BrowseTab.Favorites)), groups[0].entries)
    }

    @Test
    fun `Watch starts open and the rest closed`() {
        val open = NavGroupState.decode(null)
        assertEquals(setOf(NavGroup.Watch), open)
        assertTrue(NavGroupState.isOpen(NavGroup.Watch, open, current = null))
        assertFalse(NavGroupState.isOpen(NavGroup.Chats, open, current = null))
        assertFalse(NavGroupState.isOpen(NavGroup.Folders, open, current = null))
    }

    @Test
    fun `the current group is always open and cannot be folded`() {
        val allClosed = emptySet<NavGroup>()
        for (group in NavGroup.entries) {
            assertTrue(NavGroupState.isOpen(group, allClosed, current = group))
            assertFalse(NavGroupState.canToggle(group, current = group))
        }
        assertTrue(NavGroupState.canToggle(NavGroup.Watch, current = NavGroup.Chats))
        assertFalse(NavGroupState.isOpen(NavGroup.Watch, allClosed, current = NavGroup.Chats))
    }

    @Test
    fun `decode tolerates keys it does not know, and an empty set means all closed`() {
        assertEquals(setOf(NavGroup.Chats), NavGroupState.decode(setOf("chats", "gone")))
        assertEquals(emptySet<NavGroup>(), NavGroupState.decode(emptySet()))
        assertEquals(setOf("watch", "folders"), NavGroupState.encode(setOf(NavGroup.Watch, NavGroup.Folders)))
    }

    @Test
    fun `open state is remembered in SettingsStore`() = runBlocking {
        val file = Files.createTempDirectory("tm-navgroups").resolve(SettingsStore.FILE_NAME).toFile()
        val settings = SettingsStore(SettingsStore.openDataStore(file))
        assertNull(settings.navGroupsOpen.first())

        toggleNavGroup(settings, NavGroup.Chats)
        assertEquals(setOf(NavGroup.Watch, NavGroup.Chats), NavGroupState.decode(settings.navGroupsOpen.first()))
        toggleNavGroup(settings, NavGroup.Watch)
        assertEquals(setOf(NavGroup.Chats), NavGroupState.decode(settings.navGroupsOpen.first()))

        // Closing the last open group is stored as an empty set, not mistaken for "never set".
        toggleNavGroup(settings, NavGroup.Chats)
        assertEquals(emptySet<NavGroup>(), NavGroupState.decode(settings.navGroupsOpen.first()))
    }
}
