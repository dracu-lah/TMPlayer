package com.tmplayer.ui.browse

import com.tmplayer.data.AllChatsCursor
import com.tmplayer.data.AllChatsPage
import com.tmplayer.data.ChatGate
import com.tmplayer.data.ForumTopicSummary
import com.tmplayer.data.TopicSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/**
 * The search by topic name (U14) against [SyntheticAccount]: 3,000 chats and 1,500 archived, one
 * group in seven a forum of forty topics. What is held to: the stated budget
 * ([TopicSearch.CHATS_PER_SEARCH] groups asked, [TopicSearch.AT_ONCE] at once,
 * [TopicSearch.FORUM_CHECKS_PER_SEARCH] looked up), nothing before the typing settles, nothing for
 * the words typed over, the first topics within 3 s, the kids lock, and a failure said once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TopicSearchScaleTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        // No choice of chats left over from another test: the gate is the kids lock alone.
        com.tmplayer.data.AddedChats.set(com.tmplayer.data.AddedChoice())
        ChatGate.set(null)
    }

    @After fun tearDown() {
        ChatGate.set(null)
        Dispatchers.resetMain()
    }

    private val noVideos = VideoSearchSource { _, _, _ -> AllChatsPage(emptyList(), AllChatsCursor(videoDone = true, documentDone = true)) }

    private fun model(topics: TopicSource, settle: Long = 400) =
        GlobalSearchViewModel(noVideos, known = { emptyList() }, work = dispatcher, settleMs = settle, topicSource = topics)

    @Test
    fun `a search on a large account stays within its budget and answers within 3 s`() = runTest(dispatcher, timeout = 5.minutes) {
        val account = SyntheticAccount(backgroundScope, { testScheduler.currentTime })
        val search = model(account.topics)
        search.chatList = account.mainList() + account.archivedList()
        val typedAt = testScheduler.currentTime
        search.type("season 2")
        runCurrent()
        // Nothing is asked of Telegram before the typing settles.
        assertEquals(null, account.calls["getForumTopics"])
        var firstAt = -1L
        while (testScheduler.currentTime - typedAt < 10_000) {
            advanceTimeBy(100)
            runCurrent()
            if (firstAt < 0 && search.state.value.topics.isNotEmpty()) firstAt = testScheduler.currentTime - typedAt
        }
        advanceUntilIdle()
        val state = search.state.value
        assertTrue("first topics after ${firstAt} ms", firstAt in 0..3_000)
        assertTrue((account.calls["getForumTopics"] ?: 0) <= TopicSearch.CHATS_PER_SEARCH)
        assertTrue((account.calls["isForum"] ?: 0) <= TopicSearch.FORUM_CHECKS_PER_SEARCH)
        assertTrue("at once: ${account.maxInFlight}", account.maxInFlight <= TopicSearch.AT_ONCE)
        assertTrue(state.topics.isNotEmpty() && state.topics.size <= TopicSearch.MAX_HITS)
        assertTrue(state.topics.all { account.isForumChat(it.chatId) && it.topic.name == "Season 2" })
        // The same name in many chats: one hit each.
        assertEquals(state.topics.size, state.topics.map { it.chatId }.distinct().size)
        assertTrue(state.topicsPartial)
        assertFalse(state.topicsSearching)
        assertNull(state.topicsError)
    }

    @Test
    fun `letters typed over are never asked, only the words that settled`() = runTest(dispatcher) {
        val asked = mutableListOf<String>()
        val source = object : TopicSource {
            override suspend fun isForum(chatId: Long) = true
            override suspend fun search(chatId: Long, query: String, limit: Int): List<ForumTopicSummary> {
                asked += query
                return listOf(ForumTopicSummary(1, "Movies"))
            }
        }
        val search = model(source, settle = 300)
        search.chatList = listOf(group(1))
        search.type("mo")
        advanceTimeBy(100)
        search.type("mov")
        advanceTimeBy(100)
        search.type("movies")
        advanceUntilIdle()
        assertEquals(listOf("movies"), asked)
        assertEquals("Movies", search.state.value.topics.single().topic.name)
        search.type("")
        advanceUntilIdle()
        assertTrue(search.state.value.topics.isEmpty())
    }

    @Test
    fun `the kids lock keeps its chats out, before and after the search`() = runTest(dispatcher) {
        val asked = mutableListOf<Long>()
        val source = object : TopicSource {
            override suspend fun isForum(chatId: Long) = true
            override suspend fun search(chatId: Long, query: String, limit: Int): List<ForumTopicSummary> {
                asked += chatId
                return listOf(ForumTopicSummary(1, "Movies"))
            }
        }
        // The search's own gate, as ChatGate.browsing hands it over, set by the test in step.
        val gate = kotlinx.coroutines.flow.MutableStateFlow<Set<Long>?>(setOf(2L, 3L))
        val search = GlobalSearchViewModel(noVideos, known = { emptyList() }, work = dispatcher, settleMs = 0, topicSource = source, gate = gate)
        search.chatList = listOf(group(1), group(2), group(3))
        search.type("movies")
        advanceUntilIdle()
        assertEquals(setOf(2L, 3L), asked.toSet())
        gate.value = setOf(3L)
        advanceUntilIdle()
        assertEquals(listOf(3L), search.state.value.topics.map { it.chatId })
    }

    @Test
    fun `a search every group refused says so once, with Try again, and one that half worked does not`() = runTest(dispatcher) {
        var refuse = setOf(1L, 2L)
        val source = object : TopicSource {
            override suspend fun isForum(chatId: Long) = true
            override suspend fun search(chatId: Long, query: String, limit: Int): List<ForumTopicSummary> {
                if (chatId in refuse) throw IllegalStateException("Internal Server Error")
                return listOf(ForumTopicSummary(1, "Movies", hasVideos = false))
            }
        }
        val search = model(source, settle = 0)
        search.chatList = listOf(group(1), group(2))
        search.type("movies")
        advanceUntilIdle()
        assertNotNull(search.state.value.topicsError)
        assertTrue(search.state.value.topics.isEmpty())
        refuse = setOf(1L)
        search.retryTopics()
        advanceUntilIdle()
        assertNull(search.state.value.topicsError)
        // A topic with no videos is still listed, for the row to dim it.
        assertEquals(listOf(2L), search.state.value.topics.map { it.chatId })
        assertFalse(search.state.value.topics.single().topic.hasVideos)
    }

    @Test
    fun `topics already known from a group's tabs are matched without asking`() = runTest(dispatcher) {
        var asked = 0
        val source = object : TopicSource {
            override suspend fun isForum(chatId: Long) = true
            override fun known(chatId: Long) = if (chatId == 1L) listOf(ForumTopicSummary(4, "Café talks")) else null
            override suspend fun search(chatId: Long, query: String, limit: Int): List<ForumTopicSummary> {
                asked++
                return emptyList()
            }
        }
        val search = model(source, settle = 0)
        search.chatList = listOf(group(1))
        search.type("cafe")
        advanceUntilIdle()
        assertEquals(0, asked)
        assertEquals(listOf(4), search.state.value.topics.map { it.topic.id })
    }

    private fun group(id: Long) = com.tmplayer.data.ChatSummary(
        id = id, title = "Group $id", miniThumbnail = null, photoFileId = 0, kind = com.tmplayer.data.ChatKind.Group,
    )
}
