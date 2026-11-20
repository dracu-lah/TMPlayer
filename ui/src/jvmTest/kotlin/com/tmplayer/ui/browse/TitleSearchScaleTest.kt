package com.tmplayer.ui.browse

import com.tmplayer.data.AddedChat
import com.tmplayer.data.AddedChoice
import com.tmplayer.data.AddedMode
import com.tmplayer.data.AllChatsCursor
import com.tmplayer.data.AllChatsPage
import com.tmplayer.data.AllChatsSearch
import com.tmplayer.data.ChatGate
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SearchFilters
import com.tmplayer.online.MetaQuery
import com.tmplayer.online.PosterChoice
import com.tmplayer.online.PosterChoices
import com.tmplayer.online.WhereToWatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
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
 * HX41 at scale: [SyntheticAccount]'s 3,000 chats with 40 added, 100,000 videos already known to
 * the device. Typing a title letter by letter asks TMDB once, the first content is up within 3 s,
 * the titles are worked out from the whole answer in well under a frame budget per state, and
 * the search never reads the chats that were not added.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TitleSearchScaleTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        ChatGate.set(null)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a title over 3000 chats and 100000 videos asks TMDB once and shows within 3 s`() = runTest(dispatcher, timeout = 5.minutes) {
        val account = SyntheticAccount(backgroundScope, { testScheduler.currentTime })
        assertEquals(3_000, account.mainList().size)
        val added = account.starred.toList().take(40)
        val choice = MutableStateFlow(AddedChoice(AddedMode.Picked, added.map { AddedChat(it, "chat $it") }))
        val source = object : VideoSearchSource {
            override suspend fun page(query: String, cursor: AllChatsCursor, filters: SearchFilters): AllChatsPage {
                val window = added.drop(cursor.chatsSearched).take(AllChatsSearch.CHATS_PER_PAGE)
                val items = window.flatMap { chat -> account.searchChat(chat, "video", 0, 30).first }
                val reached = cursor.chatsSearched + window.size
                val finished = reached >= added.size
                return AllChatsPage(items, cursor.copy(chatsSearched = reached, videoDone = finished, documentDone = finished))
            }
        }
        val known = (0 until 100_000).map { i ->
            MediaItem(
                chatId = (i % 3_000 + 1).toLong(), messageId = i.toLong() * 16, fileId = i, title = "Holiday clip",
                sizeBytes = 300L * 1024 * 1024, durationSec = 1_200, mimeType = "video/mp4", thumbnailFileId = 0,
                miniThumbnail = null, date = 1_900_000_000 - i, fileName = "Holiday.Clip.mp4",
            )
        }
        val show = "Show ${added.first()}"
        var titleCalls = 0
        val titles = object : TitleSource {
            override suspend fun titles(query: MetaQuery): PosterChoices {
                titleCalls++
                return PosterChoices.Found(
                    listOf(
                        PosterChoice(1, true, show, 2020, "en", "", null),
                        PosterChoice(2, false, "$show The Film", 2021, "en", "", null),
                    ) + (3..10).map { PosterChoice(it, false, "Unrelated $it", 2000 + it, "en", "", null) },
                )
            }

            override suspend fun whereToWatch(title: PosterChoice): WhereToWatch = WhereToWatch.NoneHere("GB")
        }
        val search = GlobalSearchViewModel(
            source, known = { known }, work = dispatcher, settleMs = 300,
            gate = MutableStateFlow(choice.value.ids), wholeGate = MutableStateFlow(null), choice = choice, cache = null,
            titleSource = titles,
        )
        val started = testScheduler.currentTime
        show.indices.forEach { search.type(show.take(it + 1)) }
        while (search.state.value.shown.isEmpty()) {
            testScheduler.advanceTimeBy(100)
            testScheduler.runCurrent()
            assertTrue("no result after ${testScheduler.currentTime - started} ms", testScheduler.currentTime - started < 3_000)
        }
        // The rest of the windows, the account answering in the background.
        testScheduler.advanceTimeBy(120_000)
        testScheduler.runCurrent()
        // One TMDB question for the whole typing, and no account-wide read.
        assertEquals(1, titleCalls)
        assertNull(account.calls["searchMessages"])
        val state = search.state.value
        val wall = System.nanoTime()
        val hits = state.titleHits
        val ms = (System.nanoTime() - wall) / 1_000_000
        assertTrue("titles took $ms ms over ${state.groups.size} cards", ms < 500)
        assertEquals(show, hits.first().title.title)
        assertTrue("no copies of $show in ${state.groups.size} cards", hits.first().inChats)
        assertTrue(hits.first().groups.all { g -> g.copies.all { it.chatId in added } })
        // The film of the same words is not in the chats; the unrelated titles are left out.
        assertTrue(hits.any { it.title.tmdbId == 2 && it.notInChats })
        assertTrue(hits.none { it.title.title.startsWith("Unrelated") })
    }
}
