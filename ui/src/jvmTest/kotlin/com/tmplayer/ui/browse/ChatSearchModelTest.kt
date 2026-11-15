package com.tmplayer.ui.browse

import com.tmplayer.data.ChatGrid
import com.tmplayer.data.ChatHistoryPage
import com.tmplayer.data.ChatSearchSource
import com.tmplayer.data.MediaCursors
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaPage
import com.tmplayer.data.PageQueries
import com.tmplayer.data.QueryKey
import com.tmplayer.data.SearchSettle
import com.tmplayer.ui.components.StateAction
import com.tmplayer.ui.components.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/**
 * The search box of one chat through [MediaListViewModel], on virtual time: the bug of 2026-11-15
 * (a dotted file name past the loaded pages never found), the debounce, a slow answer for old words
 * never landing over the new ones, Keep looking, and a 20,000 video chat of a 3,000 chat account.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSearchModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val chatId = 3L

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        PageQueries.cache.remove { it.chatId == chatId }
        Dispatchers.resetMain()
    }

    private val dotted = "Resident.Evil.Welcome.To.Raccoon.City.2021.1080p.mkv"

    private fun video(index: Int, name: String) = MediaItem(
        chatId = chatId, messageId = (COUNT - index).toLong() * 16, fileId = index + 1, title = name.substringBeforeLast('.'),
        sizeBytes = 900L * 1024 * 1024, durationSec = 5_000, mimeType = "video/x-matroska", thumbnailFileId = 0,
        miniThumbnail = null, date = 1_700_000_000 - index, fileName = name,
    )

    /** A chat of [COUNT] videos whose Telegram matches words whole, answering after [latencyMs]. */
    private inner class Chat(named: Map<Int, String>, val latencyMs: Long = 300) : ChatSearchSource {
        val videos = List(COUNT) { video(it, named[it] ?: "Show.$it.S01E05.mkv") }
        val asked = mutableListOf<String>()
        var histories = 0

        override suspend fun search(query: String, cursors: MediaCursors, limit: Int): MediaPage {
            asked += query
            delay(latencyMs)
            val words = query.lowercase().split(' ').filter { it.isNotEmpty() }
            val found = videos.filter { v -> (cursors.video == 0L || v.messageId < cursors.video) && words.all { it in v.fileName.lowercase().split(' ') } }.take(limit)
            val done = found.size < limit
            return MediaPage(found, cursors.copy(video = found.lastOrNull()?.messageId ?: 0, videoDone = done, documentDone = true, animationDone = true), done)
        }

        override suspend fun history(fromMessageId: Long, limit: Int, onlyLocal: Boolean): ChatHistoryPage {
            histories++
            delay(latencyMs / 2)
            val page = videos.filter { fromMessageId == 0L || it.messageId < fromMessageId }.take(limit)
            return ChatHistoryPage(page, page.size, if (page.size < limit) 0L else page.last().messageId)
        }

        override suspend fun online() = true
    }

    /** The chat opened with its first [loaded] videos kept from the last visit, so no TDLib is needed to draw it. */
    private fun open(chat: Chat, loaded: Int = 120): MediaListViewModel {
        PageQueries.cache.put(
            QueryKey.ChatMedia(chatId),
            ChatGrid(items = chat.videos.take(loaded), cursors = MediaCursors(video = chat.videos[loaded - 1].messageId), endReached = false),
        )
        return MediaListViewModel(chatId, minSizeBytes = 0, maxSizeBytes = Long.MAX_VALUE, searchSource = { _, _ -> chat }, knownVideos = { emptyList() })
    }

    private fun MediaListViewModel.names(): List<String> = (state.value as? UiState.Content)?.value?.items.orEmpty().map { it.fileName }

    private fun TestScope.at() = testScheduler.currentTime

    @Test
    fun `the bug, a dotted name past the loaded pages is found by the chat's search`() = runTest(dispatcher, timeout = 1.minutes) {
        val chat = Chat(mapOf(200 to dotted))
        val model = open(chat)
        runCurrent()
        val started = at()
        model.search("resident evil")
        runCurrent()
        // Nothing loaded matches: the listing from before stays up under the field's spinner, never a blank.
        assertTrue(model.searching.value)
        assertTrue(model.state.value is UiState.Content)
        while (model.searching.value && at() - started < 30_000) advanceTimeBy(100)
        assertEquals(listOf(dotted), model.names())
        val took = at() - started
        println("[chat-search] dotted name 200 videos deep: found at $took ms, asked ${chat.asked}, ${chat.histories} history pages")
        assertTrue("found within 3 s, at $took ms", took <= 3_000)
        val content = (model.state.value as UiState.Content).value
        assertTrue("says only the newest part was read", content.searchNote != null && content.canSearchDeeper)
    }

    @Test
    fun `a loaded match shows at once, before Telegram answers`() = runTest(dispatcher, timeout = 1.minutes) {
        val chat = Chat(mapOf(40 to dotted))
        val model = open(chat)
        runCurrent()
        model.search("raccoon")
        assertEquals(listOf(dotted), model.names())
        assertTrue(model.searching.value)
        advanceUntilIdle()
        assertEquals(listOf(dotted), model.names())
        assertTrue(!model.searching.value)
    }

    @Test
    fun `typing is asked once, for the words it rested on`() = runTest(dispatcher, timeout = 1.minutes) {
        val chat = Chat(mapOf(40 to dotted))
        val model = open(chat)
        runCurrent()
        for (typed in listOf("r", "re", "res", "resid", "resident", "resident e", "resident ev", "resident evil")) {
            model.type(typed)
            advanceTimeBy(120)
        }
        assertTrue("nothing asked while typing: ${chat.asked}", chat.asked.isEmpty())
        advanceTimeBy(SearchSettle.MS + 10)
        runCurrent()
        assertEquals(listOf("resident evil"), chat.asked.distinct().take(1))
        advanceUntilIdle()
        assertTrue("only the last words: ${chat.asked}", chat.asked.all { it.contains("resident") || it == "evil" })
        assertEquals(listOf(dotted), model.names())
    }

    @Test
    fun `a slow answer for old words never lands over the new ones`() = runTest(dispatcher, timeout = 1.minutes) {
        val chat = Chat(mapOf(10 to "Night Train.mkv", 20 to "Harbour Days.mkv"), latencyMs = 5_000)
        val model = open(chat, loaded = 5)
        runCurrent()
        model.search("night")
        advanceTimeBy(1_000)
        model.search("harbour")
        advanceUntilIdle()
        assertEquals(listOf("Harbour Days.mkv"), model.names())
        assertEquals("harbour", model.searchText)
    }

    @Test
    fun `Keep looking reads the next part of the chat and an empty search says what was read`() = runTest(dispatcher, timeout = 1.minutes) {
        val chat = Chat(mapOf(2_000 to dotted))
        val model = open(chat)
        runCurrent()
        model.search("raccoon city")
        advanceUntilIdle()
        val empty = model.state.value
        assertTrue("empty with Keep looking: $empty", empty is UiState.Empty && empty.action == StateAction.KeepLooking)
        var presses = 0
        while (model.names().isEmpty() && presses < 10) {
            model.act(StateAction.KeepLooking)
            advanceUntilIdle()
            presses++
        }
        assertEquals(listOf(dotted), model.names())
        // Rounds of 500: the first answer read videos 0 to 499, so 2,000 is the fourth press's.
        assertEquals(4, presses)
    }

    @Test
    fun `a 20,000 video chat of a 3,000 chat account answers within 3 s on a bounded budget`() = runTest(dispatcher, timeout = 5.minutes) {
        val account = SyntheticAccount(backgroundScope, { testScheduler.currentTime })
        val named = mapOf(150 to dotted, 9_000 to "Resident Evil 2002.mkv")
        val source = account.chatSearch(1L, named)
        PageQueries.cache.put(QueryKey.ChatMedia(1L), ChatGrid(items = emptyList(), cursors = MediaCursors(), endReached = false))
        val model = MediaListViewModel(1L, 0, Long.MAX_VALUE, searchSource = { _, _ -> source }, knownVideos = { emptyList() })
        try {
            runCurrent()
            val started = at()
            model.search("resident evil")
            var firstAt = -1L
            while (model.searching.value || firstAt < 0) {
                if (firstAt < 0 && model.names().isNotEmpty()) firstAt = at() - started
                advanceTimeBy(50)
                if (at() - started > 60_000) break
            }
            val calls = (account.calls["searchChatMessages"] ?: 0) + (account.calls["getChatHistory"] ?: 0)
            println("[chat-search] 20,000 videos: first result at $firstAt ms, ${model.names()}, calls ${account.calls}")
            assertTrue("first result at $firstAt ms", firstAt in 0..3_000)
            // Telegram's word search finds the spaced name, 9,000 deep; the dotted one is in the
            // newest 500 read beside it, which Telegram's search cannot see.
            assertEquals(setOf("Resident Evil 2002.mkv", dotted), model.names().toSet())
            assertTrue("calls $calls", calls <= 7 * 3 + 7)
            assertTrue("bounded: ${model.names().size}", model.names().size <= 60)
        } finally {
            PageQueries.cache.remove { it.chatId == 1L }
        }
    }

    private companion object {
        const val COUNT = 5_000
    }
}
