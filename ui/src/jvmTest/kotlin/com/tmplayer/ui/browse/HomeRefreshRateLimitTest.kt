package com.tmplayer.ui.browse

import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.MediaItem
import com.tmplayer.data.Td
import com.tmplayer.data.WorkStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F5 on the desktop's Home during a Telegram flood wait (LS-19): nothing is asked, because asking
 * extends the wait; the seconds left come back for the toast and the status line counts them down.
 * Outside a wait it is the plain refresh.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeRefreshRateLimitTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val chats = (1L..3L).map { ChatSummary(id = it, title = "Chat $it", miniThumbnail = null, photoFileId = 0, kind = ChatKind.Channel) }

    private var fetches = 0

    private val source = object : HomeSource {
        override suspend fun chatMedia(chatId: Long): List<MediaItem> {
            fetches++
            return emptyList()
        }
        override suspend fun recentMedia(): List<MediaItem> {
            fetches++
            return emptyList()
        }
        override suspend fun mediaItem(chatId: Long, messageId: Long): MediaItem? = null
    }

    private fun TestScope.model() = HomeViewModel(
        source,
        work = dispatcher,
        disk = dispatcher,
        connected = MutableStateFlow(true),
        clock = { testScheduler.currentTime },
    )

    @Test
    fun `during a flood wait nothing is asked and the seconds come back`() = runTest(dispatcher) {
        val model = model()
        model.fill(chats)
        advanceTimeBy(5_000)
        runCurrent()
        // Eleven seconds, just over the quiet threshold: the one test that records a wait.
        Td.noteFailure("FLOOD_WAIT_11")
        val before = fetches
        val waiting = model.refreshInPlaceUnlessRateLimited()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue("waiting $waiting", waiting in 1..11)
        assertEquals("no fetch during the wait", before, fetches)
        assertTrue("${model.status.value}", model.status.value is WorkStatus.RateLimited)
    }
}
