package com.tmplayer.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Test

/** U18: every empty tab offers a next step, decided once for the phone, the television and the desktop. */
class EmptyTabNextTest {

    private fun tab(t: BrowseTab) = BrowseSection.of(t)

    @Test
    fun `a search that found nothing offers Search all chats on every tab`() {
        for (t in BrowseTab.entries) {
            assertEquals(t.name, EmptyTabNext.SearchAllChats, emptyTabNext(tab(t), "zebra"))
        }
    }

    @Test
    fun `Favourites, Saved and the filtered Chats pages offer Go to Chats`() {
        assertEquals(EmptyTabNext.GoToChats, emptyTabNext(tab(BrowseTab.Favorites), ""))
        assertEquals(EmptyTabNext.GoToChats, emptyTabNext(tab(BrowseTab.Saved), ""))
        assertEquals(EmptyTabNext.GoToChats, emptyTabNext(tab(BrowseTab.Chats), "", ChatFilter.Unread))
        assertEquals(EmptyTabNext.GoToChats, emptyTabNext(tab(BrowseTab.Chats), "", ChatFilter.Archived))
    }

    @Test
    fun `every History tab offers Go to Chats`() {
        for (h in HistoryTab.entries) {
            assertEquals(h.name, EmptyTabNext.GoToChats, emptyTabNext(tab(BrowseTab.History), "", history = h))
        }
    }

    @Test
    fun `the page that already is every chat offers nothing`() {
        assertEquals(EmptyTabNext.None, emptyTabNext(tab(BrowseTab.Chats), "", ChatFilter.All))
    }

    @Test
    fun `a blank query is not a search`() {
        assertEquals(EmptyTabNext.GoToChats, emptyTabNext(tab(BrowseTab.Favorites), "   "))
    }
}
