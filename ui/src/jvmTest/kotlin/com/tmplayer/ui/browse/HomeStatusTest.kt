package com.tmplayer.ui.browse

import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.MediaItem
import com.tmplayer.data.WorkStatus
import com.tmplayer.data.WorkStatusWords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
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
 * Home's status line (WP-93, LS-06): rows that still fail after their retries are counted ("2
 * chats could not be read for now"), every row failing is Failed, Try again says Updating and a
 * success clears it, and a refresh's Syncing ends by itself (its answers, or the time box).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeStatusTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val chats = (1L..3L).map { ChatSummary(id = it, title = "Chat $it", miniThumbnail = null, photoFileId = 0, kind = ChatKind.Channel) }

    private fun item(chat: Long) = MediaItem(
        chatId = chat, messageId = chat * 10, fileId = chat.toInt(), title = "Clip $chat", sizeBytes = 900_000_000, durationSec = 3_000,
        mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 1_000, fileName = "clip$chat.mp4",
    )

    /** Chats in here fail; [hang] makes every fetch wait for ever. */
    private var failing = setOf<Long>()
    private var hang = false

    private val source = object : HomeSource {
        override suspend fun chatMedia(chatId: Long): List<MediaItem> {
            if (hang) awaitCancellation()
            if (chatId in failing) throw java.io.IOException("Connection closed")
            return listOf(item(chatId))
        }
        override suspend fun recentMedia(): List<MediaItem> = emptyList()
        override suspend fun mediaItem(chatId: Long, messageId: Long): MediaItem? = null
    }

    private fun TestScope.model() = HomeViewModel(
        source,
        work = dispatcher,
        disk = dispatcher,
        connected = MutableStateFlow(true),
        clock = { testScheduler.currentTime },
    )

    /** Past every retry a failed fetch gets ([HomeFetches.RETRY_WAITS_MS]). */
    private fun TestScope.pastRetries() {
        advanceTimeBy(HomeFetches.RETRY_WAITS_MS.sum() + 10_000)
        runCurrent()
    }

    @Test
    fun `rows failing after their retries are counted`() = runTest(dispatcher) {
        failing = setOf(2L, 3L)
        val model = model()
        model.fill(chats)
        pastRetries()
        val status = model.status.value
        assertTrue("$status", status is WorkStatus.Partial)
        assertEquals(2, (status as WorkStatus.Partial).failedCount)
        assertEquals("2 chats could not be read for now.", WorkStatusWords.line(status, 0))
    }

    @Test
    fun `every row failing is failed, and try again then a success clears it`() = runTest(dispatcher) {
        failing = chats.map { it.id }.toSet()
        val model = model()
        model.fill(chats)
        pastRetries()
        assertTrue("${model.status.value}", model.status.value is WorkStatus.Failed)

        failing = emptySet()
        hang = true
        model.retryFailedRows()
        runCurrent()
        assertTrue("Try again says Updating: ${model.status.value}", model.status.value is WorkStatus.Syncing)
        hang = false
        // The hung fetches time out and are retried; the line is fine by then.
        advanceTimeBy(HomeViewModel.ROW_TIMEOUT_MS + HomeFetches.RETRY_WAITS_MS.first() + 5_000)
        runCurrent()
        assertEquals(WorkStatus.Idle, model.status.value)
        assertEquals(chats.size, model.loaded.value.count { it.value.isNotEmpty() })
    }

    @Test
    fun `a refresh says syncing until its answers are in`() = runTest(dispatcher) {
        val model = model()
        model.fill(chats)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(WorkStatus.Idle, model.status.value)
        model.refreshInPlace()
        assertTrue(model.status.value is WorkStatus.Syncing || model.refreshing.value)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(WorkStatus.Idle, model.status.value)
    }

    @Test
    fun `a refresh that hangs stops saying syncing after its time box`() = runTest(dispatcher) {
        val model = model()
        model.fill(chats)
        advanceTimeBy(5_000)
        runCurrent()
        hang = true
        model.refreshInPlace()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue("${model.status.value}", model.status.value is WorkStatus.Syncing)
        advanceTimeBy(HomeViewModel.SYNC_BOX_MS)
        runCurrent()
        assertTrue("still fetching", model.refreshing.value)
        assertEquals(WorkStatus.Idle, model.status.value)
        hang = false
    }

    @Test
    fun `the status from its parts`() {
        assertEquals(WorkStatus.Idle, HomeViewModel.statusOf(false, emptySet(), 3, 0))
        assertEquals(WorkStatus.Syncing(), HomeViewModel.statusOf(true, emptySet(), 3, 0))
        // Art and shows failing are not rows.
        assertEquals(WorkStatus.Idle, HomeViewModel.statusOf(false, setOf("art:1_2", "show:x"), 3, 0))
        // Recently added alone failing is no chat count, but with every chat failed it is all of Home.
        assertEquals(WorkStatus.Idle, HomeViewModel.statusOf(false, setOf("recent"), 3, 0))
        val all = HomeViewModel.statusOf(true, setOf("chat:1", "chat:2", "recent"), 3, 42)
        assertEquals(WorkStatus.Failed(com.tmplayer.data.Failures.DEFAULT, keptAt = 42), all)
        assertEquals(null, (HomeViewModel.statusOf(false, setOf("chat:1"), 1, 0) as WorkStatus.Failed).keptAt)
        // A failure beats a refresh running.
        assertTrue(HomeViewModel.statusOf(true, setOf("chat:1"), 3, 0) is WorkStatus.Partial)
    }
}
