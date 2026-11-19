package com.tmplayer.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Back between the pages the phone's floating bar reaches, and which bar button is lit. */
class TouchRoutesTest {

    private val side = setOf("settings", "downloads")

    private fun back(to: String, from: String, fromBack: String? = null, toBack: String? = null) =
        TouchRoutes.backTarget(to, from, fromBack, toBack, lateral = { it in side }, home = "browse")

    @Test
    fun `Settings from the browser goes back to the browser`() {
        assertEquals("browse", back(to = "settings", from = "browse"))
    }

    @Test
    fun `Downloads from a chat goes back to the chat, as Not enough space does`() {
        assertEquals("chat", back(to = "downloads", from = "chat"))
    }

    @Test
    fun `a step sideways keeps the Back of the page it was taken from`() {
        // A chat, Not enough space, Downloads, then Settings from the popover: Back from Settings is the chat.
        assertEquals("chat", back(to = "settings", from = "downloads", fromBack = "chat"))
        assertEquals("browse", back(to = "downloads", from = "settings", fromBack = "browse"))
    }

    @Test
    fun `Settings and Downloads never send Back to each other`() {
        // Downloads from Settings' Cached videos row, then Settings from the popover: Settings keeps its own Back.
        assertEquals("chat", back(to = "settings", from = "downloads", fromBack = "settings", toBack = "chat"))
        assertEquals("browse", back(to = "settings", from = "downloads", fromBack = "settings", toBack = null))
        assertEquals("browse", back(to = "settings", from = "downloads", fromBack = "settings", toBack = "downloads"))
    }

    @Test
    fun `the highlight follows the bar's order, Downloads after Search`() {
        assertEquals(0, barHighlightIndex(TouchDestination.Home))
        assertEquals(1, barHighlightIndex(TouchDestination.Chats))
        // Home, Chats, Search (a button, not a place), then Downloads: the fourth slot.
        assertEquals(3, barHighlightIndex(TouchDestination.Downloads))
        // A page behind the gear (Favourites, History, Watch later) lights nothing.
        assertNull(barHighlightIndex(null))
    }

    @Test
    fun `Home and Chats are the two pages of the swipe, and nothing else is`() {
        val home = BrowseSection.of(BrowseTab.Home)
        val chats = BrowseSection.of(BrowseTab.Chats)
        assertEquals(0, swipePage(home))
        assertEquals(1, swipePage(chats))
        assertNull(swipePage(BrowseSection.of(BrowseTab.Favorites)))
        assertNull(swipePage(BrowseSection.of(BrowseTab.History)))
        assertEquals(home, swipeSection(0, chats))
        assertEquals(chats, swipeSection(1, chats))
    }

    @Test
    fun `the Chats page comes back as Chats, even from a folder page an older build saved`() {
        val chats = BrowseSection.of(BrowseTab.Chats)
        assertEquals(chats, chatsPageOf(BrowseSection.encode(chats)))
        assertEquals(chats, chatsPageOf("folder:9:Gone"))
        assertEquals(chats, chatsPageOf(""))
        // Never a page that is not on the Chats page.
        assertEquals(chats, chatsPageOf(BrowseSection.encode(BrowseSection.of(BrowseTab.History))))
    }

    @Test
    fun `a page with its own buttons keeps room for their pill over the bar`() {
        assert(touchShellClearance(withActions = true) > touchShellClearance(withActions = false))
        assertEquals(BAR_MARGIN + BAR_HEIGHT + BAR_GAP, touchShellClearance(withActions = false))
    }
}
