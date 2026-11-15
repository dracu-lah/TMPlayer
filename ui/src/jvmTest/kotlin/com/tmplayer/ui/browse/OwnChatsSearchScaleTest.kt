package com.tmplayer.ui.browse

import com.tmplayer.data.AddedChat
import com.tmplayer.data.AddedChoice
import com.tmplayer.data.AddedMode
import com.tmplayer.data.AllChatsCursor
import com.tmplayer.data.AllChatsPage
import com.tmplayer.data.AllChatsSearch
import com.tmplayer.data.ChatGate
import com.tmplayer.data.SearchFilters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/**
 * HX62 at scale, on [SyntheticAccount]'s 3,000 chats with 40 of them added: search lists the added
 * chats' videos only, its first results are in within 3 s, and it never makes the one account-wide
 * `searchMessages` call that would read the other 2,960 chats.
 *
 * The source stands in for [com.tmplayer.data.ChatRepository.searchAddedChats], which TDLib's
 * types keep out of a unit test: the same windows of [AllChatsSearch.CHATS_PER_PAGE] chats, three
 * at once, a video and a document search each, on the account's own latency and FLOOD_WAIT model.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnChatsSearchScaleTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        ChatGate.set(null)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `40 added chats of 3000 are searched in windows, first results within 3 s, never account wide`() = runTest(dispatcher, timeout = 5.minutes) {
        val account = SyntheticAccount(backgroundScope, { testScheduler.currentTime })
        assertEquals(3_000, account.mainList().size)
        val added = account.starred.toList().take(40)
        assertEquals(40, added.size)
        val choice = MutableStateFlow(AddedChoice(AddedMode.Picked, added.map { AddedChat(it, "chat $it") }))
        val windows = ArrayList<Int>()
        val source = object : VideoSearchSource {
            override suspend fun page(query: String, cursor: AllChatsCursor, filters: SearchFilters): AllChatsPage {
                val window = added.drop(cursor.chatsSearched).take(AllChatsSearch.CHATS_PER_PAGE)
                windows += window.size
                val reached = cursor.chatsSearched + window.size
                val items = window.chunked(3).flatMap { chunk ->
                    coroutineScope {
                        chunk.map { chat ->
                            async {
                                account.searchChat(chat, "video", 0, 30).first + account.searchChat(chat, "document", 0, 30).first
                            }
                        }.awaitAll().flatten()
                    }
                }
                val finished = reached >= added.size
                return AllChatsPage(items, cursor.copy(chatsSearched = reached, videoDone = finished, documentDone = finished))
            }

            override suspend fun everyChatPage(query: String, cursor: AllChatsCursor, filters: SearchFilters): AllChatsPage =
                AllChatsPage(account.searchAll("video", 60), AllChatsCursor(videoDone = true, documentDone = true))
        }
        val search = GlobalSearchViewModel(
            source, known = { emptyList() }, work = dispatcher, settleMs = 0,
            gate = MutableStateFlow(choice.value.ids), wholeGate = MutableStateFlow(null), choice = choice, cache = null,
        )
        val started = testScheduler.currentTime
        search.type("show")
        // The first window answers: eight chats, two calls each, three chats at once.
        while (search.state.value.shown.isEmpty()) {
            testScheduler.advanceTimeBy(100)
            testScheduler.runCurrent()
            assertTrue("no result after ${testScheduler.currentTime - started} ms", testScheduler.currentTime - started < 3_000)
        }
        val first = testScheduler.currentTime - started
        assertTrue("first results after $first ms", first < 3_000)
        advanceUntilIdle()
        val chatsInResults = search.state.value.groups.flatMap { g -> g.copies.map { it.chatId } }.toSet()
        assertTrue("results from chats that were not added: ${chatsInResults - added.toSet()}", added.containsAll(chatsInResults))
        assertTrue(chatsInResults.isNotEmpty())
        // No account-wide search, and every window within the page budget.
        assertNull(account.calls["searchMessages"])
        assertTrue(windows.all { it <= AllChatsSearch.CHATS_PER_PAGE })
        assertTrue("six calls in flight at most, saw ${account.maxInFlight}", account.maxInFlight <= 2 * 3)
        val tally = search.state.value.tally
        assertTrue(tally != null && tally.chats <= 40 && tally.videos > 0)
    }

    private fun kotlinx.coroutines.test.TestScope.settle() {
        testScheduler.advanceTimeBy(120_000)
        testScheduler.runCurrent()
    }

    @Test
    fun `the one-off search of the other chats makes one account wide call and the added chats are not asked again`() = runTest(dispatcher, timeout = 5.minutes) {
        val account = SyntheticAccount(backgroundScope, { testScheduler.currentTime })
        val added = account.starred.toList().take(40)
        val choice = MutableStateFlow(AddedChoice(AddedMode.Picked, added.map { AddedChat(it, "chat $it") }))
        var narrow = 0
        val source = object : VideoSearchSource {
            override suspend fun page(query: String, cursor: AllChatsCursor, filters: SearchFilters): AllChatsPage {
                narrow++
                val chat = added.first()
                return AllChatsPage(account.searchChat(chat, "video", 0, 30).first, AllChatsCursor(videoDone = true, documentDone = true))
            }

            override suspend fun everyChatPage(query: String, cursor: AllChatsCursor, filters: SearchFilters): AllChatsPage =
                AllChatsPage(account.searchAll("video", 60), AllChatsCursor(videoDone = true, documentDone = true))
        }
        val search = GlobalSearchViewModel(
            source, known = { emptyList() }, work = dispatcher, settleMs = 0,
            gate = MutableStateFlow(choice.value.ids), wholeGate = MutableStateFlow(null), choice = choice, cache = null,
        )
        search.type("show")
        settle()
        val before = narrow
        search.searchOtherChats()
        settle()
        assertEquals(1, account.calls["searchMessages"])
        // The wider search does not run the narrow one again; what it found stays listed.
        assertEquals(before, narrow)
        assertTrue(search.state.value.groups.flatMap { g -> g.copies.map { it.chatId } }.toSet().size > 1)
    }
}
