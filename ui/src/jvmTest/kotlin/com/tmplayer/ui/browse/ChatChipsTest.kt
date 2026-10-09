package com.tmplayer.ui.browse

import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class ChatChipsTest {

    private fun chat(
        id: Long,
        title: String,
        kind: ChatKind,
        unread: Int = 0,
        archived: Boolean = false,
    ) = ChatSummary(id, title, null, 0, kind, isArchived = archived, unreadCount = unread)

    // In Telegram's order, newest activity first.
    private val chats = listOf(
        chat(1, "Zebra films", ChatKind.Channel, unread = 4),
        chat(2, "Saved Messages", ChatKind.Saved),
        chat(3, "Book club", ChatKind.Group),
        chat(4, "anna", ChatKind.Direct, unread = 1),
        chat(5, "Old news", ChatKind.Channel, archived = true),
    )

    private val chatsSection = BrowseSection.of(BrowseTab.Chats)

    private fun ids(filter: ChatFilter, sort: ChatSort = ChatSort.Recent, query: String = "") =
        filterChats(chats, chatsSection, emptySet(), query, filter, sort).map { it.id }

    @Test
    fun `each chip filters the one Chats list, and only Archived shows the archive`() {
        assertEquals(listOf(1L, 2L, 3L, 4L), ids(ChatFilter.All))
        assertEquals(listOf(1L, 4L), ids(ChatFilter.Unread))
        assertEquals(listOf(1L), ids(ChatFilter.Channels))
        assertEquals(listOf(3L), ids(ChatFilter.Groups))
        // Saved Messages is a private chat, so People keeps it.
        assertEquals(listOf(2L, 4L), ids(ChatFilter.People))
        assertEquals(listOf(5L), ids(ChatFilter.Archived))
    }

    @Test
    fun `the sort toggle orders by name without regard to case, and Recent keeps Telegram's order`() {
        assertEquals(listOf(4L, 3L, 2L, 1L), ids(ChatFilter.All, ChatSort.Name))
        assertEquals(listOf(1L, 4L), ids(ChatFilter.Unread, ChatSort.Recent))
        assertEquals(listOf(4L, 1L), ids(ChatFilter.Unread, ChatSort.Name))
        assertEquals(ChatSort.Name, ChatSort.Recent.toggled)
        assertEquals(ChatSort.Recent, ChatSort.Name.toggled)
    }

    @Test
    fun `a search ranks inside the chosen chip`() {
        assertEquals(listOf(3L), ids(ChatFilter.Groups, query = "book"))
        assertEquals(emptyList<Long>(), ids(ChatFilter.Channels, query = "book"))
    }

    @Test
    fun `the chips apply to Chats only, so Saved Messages and folders ignore them`() {
        val saved = filterChats(chats, BrowseSection.of(BrowseTab.Saved), emptySet(), "", ChatFilter.Channels, ChatSort.Name)
        assertEquals(listOf(2L), saved.map { it.id })
        val favourites = filterChats(chats, BrowseSection.of(BrowseTab.Favorites), setOf(5L, 1L), "", ChatFilter.Groups)
        assertEquals(listOf(1L, 5L), favourites.map { it.id })
    }

    @Test
    fun `saved screen state from before the merge lands on the entry that replaced it`() {
        for (old in listOf("Recent", "Unread", "Channels", "Groups", "People", "All", "Archived")) {
            assertEquals(old, chatsSection, BrowseSection.decode("tab:$old"))
        }
        assertEquals(BrowseSection.of(BrowseTab.History), BrowseSection.decode("tab:Continue"))
        assertEquals(BrowseSection.of(BrowseTab.History), BrowseSection.decode("tab:Watched"))
        assertEquals(BrowseSection.of(BrowseTab.Saved), BrowseSection.decode("tab:Saved"))
        for (tab in BrowseTab.entries) {
            assertEquals(BrowseSection.of(tab), BrowseSection.decode(BrowseSection.encode(BrowseSection.of(tab))))
        }
    }

    @Test
    fun `unknown or missing names fall back to the defaults`() {
        assertEquals(ChatFilter.All, ChatFilter.decode(null))
        assertEquals(ChatFilter.All, ChatFilter.decode("Recent"))
        assertEquals(ChatSort.Recent, ChatSort.decode("sideways"))
        assertEquals(HistoryTab.Continue, HistoryTab.decode(null))
    }

    @Test
    fun `the chosen chip, order and History tab are remembered in SettingsStore`() = runBlocking {
        val dir = Files.createTempDirectory("chips")
        try {
            val store = SettingsStore(SettingsStore.openDataStore(dir.toFile().resolve(SettingsStore.FILE_NAME)))
            assertEquals(ChatFilter.All, ChatFilter.decode(store.chatFilter.first()))
            store.setChatFilter(ChatFilter.Channels.name)
            store.setChatSort(ChatSort.Name.name)
            store.setHistoryTab(HistoryTab.Watched.name)
            assertEquals(ChatFilter.Channels, ChatFilter.decode(store.chatFilter.first()))
            assertEquals(ChatSort.Name, ChatSort.decode(store.chatSort.first()))
            assertEquals(HistoryTab.Watched, HistoryTab.decode(store.historyTab.first()))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
