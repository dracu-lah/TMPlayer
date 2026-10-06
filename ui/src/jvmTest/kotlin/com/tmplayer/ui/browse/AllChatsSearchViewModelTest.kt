package com.tmplayer.ui.browse

import com.tmplayer.data.AllChatsCursor
import com.tmplayer.data.AllChatsPage
import com.tmplayer.data.MediaItem
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AllChatsSearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun video(id: Long, name: String) = MediaItem(
        chatId = id % 3 + 1,
        messageId = id,
        fileId = 1,
        title = name,
        sizeBytes = 1,
        durationSec = 60,
        mimeType = "video/mp4",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = id.toInt(),
        fileName = name,
    )

    /** Telegram as a fixture: two pages for "harbour", nothing for anything longer. */
    private val asked = mutableListOf<Pair<String, AllChatsCursor>>()
    private val source = VideoSearchSource { query, cursor ->
        asked += query to cursor
        when {
            query != "harbour" -> AllChatsPage(emptyList(), AllChatsCursor(videoDone = true, documentDone = true))
            cursor.videoOffset.isEmpty() -> AllChatsPage(
                listOf(video(1, "Harbour.Notes.S01E01.mkv"), video(2, "harbour-sunset.mp4")),
                AllChatsCursor(videoOffset = "next", documentDone = true),
            )
            else -> AllChatsPage(
                listOf(video(3, "Harbour.Notes.S01E02.mkv")),
                AllChatsCursor(videoOffset = "next", videoDone = true, documentDone = true),
            )
        }
    }

    @Test
    fun `typing settles before Telegram is asked, and only the last query goes`() = runTest(dispatcher) {
        val model = AllChatsSearchViewModel(source, settleMs = 100)
        model.search("h")
        model.search("ha")
        model.search("harbour")
        assertTrue(model.state.value.loading)
        advanceUntilIdle()
        assertEquals(listOf("harbour"), asked.map { it.first })
        assertEquals(listOf(2L, 1L), model.state.value.videos.map { it.messageId })
        assertFalse(model.state.value.loading)
        assertFalse(model.state.value.endReached)
    }

    @Test
    fun `scrolling on pages in more, and the end is reported`() = runTest(dispatcher) {
        val searched = mutableListOf<String>()
        val model = AllChatsSearchViewModel(source, onSearched = { searched += it }, settleMs = 0)
        model.search("harbour")
        advanceUntilIdle()
        model.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(2L, 1L, 3L), model.state.value.videos.map { it.messageId })
        assertTrue(model.state.value.endReached)
        assertEquals("next", asked.last().second.videoOffset)
        assertEquals(listOf("harbour"), searched)
        model.loadMore()
        advanceUntilIdle()
        assertEquals(2, asked.size)
    }

    @Test
    fun `several words that find nothing fall back to the longest, held to the whole query`() = runTest(dispatcher) {
        val model = AllChatsSearchViewModel(source, settleMs = 0)
        model.search("harbour notes")
        advanceUntilIdle()
        assertEquals(listOf("harbour notes", "harbour"), asked.map { it.first })
        // Both answer "harbour notes" by the forgiving matcher's half-the-words rule.
        assertEquals(2, model.state.value.videos.size)
        model.loadMore()
        advanceUntilIdle()
        assertEquals("harbour", asked.last().first)
    }

    @Test
    fun `a blank query clears, and a failure says so and can be retried`() = runTest(dispatcher) {
        var fail = true
        val model = AllChatsSearchViewModel(
            { query, cursor -> if (fail) error("Telegram is away") else source.page(query, cursor) },
            settleMs = 0,
        )
        model.search("harbour")
        advanceUntilIdle()
        assertNotNull(model.state.value.error)
        fail = false
        model.retry()
        advanceUntilIdle()
        assertEquals(2, model.state.value.videos.size)
        model.search("  ")
        assertEquals(VideoSearchState(), model.state.value)
    }
}
