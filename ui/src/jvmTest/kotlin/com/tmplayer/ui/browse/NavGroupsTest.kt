package com.tmplayer.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavGroupsTest {

    private fun tab(tab: BrowseTab) = NavEntry.Section(BrowseSection.of(tab))

    @Test
    fun `every section sits in the one group`() {
        for (entry in BrowseTab.entries) assertEquals(entry.name, NavGroup.Watch, navGroupOf(BrowseSection.of(entry)))
        assertEquals(NavGroup.Watch, navGroupOf(NavEntry.Downloads))
    }

    @Test
    fun `five entries, Home, History, Favourites, Chats and Downloads, and no Saved Messages`() {
        val groups = navGroups(browseSections())
        assertEquals(listOf(NavGroup.Watch), groups.map { it.group })
        assertEquals(
            listOf(tab(BrowseTab.Home), tab(BrowseTab.History), tab(BrowseTab.Favorites), tab(BrowseTab.Chats), NavEntry.Downloads),
            groups.single().entries,
        )
    }

    @Test
    fun `the badge counts favourites on Favourites, and nothing elsewhere`() {
        assertEquals("3", navBadge(BrowseSection.of(BrowseTab.Favorites), favoriteCount = 3))
        // Chats never carries an unread count.
        assertEquals(null, navBadge(BrowseSection.of(BrowseTab.Chats), favoriteCount = 3))
        assertEquals(null, navBadge(BrowseSection.of(BrowseTab.Saved), favoriteCount = 3))
    }

    @Test
    fun `folders are neither sidebar entries nor chips of the Chats page, they filter Add chats (HX29)`() {
        val listed = navGroups(browseSections()).flatMap { it.entries }.filterIsInstance<NavEntry.Section>().map { it.section }
        assertEquals(listOf(BrowseTab.Home, BrowseTab.History, BrowseTab.Favorites, BrowseTab.Chats).map { BrowseSection.of(it) }, listed)
        // The page's chips are TMPlayer's own filters, "All chats" first.
        assertEquals("All chats", ChatFilter.All.chipLabel)
        assertEquals(ChatFilter.Unread.label, ChatFilter.Unread.chipLabel)
    }

    @Test
    fun `Downloads can be left out`() {
        val groups = navGroups(browseSections(), withDownloads = false)
        assertFalse(NavEntry.Downloads in groups[0].entries)
        assertEquals(listOf(tab(BrowseTab.Home), tab(BrowseTab.History), tab(BrowseTab.Favorites), tab(BrowseTab.Chats)), groups[0].entries)
    }

    @Test
    fun `Watch starts open and the rest closed`() {
        NavGroupState.reset()
        val open = NavGroupState.open.value
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
        assertTrue(NavGroupState.canToggle(NavGroup.Chats, current = NavGroup.Watch))
        assertFalse(NavGroupState.isOpen(NavGroup.Chats, allClosed, current = NavGroup.Watch))
    }

    @Test
    fun `Watch has no heading, so it is always open and never folds`() {
        assertFalse(NavGroup.Watch.headed)
        assertTrue(NavGroup.Chats.headed && NavGroup.Folders.headed)
        val allClosed = emptySet<NavGroup>()
        assertTrue(NavGroupState.isOpen(NavGroup.Watch, allClosed, current = NavGroup.Chats))
        assertTrue(NavGroupState.isOpen(NavGroup.Watch, allClosed, current = null))
        assertFalse(NavGroupState.canToggle(NavGroup.Watch, current = NavGroup.Chats))
    }

    @Test
    fun `folds last for the run and a launch starts folded again`() {
        NavGroupState.reset()
        NavGroupState.toggle(NavGroup.Chats)
        assertEquals(setOf(NavGroup.Watch, NavGroup.Chats), NavGroupState.open.value)
        NavGroupState.toggle(NavGroup.Chats)
        assertEquals(setOf(NavGroup.Watch), NavGroupState.open.value)

        NavGroupState.toggle(NavGroup.Folders)
        NavGroupState.reset()
        assertEquals(NavGroupState.DEFAULT_OPEN, NavGroupState.open.value)
    }
}
