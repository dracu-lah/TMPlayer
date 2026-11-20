package com.tmplayer.ui.browse

import com.tmplayer.data.AddedChat
import com.tmplayer.data.AddedChats
import com.tmplayer.data.AddedChoice
import com.tmplayer.data.AddedMode
import com.tmplayer.data.ChatGate
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.SettingsStore
import com.tmplayer.ui.components.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Favourites show a starred chat whether or not the viewer ticked it (HX86, user decision): a star
 * is their own act, so "All chats" on or off, a picked set that leaves it out, the tab still lists
 * it and it still opens. Chats lists only the ticked ones, and the kids lock still wins.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FavouritesBeyondPicksTest {

    private fun chat(id: Long, archived: Boolean = false) =
        ChatSummary(id = id, title = "Chat $id", miniThumbnail = null, photoFileId = 0, kind = ChatKind.Channel, isArchived = archived)

    private val everything = listOf(chat(1), chat(2), chat(3), chat(4, archived = true))
    private val favourites = BrowseSection.of(BrowseTab.Favorites)
    private val chatsTab = BrowseSection.of(BrowseTab.Chats)

    private val dir: File = Files.createTempDirectory("fav-beyond").toFile()

    @After fun tearDown() {
        Dispatchers.resetMain()
        AddedChats.set(AddedChoice())
        ChatGate.set(null)
        dir.deleteRecursively()
    }

    @Test
    fun `a starred chat that was not ticked joins the pool, in the list's order`() {
        val loaded = listOf(chat(1), chat(2))
        val pool = mergeStarred(everything, loaded, setOf(3L, 4L), null)
        assertEquals(listOf(1L, 2L, 3L, 4L), pool.map { it.id })
    }

    @Test
    fun `no star beyond the picks keeps the very same list, so nothing recomposes for it`() {
        val loaded = listOf(chat(1), chat(2))
        assertSame(loaded, mergeStarred(everything, loaded, emptySet(), null))
        assertSame(loaded, mergeStarred(everything, loaded, setOf(2L), null))
        // A star on a chat the account no longer has adds nothing.
        assertSame(loaded, mergeStarred(everything, loaded, setOf(99L), null))
    }

    @Test
    fun `the kids lock still hides a starred chat it does not allow`() {
        val loaded = listOf(chat(1))
        val pool = mergeStarred(everything, loaded, setOf(2L, 3L), setOf(1L, 3L))
        assertEquals(listOf(1L, 3L), pool.map { it.id })
    }

    @Test
    fun `Favourites lists the starred chats, ticked or not, an archived one too, and Chats lists the ticked only`() {
        val loaded = listOf(chat(1), chat(2))
        val data = BrowseData(loaded, null, mergeStarred(everything, loaded, setOf(1L, 3L, 4L), null))
        val stars = setOf(1L, 3L, 4L)
        val fav = filterChats(data.chatsFor(favourites), favourites, stars, "")
        assertEquals(listOf(1L, 3L, 4L), fav.map { it.id })
        val chats = filterChats(data.chatsFor(chatsTab), chatsTab, stars, "")
        assertEquals(listOf(1L, 2L), chats.map { it.id })
    }

    @Test
    fun `the view model hands Favourites the starred chat outside the picks and Chats never gets it`() {
        val store = SettingsStore(SettingsStore.openDataStore(dir.resolve(SettingsStore.FILE_NAME)))
        runBlocking {
            store.saveChatSnapshot(everything)
            store.cachedAccountSnapshot()
            store.toggleFavorite(3L)
        }
        AddedChats.set(AddedChoice(AddedMode.Picked, listOf(AddedChat(1L, "Chat 1"))))
        ChatGate.set(null)
        Dispatchers.setMain(UnconfinedTestDispatcher())

        val model = ChatListViewModel(store)

        runBlocking {
            withTimeout(10_000) {
                val data = (model.state.first {
                    it is UiState.Content && it.value.withStarred.any { c -> c.id == 3L }
                } as UiState.Content).value
                assertEquals(listOf(1L), data.chats.map { it.id })
                assertEquals(listOf(1L, 3L), data.withStarred.map { it.id })
                // The star taken off, the extra goes again.
                store.toggleFavorite(3L)
                val after = (model.state.first {
                    it is UiState.Content && it.value.withStarred.none { c -> c.id == 3L }
                } as UiState.Content).value
                assertEquals(listOf(1L), after.withStarred.map { it.id })
            }
        }
    }

    @Test
    fun `with All chats on, every chat is in the list and a star adds nothing`() {
        val data = BrowseData(everything, null, mergeStarred(everything, everything, setOf(3L), null))
        assertSame(everything, data.withStarred)
    }
}
