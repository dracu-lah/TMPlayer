package com.tmplayer.ui.browse

import com.tmplayer.data.AllChatsCursor
import com.tmplayer.data.AllChatsPage
import com.tmplayer.data.ChatGate
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SearchChatType
import com.tmplayer.data.SearchFilters
import com.tmplayer.data.SearchQuality
import com.tmplayer.data.SearchSort
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GlobalSearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        ChatGate.set(null)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun video(id: Long, name: String, chat: Long = id % 3 + 1) = MediaItem(
        chatId = chat,
        messageId = id,
        fileId = id.toInt(),
        title = name,
        sizeBytes = 900L * 1024 * 1024,
        durationSec = 5_000,
        mimeType = "video/mp4",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = id.toInt(),
        fileName = name,
    )

    private val done = AllChatsCursor(videoDone = true, documentDone = true)
    private val asked = mutableListOf<Pair<String, SearchFilters>>()

    /** Telegram as a fixture: three thin pages for "harbour", nothing for anything else. */
    private val source = VideoSearchSource { query, cursor, filters ->
        asked += query to filters
        when {
            query != "harbour" -> AllChatsPage(emptyList(), done)
            cursor.videoOffset.isEmpty() -> AllChatsPage(listOf(video(1, "Harbour.Lights.2019.1080p.mkv")), AllChatsCursor(videoOffset = "2", documentDone = true))
            cursor.videoOffset == "2" -> AllChatsPage(listOf(video(2, "Harbour.Rain.2020.720p.mkv")), AllChatsCursor(videoOffset = "3", documentDone = true))
            else -> AllChatsPage(listOf(video(3, "Harbour.Wind.2021.2160p.mkv")), AllChatsCursor(videoOffset = "4", documentDone = true))
        }
    }

    @Test
    fun `typing settles before Telegram is asked, and only the last query goes`() = runTest(dispatcher) {
        val model = GlobalSearchViewModel(source, known = { emptyList() }, work = dispatcher, settleMs = 100)
        model.type("ha")
        model.type("harb")
        model.type("harbour")
        // Waiting for the typing to rest is not searching yet: no spinner, no "Nothing found".
        assertFalse(model.state.value.searching)
        assertTrue(model.state.value.settling)
        assertTrue(model.state.value.hiddenBy.isEmpty())
        advanceUntilIdle()
        // One query asked (its later pages included), never one per letter.
        assertEquals(listOf("harbour"), asked.map { it.first }.distinct())
        assertFalse(model.state.value.searching)
        assertFalse(model.state.value.settling)
    }

    @Test
    fun `the same words are never asked twice`() = runTest(dispatcher) {
        val model = GlobalSearchViewModel(source, known = { emptyList() }, work = dispatcher, settleMs = 100)
        model.type("harbour")
        advanceUntilIdle()
        val before = asked.size
        // Typed again with a space, submitted with the search key, and a quality added to it.
        model.type("harbour ")
        model.submit("harbour")
        model.type("harbour 4k")
        advanceUntilIdle()
        assertEquals(before, asked.size)
        assertEquals(listOf(3L), model.state.value.shown.map { it.lead.messageId })
    }

    @Test
    fun `the search key skips the wait, clearing drops what was waiting`() = runTest(dispatcher) {
        val model = GlobalSearchViewModel(source, known = { emptyList() }, work = dispatcher, settleMs = 10_000)
        model.type("harbour")
        model.submit("harbour")
        assertTrue(model.state.value.searching)
        dispatcher.scheduler.runCurrent()
        assertEquals("harbour", asked.first().first)
        advanceUntilIdle()
        val before = asked.size
        model.type("harbour wind")
        model.type("")
        advanceUntilIdle()
        assertEquals(before, asked.size)
        assertTrue(model.state.value.groups.isEmpty())
        assertFalse(model.state.value.busy)
    }

    @Test
    fun `one letter is not sent to Telegram`() = runTest(dispatcher) {
        val model = GlobalSearchViewModel(source, known = { emptyList() }, work = dispatcher, settleMs = 100)
        model.type("h")
        advanceUntilIdle()
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `what was listed and still matches stays up until the new answer`() = runTest(dispatcher) {
        val model = GlobalSearchViewModel(source, known = { emptyList() }, work = dispatcher, settleMs = 100)
        model.type("harbour")
        advanceUntilIdle()
        model.type("harbour wind")
        // Still settling: the Wind card answers the new words and stays up, so the list never
        // flashes empty between one letter and the next.
        assertTrue(3L in model.state.value.groups.map { it.lead.messageId })
        advanceUntilIdle()
        assertFalse(model.state.value.busy)
    }

    @Test
    fun `a thin first answer keeps asking, three pages at most`() = runTest(dispatcher) {
        val model = GlobalSearchViewModel(source, known = { emptyList() }, work = dispatcher, settleMs = 0)
        model.type("harbour")
        advanceUntilIdle()
        assertEquals(3, asked.size)
        assertEquals(listOf(1L, 2L, 3L), model.state.value.shown.map { it.lead.messageId })
        assertFalse(model.state.value.stillSearching)
    }

    @Test
    fun `what the app already knows shows before Telegram answers`() = runTest(dispatcher) {
        val local = video(40, "Harbour_Notes_2024.mkv")
        val model = GlobalSearchViewModel(source, known = { listOf(local) }, work = dispatcher, settleMs = 1_000)
        model.type("harbour notes")
        // Worked out off the main thread, but before the settle is over.
        dispatcher.scheduler.runCurrent()
        assertTrue(model.state.value.settling)
        assertEquals(listOf(40L), model.state.value.groups.map { it.lead.messageId })
    }

    @Test
    fun `a quality or a sort never asks again, a chat type does`() = runTest(dispatcher) {
        val model = GlobalSearchViewModel(source, known = { emptyList() }, work = dispatcher, settleMs = 0)
        model.type("harbour")
        advanceUntilIdle()
        val before = asked.size
        model.setFilters(SearchFilters(quality = SearchQuality.UHD))
        model.setSort(SearchSort.Newest)
        advanceUntilIdle()
        assertEquals(before, asked.size)
        assertEquals(listOf(3L), model.state.value.shown.map { it.lead.messageId })
        model.setFilters(SearchFilters(chatType = SearchChatType.Channels))
        advanceUntilIdle()
        assertTrue(asked.size > before)
        assertEquals(SearchChatType.Channels, asked.last().second.chatType)
    }

    @Test
    fun `a search that sat with results is remembered, one typed through is not`() = runTest(dispatcher) {
        val remembered = mutableListOf<String>()
        val model = GlobalSearchViewModel(source, onSearched = { remembered += it }, known = { emptyList() }, work = dispatcher, settleMs = 0, rememberMs = 2_000)
        model.type("harbour")
        advanceUntilIdle()
        assertEquals(listOf("harbour"), remembered)
        model.type("nothing here")
        advanceUntilIdle()
        assertEquals(listOf("harbour"), remembered)
    }

    @Test
    fun `offline lists the local half and asks again on reconnect`() = runTest(dispatcher) {
        var online = false
        val flaky = VideoSearchSource { query, cursor, filters ->
            if (!online) throw VideoSearchSource.Offline()
            source.page(query, cursor, filters)
        }
        val model = GlobalSearchViewModel(flaky, known = { listOf(video(50, "Harbour.Days.2018.mkv")) }, work = dispatcher, settleMs = 0)
        model.type("harbour")
        advanceUntilIdle()
        assertTrue(model.state.value.offline)
        assertEquals(listOf(50L), model.state.value.groups.map { it.lead.messageId })
        online = true
        model.reconnected()
        advanceUntilIdle()
        assertFalse(model.state.value.offline)
        assertTrue(model.state.value.groups.size > 1)
    }

    @Test
    fun `a flood wait counts down, then asks again`() = runTest(dispatcher) {
        var calls = 0
        val waiting = VideoSearchSource { query, cursor, filters ->
            calls++
            if (calls == 1) throw IllegalStateException("FLOOD_WAIT_3")
            source.page(query, cursor, filters)
        }
        val model = GlobalSearchViewModel(waiting, known = { emptyList() }, work = dispatcher, settleMs = 0)
        model.type("harbour")
        dispatcher.scheduler.advanceTimeBy(500)
        dispatcher.scheduler.runCurrent()
        assertEquals(3, model.state.value.floodWait)
        advanceUntilIdle()
        assertEquals(0, model.state.value.floodWait)
        assertTrue(model.state.value.groups.isNotEmpty())
    }

    @Test
    fun `a deleted video's card goes when it is opened`() = runTest(dispatcher) {
        val gone = object : VideoSearchSource {
            override suspend fun page(query: String, cursor: AllChatsCursor, filters: SearchFilters) = source.page(query, cursor, filters)
            override suspend fun stillThere(item: MediaItem): Boolean = item.messageId != 1L
        }
        val model = GlobalSearchViewModel(gone, known = { emptyList() }, work = dispatcher, settleMs = 0)
        model.type("harbour")
        advanceUntilIdle()
        val first = model.state.value.groups.first().lead
        assertFalse(model.check(first))
        assertFalse(model.state.value.groups.any { g -> g.copies.any { it.messageId == 1L } })
    }
    private fun withLookup(answer: suspend (MediaItem) -> com.tmplayer.data.MessageLookup?) = object : VideoSearchSource {
        override suspend fun page(query: String, cursor: AllChatsCursor, filters: SearchFilters) = source.page(query, cursor, filters)
        override suspend fun lookup(item: MediaItem) = answer(item)
    }

    @Test
    fun `an unreachable lookup opens the page and keeps the card`() = runTest(dispatcher) {
        val model = GlobalSearchViewModel(
            withLookup { com.tmplayer.data.MessageLookup.Unreachable },
            known = { emptyList() }, work = dispatcher, settleMs = 0,
        )
        model.type("harbour")
        advanceUntilIdle()
        val before = model.state.value.groups.size
        assertTrue(before > 0)
        assertTrue(model.check(model.state.value.groups.first().lead))
        assertEquals(before, model.state.value.groups.size)
    }

    @Test
    fun `a gone lookup still removes the card and a found one keeps it`() = runTest(dispatcher) {
        val model = GlobalSearchViewModel(
            withLookup { item ->
                if (item.messageId == 1L) com.tmplayer.data.MessageLookup.Gone(com.tmplayer.data.GoneReason.Deleted)
                else com.tmplayer.data.MessageLookup.Found(item)
            },
            known = { emptyList() }, work = dispatcher, settleMs = 0,
        )
        model.type("harbour")
        advanceUntilIdle()
        val groups = model.state.value.groups
        val first = groups.first { g -> g.copies.any { it.messageId == 1L } }.lead
        val other = groups.first { g -> g.copies.none { it.messageId == 1L } }.lead
        assertTrue(model.check(other))
        assertFalse(model.check(first))
        assertFalse(model.state.value.groups.any { g -> g.copies.any { it.messageId == 1L } })
    }

    @Test
    fun `a lookup that does not answer in time opens the page after the bound`() = runTest(dispatcher) {
        val model = GlobalSearchViewModel(
            withLookup { kotlinx.coroutines.awaitCancellation() },
            known = { emptyList() }, work = dispatcher, settleMs = 0,
        )
        model.type("harbour")
        advanceUntilIdle()
        val before = model.state.value.groups.size
        assertTrue(model.check(model.state.value.groups.first().lead))
        assertEquals(before, model.state.value.groups.size)
        assertTrue(testScheduler.currentTime >= VideoSearchSource.CHECK_MS)
    }
    @Test
    fun `a page that fails to load says so, is not asked again by scrolling, and Try again asks once more`() = runTest(dispatcher) {
        var failSecond = true
        var calls = 0
        val twoPages = VideoSearchSource { query, cursor, _ ->
            calls++
            when {
                cursor.videoOffset.isEmpty() ->
                    AllChatsPage((1L..14L).map { video(it, "Harbour.Part.$it.2019.1080p.mkv") }, AllChatsCursor(videoOffset = "2", documentDone = true))
                failSecond -> throw java.io.IOException("down")
                else -> AllChatsPage(listOf(video(50, "Harbour.End.2021.1080p.mkv")), done)
            }
        }
        val model = GlobalSearchViewModel(twoPages, known = { emptyList() }, work = dispatcher, settleMs = 0)
        model.type("harbour")
        advanceUntilIdle()
        assertFalse(model.state.value.moreFailed)
        model.loadMore()
        advanceUntilIdle()
        assertTrue(model.state.value.moreFailed)
        assertFalse(model.state.value.loadingMore)
        assertEquals(14, model.state.value.groups.size)
        // Scrolling to the end again does not loop on a dead line.
        val asked = calls
        model.loadMore()
        advanceUntilIdle()
        assertEquals(asked, calls)
        failSecond = false
        model.retryMore()
        advanceUntilIdle()
        assertFalse(model.state.value.moreFailed)
        assertEquals(15, model.state.value.groups.size)
        assertTrue(model.state.value.endReached)
    }
}
