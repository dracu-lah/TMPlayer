package com.tmplayer.ui.browse

import com.tmplayer.data.AllChatsCursor
import com.tmplayer.data.AllChatsPage
import com.tmplayer.data.ChatGate
import com.tmplayer.data.MediaItem
import com.tmplayer.online.MetaQuery
import com.tmplayer.online.PosterChoice
import com.tmplayer.online.PosterChoices
import com.tmplayer.online.WatchOffers
import com.tmplayer.online.WhereToWatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/** HX41 in the search model: titles over the cards, "Not in your chats", Where to watch, with a fake TMDB. */
@OptIn(ExperimentalCoroutinesApi::class)
class TitleSearchModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        ChatGate.set(null)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun video(id: Long, name: String, chat: Long = id % 3 + 1) = MediaItem(
        chatId = chat, messageId = id, fileId = id.toInt(), title = name, sizeBytes = 900L * 1024 * 1024,
        durationSec = 5_000, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = id.toInt(), fileName = name,
    )

    private val done = AllChatsCursor(videoDone = true, documentDone = true)

    private val source = VideoSearchSource { query, _, _ ->
        when (query) {
            "night train" -> AllChatsPage(listOf(video(1, "Night.Train.2019.1080p.mkv"), video(2, "Night.Train.2019.720p.mkv")), done)
            else -> AllChatsPage(emptyList(), done)
        }
    }

    private val nightTrain = PosterChoice(7, false, "Night Train", 2019, "en", "", null)
    private val harbour = PosterChoice(8, false, "Harbour Lights", 2001, "en", "", null)

    private class FakeTitles(var answer: PosterChoices, var watch: WhereToWatch = WhereToWatch.NoneHere("GB")) : TitleSource {
        val asked = mutableListOf<MetaQuery>()
        var watchAsked = 0
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun titles(query: MetaQuery): PosterChoices {
            asked += query
            return answer
        }

        override suspend fun whereToWatch(title: PosterChoice): WhereToWatch {
            watchAsked++
            gate?.await()
            return watch
        }
    }

    private fun model(titles: TitleSource?, settle: Long = 100) =
        GlobalSearchViewModel(source, known = { emptyList() }, work = dispatcher, settleMs = settle, cache = null, titleSource = titles)

    @Test
    fun `the copies the viewer has are listed under the title, the typing asking TMDB once`() = runTest(dispatcher) {
        val titles = FakeTitles(PosterChoices.Found(listOf(nightTrain)))
        val search = model(titles)
        "night train".indices.forEach { search.type("night train".take(it + 1)) }
        advanceUntilIdle()
        assertEquals(1, titles.asked.size)
        assertEquals("night train", titles.asked.single().title)
        val state = search.state.value
        assertEquals(TitleLookup.Found, state.titleLookup)
        val hit = state.titleHits.single()
        assertTrue(hit.inChats)
        assertEquals(2, hit.fileCount)
        assertFalse(state.notInChats)
        // A quality added asks nothing new.
        search.type("night train 1080p")
        advanceUntilIdle()
        assertEquals(1, titles.asked.size)
    }

    @Test
    fun `a title with no copy says Not in your chats, only once the chats have answered`() = runTest(dispatcher) {
        val titles = FakeTitles(PosterChoices.Found(listOf(harbour)))
        val search = model(titles)
        search.type("harbour lights")
        // Still settling: never "Not in your chats" before the chats are read.
        assertFalse(search.state.value.notInChats)
        advanceUntilIdle()
        val state = search.state.value
        assertTrue(state.notInChats)
        assertTrue(state.titleHits.single().notInChats)
        assertTrue(state.groups.isEmpty())
    }

    @Test
    fun `with no key or lookups off the search works from the chats alone`() = runTest(dispatcher) {
        for (answer in listOf(PosterChoices.NoKey, PosterChoices.Off)) {
            val search = model(FakeTitles(answer))
            search.type("night train")
            advanceUntilIdle()
            val state = search.state.value
            assertEquals(TitleLookup.Off, state.titleLookup)
            assertTrue(state.titleHits.isEmpty())
            assertFalse(state.notInChats)
            assertEquals(1, state.groups.size)
        }
        // No title source at all (the promo fixture): the same.
        val bare = model(null)
        bare.type("night train")
        advanceUntilIdle()
        assertEquals(TitleLookup.Idle, bare.state.value.titleLookup)
        assertEquals(1, bare.state.value.groups.size)
    }

    @Test
    fun `offline titles say so quietly and Try again asks only the titles`() = runTest(dispatcher) {
        val titles = FakeTitles(PosterChoices.Unavailable)
        val search = model(titles)
        search.type("night train")
        advanceUntilIdle()
        assertEquals(TitleLookup.Unavailable, search.state.value.titleLookup)
        assertEquals(1, search.state.value.groups.size)
        titles.answer = PosterChoices.Found(listOf(nightTrain))
        search.retryTitles()
        advanceUntilIdle()
        assertEquals(2, titles.asked.size)
        assertEquals(TitleLookup.Found, search.state.value.titleLookup)
        // Try again with nothing failed does nothing.
        search.retryTitles()
        advanceUntilIdle()
        assertEquals(2, titles.asked.size)
    }

    @Test
    fun `clearing the words clears the titles, a quality alone asks nothing`() = runTest(dispatcher) {
        val titles = FakeTitles(PosterChoices.Found(listOf(nightTrain)))
        val search = model(titles)
        search.type("night train")
        advanceUntilIdle()
        search.type("")
        advanceUntilIdle()
        assertTrue(search.state.value.titles.isEmpty())
        assertEquals(TitleLookup.Idle, search.state.value.titleLookup)
        search.type("1080p")
        advanceUntilIdle()
        assertEquals(1, titles.asked.size)
    }

    @Test
    fun `where to watch opens once, answers, and a closed sheet stays closed`() = runTest(dispatcher) {
        val offers = WhereToWatch.Found(WatchOffers("GB", stream = listOf("Stream One")))
        val titles = FakeTitles(PosterChoices.Found(listOf(harbour)), offers)
        val search = model(titles)
        search.type("harbour lights")
        advanceUntilIdle()
        titles.gate = CompletableDeferred()
        search.openWhereToWatch(harbour)
        search.openWhereToWatch(harbour)
        advanceUntilIdle()
        assertEquals(harbour to null, search.state.value.watching)
        titles.gate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, titles.watchAsked)
        assertEquals(harbour to offers, search.state.value.watching)
        // Closed while a second title was asking: its answer does not reopen the sheet.
        titles.gate = CompletableDeferred()
        search.openWhereToWatch(nightTrain)
        advanceUntilIdle()
        search.closeWhereToWatch()
        titles.gate?.complete(Unit)
        advanceUntilIdle()
        assertNull(search.state.value.watching)
    }

    @Test
    fun `search my other chats finds the copy and the title stops saying Not in your chats`() = runTest(dispatcher) {
        val added = kotlinx.coroutines.flow.MutableStateFlow(
            com.tmplayer.data.AddedChoice(com.tmplayer.data.AddedMode.Picked, listOf(com.tmplayer.data.AddedChat(1, "one"))),
        )
        val wide = object : VideoSearchSource {
            override suspend fun page(query: String, cursor: AllChatsCursor, filters: com.tmplayer.data.SearchFilters) = AllChatsPage(emptyList(), done)
            override suspend fun everyChatPage(query: String, cursor: AllChatsCursor, filters: com.tmplayer.data.SearchFilters) =
                AllChatsPage(listOf(video(5, "Harbour.Lights.2001.mkv", chat = 9)), done)
        }
        val search = GlobalSearchViewModel(
            wide, known = { emptyList() }, work = dispatcher, settleMs = 0, cache = null,
            gate = kotlinx.coroutines.flow.MutableStateFlow(setOf(1L)), wholeGate = kotlinx.coroutines.flow.MutableStateFlow(null), choice = added,
            titleSource = FakeTitles(PosterChoices.Found(listOf(harbour))),
        )
        search.type("harbour lights")
        advanceUntilIdle()
        assertTrue(search.state.value.notInChats)
        assertTrue(search.state.value.canSearchOthers)
        search.searchOtherChats()
        advanceUntilIdle()
        assertFalse(search.state.value.notInChats)
        assertTrue(search.state.value.titleHits.single().inChats)
    }
}
