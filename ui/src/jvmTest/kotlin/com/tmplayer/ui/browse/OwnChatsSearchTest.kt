package com.tmplayer.ui.browse

import com.tmplayer.data.AddedChat
import com.tmplayer.data.AddedChoice
import com.tmplayer.data.AddedMode
import com.tmplayer.data.AllChatsCursor
import com.tmplayer.data.AllChatsPage
import com.tmplayer.data.ChatGate
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SearchFilters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Search inside the viewer's own chats (HX62): the added chats are searched, the one-off "Search my
 * other chats" goes wider for those words only, nothing is looked up by username, the count line
 * says how much was found, and copies lead by the preferred audio language.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnChatsSearchTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        ChatGate.set(null)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun video(id: Long, name: String, chat: Long) = MediaItem(
        chatId = chat, messageId = id, fileId = id.toInt(), title = name, sizeBytes = 900L * 1024 * 1024, durationSec = 5_000,
        mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = id.toInt(), fileName = name,
    )

    private val done = AllChatsCursor(videoDone = true, documentDone = true)
    private val addedAsked = mutableListOf<String>()
    private val everywhereAsked = mutableListOf<String>()

    /** The added chats (1 and 2) hold one video each; chat 3, not added, holds another. */
    private val source = object : VideoSearchSource {
        override suspend fun page(query: String, cursor: AllChatsCursor, filters: SearchFilters): AllChatsPage {
            addedAsked += query
            return AllChatsPage(listOf(video(1, "Harbour.Lights.2019.1080p.mkv", 1), video(2, "Harbour.Rain.2020.720p.mkv", 2)), done)
        }

        override suspend fun everyChatPage(query: String, cursor: AllChatsCursor, filters: SearchFilters): AllChatsPage {
            everywhereAsked += query
            return AllChatsPage(listOf(video(3, "Harbour.Wind.2021.2160p.mkv", 3)), done)
        }
    }

    private fun picked(vararg ids: Long) = MutableStateFlow(AddedChoice(AddedMode.Picked, ids.map { AddedChat(it, "chat $it") }))

    private fun model(
        choice: MutableStateFlow<AddedChoice> = picked(1, 2),
        wholeGate: MutableStateFlow<Set<Long>?> = MutableStateFlow(null),
        gate: MutableStateFlow<Set<Long>?> = MutableStateFlow(choice.value.ids),
        src: VideoSearchSource = source,
    ) = GlobalSearchViewModel(
        src, known = { emptyList() }, work = dispatcher, settleMs = 0,
        gate = gate, wholeGate = wholeGate, choice = choice,
    )

    @Test
    fun `the count line says how many videos in how many chats`() = runTest(dispatcher) {
        val search = model()
        search.type("harbour")
        advanceUntilIdle()
        val tally = search.state.value.tally
        assertEquals(2, tally?.videos)
        assertEquals(2, tally?.chats)
        assertEquals(listOf("harbour"), addedAsked.distinct())
        assertTrue(everywhereAsked.isEmpty())
    }

    @Test
    fun `search my other chats asks the whole account once, lists it with what was found, and is offered no more`() = runTest(dispatcher) {
        val search = model()
        search.type("harbour")
        advanceUntilIdle()
        assertTrue(search.state.value.canSearchOthers)
        search.searchOtherChats()
        advanceUntilIdle()
        assertEquals(listOf("harbour"), everywhereAsked.distinct())
        assertEquals(setOf(1L, 2L, 3L), search.state.value.groups.flatMap { g -> g.copies.map { it.messageId } }.toSet())
        assertEquals(3, search.state.value.tally?.chats)
        assertTrue(search.state.value.everyChat)
        assertFalse(search.state.value.canSearchOthers)
        // Pressed again it does nothing.
        val asked = everywhereAsked.size
        search.searchOtherChats()
        advanceUntilIdle()
        assertEquals(asked, everywhereAsked.size)
    }

    @Test
    fun `the wider search is for those words only, the next words go back to the added chats`() = runTest(dispatcher) {
        val search = model()
        search.type("harbour")
        advanceUntilIdle()
        search.searchOtherChats()
        advanceUntilIdle()
        val wide = everywhereAsked.size
        search.type("harbour rain")
        advanceUntilIdle()
        assertFalse(search.state.value.everyChat)
        assertEquals(wide, everywhereAsked.size)
        assertTrue(search.state.value.canSearchOthers)
    }

    @Test
    fun `the kids lock coming on takes a chat of the wider search out of the cards at once`() = runTest(dispatcher) {
        val whole = MutableStateFlow<Set<Long>?>(null)
        val browsing = MutableStateFlow<Set<Long>?>(setOf(1L, 2L))
        val search = model(wholeGate = whole, gate = browsing)
        search.type("harbour")
        advanceUntilIdle()
        search.searchOtherChats()
        advanceUntilIdle()
        assertTrue(search.state.value.groups.any { g -> g.copies.any { it.chatId == 3L } })
        // The lock allows chats 1 and 2 only; the wider search reads the lock alone.
        whole.value = setOf(1L, 2L)
        browsing.value = setOf(1L, 2L, 99L)
        advanceUntilIdle()
        assertFalse(search.state.value.groups.any { g -> g.copies.any { it.chatId == 3L } })
        assertTrue(search.state.value.groups.any { g -> g.copies.any { it.chatId == 1L } })
    }

    @Test
    fun `with All chats on there is nothing to add, so no note and no add more chats offer`() = runTest(dispatcher) {
        val search = model(choice = MutableStateFlow(AddedChoice(AddedMode.All)))
        search.type("harbour")
        advanceUntilIdle()
        val state = search.state.value
        assertTrue(state.allChatsOn)
        assertFalse(state.canAddChats)
        assertFalse(state.everyChatNote)
        assertFalse(state.canSearchOthers)
    }

    @Test
    fun `with no chat added every chat is searched, said so, and there is no other chats button`() = runTest(dispatcher) {
        val search = model(choice = MutableStateFlow(AddedChoice()))
        search.type("harbour")
        advanceUntilIdle()
        val state = search.state.value
        assertFalse(state.addedOnly)
        assertFalse(state.canSearchOthers)
        assertTrue(state.everyChatNote)
        search.searchOtherChats()
        advanceUntilIdle()
        assertTrue(everywhereAsked.isEmpty())
    }

    @Test
    fun `an old recent search holding a username is an ordinary query, nothing is looked up`() = runTest(dispatcher) {
        val search = model()
        search.submit("@somechannel")
        advanceUntilIdle()
        // Asked as words of the viewer's own chats; no chat is found, none is offered to join.
        assertTrue(addedAsked.isNotEmpty())
        assertTrue(search.state.value.error == null)
    }

    @Test
    fun `an empty answer in the added chats offers the other chats and the picker`() = runTest(dispatcher) {
        val none = VideoSearchSource { _, _, _ -> AllChatsPage(emptyList(), done) }
        val search = model(src = none)
        search.type("harbour")
        advanceUntilIdle()
        val state = search.state.value
        assertTrue(state.shown.isEmpty())
        assertNull(state.tally)
        assertTrue(state.addedOnly)
        assertTrue(state.canSearchOthers)
    }

    @Test
    fun `copies are led by the preferred audio language the name carries`() = runTest(dispatcher) {
        val both = VideoSearchSource { _, _, _ ->
            AllChatsPage(
                listOf(
                    video(1, "Night.Train.2022.1080p.Tamil.WEB-DL.mkv", 1),
                    video(2, "Night.Train.2022.720p.Hindi.WEB-DL.mkv", 2),
                ),
                done,
            )
        }
        val search = model(src = both)
        search.audioLanguage = "hi"
        search.type("night train")
        advanceUntilIdle()
        assertEquals(2L, search.state.value.groups.single().lead.messageId)
        val plain = model(src = both)
        plain.type("night train")
        advanceUntilIdle()
        assertEquals(1L, plain.state.value.groups.single().lead.messageId)
    }
}
