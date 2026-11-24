package com.tmplayer.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import com.tmplayer.data.ChatGrid
import com.tmplayer.data.MediaCursors
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaPage
import com.tmplayer.data.PageQueries
import com.tmplayer.data.QueryKey
import com.tmplayer.data.WorkStatus
import com.tmplayer.ui.components.StateAction
import com.tmplayer.ui.components.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.reflect.KClass

/**
 * What a chat's grid says while it changes (WP-94): a kept grid read again says Updating (quietly),
 * a failed read keeps the grid and says since when, Try again says Updating and then Up to date,
 * a walk through posts the size limits reject counts under the skeleton, and the answer of a topic
 * or a chat that was left never lands in the one now shown (the race the user hit when changing
 * chats). No TDLib: the pages come from [GridPages].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaListStatusTest {

    private val dispatcher = StandardTestDispatcher()
    private var chatId = 0L

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        chatId = 900_000L + System.nanoTime() % 90_000L
    }

    @After fun tearDown() {
        PageQueries.cache.remove { it is QueryKey.ChatMedia && it.chatId == chatId }
        Dispatchers.resetMain()
    }

    private fun video(id: Long, size: Long = 500L) = MediaItem(
        chatId = chatId, messageId = id, fileId = id.toInt(), title = "v$id", sizeBytes = size, durationSec = 60,
        mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = id.toInt(), fileName = "v$id.mp4",
    )

    private fun page(vararg ids: Long, end: Boolean = false) =
        MediaPage(ids.map { video(it) }, MediaCursors(video = ids.minOrNull() ?: 0, document = 1, animation = 1), end)

    private fun keep(vararg ids: Long, at: Long = System.currentTimeMillis() - 600_000L, topic: Int = 0) {
        PageQueries.cache.put(
            QueryKey.ChatMedia(chatId, topic),
            ChatGrid(ids.map { video(it) }, MediaCursors(video = ids.min(), document = 1, animation = 1), endReached = false),
            at,
        )
    }

    private fun model(min: Long = 0, pages: GridPages) = MediaListViewModel(chatId, min, Long.MAX_VALUE, pages = pages)

    private fun MediaListViewModel.ids() = (state.value as? UiState.Content)?.value?.items?.map { it.messageId }

    @Test
    fun `a stale kept grid says Syncing quietly while it is read again, then nothing`() = runTest(dispatcher) {
        keep(30, 20, 10)
        val gate = CompletableDeferred<MediaPage>()
        val model = model { _, _, _ -> gate.await() }
        runCurrent()
        assertEquals("the kept grid is drawn at once", listOf(30L, 20L, 10L), model.ids())
        assertTrue("${model.status.value}", model.status.value is WorkStatus.Syncing)
        assertFalse("a revalidate is quiet: not asked", model.statusAsked.value)

        gate.complete(page(40, 30, end = false))
        // The cache's own scope answers on a real thread: waited for, not advanced.
        settle { model.status.value == WorkStatus.Idle }
        assertEquals(WorkStatus.Idle, model.status.value)
        assertEquals(listOf(40L, 30L, 20L, 10L), model.ids())
    }

    @Test
    fun `a revalidate that fails keeps the grid and says since when, with Try again after`() = runTest(dispatcher) {
        val keptAt = System.currentTimeMillis() - 600_000L
        keep(30, 20, 10, at = keptAt)
        var down = true
        val model = model { _, _, _ -> if (down) throw java.io.IOException("Connection closed") else page(50, 40, 30) }
        runCurrent()
        val failed = model.status.value
        assertTrue("$failed", failed is WorkStatus.Failed)
        assertEquals("the time the grid was fresh", keptAt, (failed as WorkStatus.Failed).keptAt)
        assertEquals("the old grid stays", listOf(30L, 20L, 10L), model.ids())

        // Try again: Updating at once, as a press, then Up to date.
        down = false
        val gate = CompletableDeferred<MediaPage>()
        val later = model { _, _, _ -> gate.await() }
        later.load()
        runCurrent()
        assertTrue(later.status.value is WorkStatus.Syncing)
        assertTrue("a press shows its words at once", later.statusAsked.value)
        gate.complete(page(50, 40, 30))
        runCurrent()
        assertTrue("${later.status.value}", later.status.value is WorkStatus.Updated)
        assertEquals(listOf(50L, 40L, 30L), later.ids())
    }

    @Test
    fun `a Refresh that fails keeps the previous grid and sets Failed`() = runTest(dispatcher) {
        keep(30, 20, 10, at = System.currentTimeMillis())
        var calls = 0
        val model = model { _, _, _ -> calls++; throw java.io.IOException("Connection closed") }
        runCurrent()
        assertEquals("fresh: not read again", 0, calls)
        assertEquals(WorkStatus.Idle, model.status.value)

        model.load()
        runCurrent()
        assertEquals(1, calls)
        assertEquals("the previous grid is back", listOf(30L, 20L, 10L), model.ids())
        val failed = model.status.value
        assertTrue("$failed", failed is WorkStatus.Failed)
        assertTrue("since when it was fresh", (failed as WorkStatus.Failed).keptAt != null)
    }

    @Test
    fun `a Syncing that nothing ends is ended by its time box`() = runTest(dispatcher) {
        keep(30, 20, 10)
        val model = model { _, _, _ -> awaitCancellation() }
        runCurrent()
        assertTrue(model.status.value is WorkStatus.Syncing)
        advanceTimeBy(ChatListStatus.SYNC_BOX_MS + 1_000)
        runCurrent()
        assertEquals("nothing spins for ever", WorkStatus.Idle, model.status.value)
    }

    @Test
    fun `the walk through posts outside the size limits counts after 3 s, at most every 2 s`() = runTest(dispatcher) {
        var next = 10_000L
        val model = model(min = 1_000) { cursors, _, _ ->
            delay(1_000)
            // Forty files a page, every one of them under the size floor; the chat goes on.
            val from = if (cursors.video == 0L) next else cursors.video
            MediaPage(
                (0 until 40).map { video(from - it, size = 10) },
                MediaCursors(video = from - 40, document = 1, animation = 1),
                endReached = false,
            )
        }
        runCurrent()
        assertTrue("the skeleton, with its words", (model.state.value as UiState.Loading).label.startsWith("Finding"))
        advanceTimeBy(3_100)
        val first = (model.state.value as UiState.Loading).label
        assertTrue(first, first.startsWith("Looked through ") && first.endsWith(" posts so far"))
        advanceTimeBy(1_000)
        assertEquals("not again inside 2 s", first, (model.state.value as UiState.Loading).label)
        advanceTimeBy(1_500)
        val second = (model.state.value as UiState.Loading).label
        assertNotEquals("the count rises", first, second)
        assertTrue(second, second.startsWith("Looked through "))
    }

    @Test
    fun `a 20,000 post chat outside the limits stops by itself, empty, with Keep looking and nothing left Syncing`() = runTest(dispatcher) {
        var reads = 0
        val model = model(min = 1_000) { cursors, _, _ ->
            reads++
            val from = if (cursors.video == 0L) 20_000L else cursors.video
            MediaPage(
                (0 until 40).map { video(from - it, size = 10) },
                MediaCursors(video = from - 40, document = 1, animation = 1),
                endReached = false,
            )
        }
        advanceTimeBy(60_000)
        runCurrent()
        val state = model.state.value
        assertTrue("$state", state is UiState.Empty)
        assertEquals("the walk's cap stops it, Keep looking goes on", StateAction.KeepLooking, (state as UiState.Empty).action)
        assertTrue("one first page and the capped walk, not the chat: $reads reads", reads <= 10)
        assertEquals(WorkStatus.Idle, model.status.value)
        // Idle: nothing of it wakes up again.
        val before = model.state.value
        advanceTimeBy(60_000)
        assertEquals(before, model.state.value)
    }

    @Test
    fun `the old topic's late answer never lands in the topic now shown`() = runTest(dispatcher) {
        val slow = CompletableDeferred<MediaPage>()
        val model = model { _, _, topic -> if (topic == 0) slow.await() else page(702, 701, end = true) }
        runCurrent()
        assertTrue(model.state.value is UiState.Loading)

        model.selectTopic(7)
        runCurrent()
        assertEquals(listOf(702L, 701L), model.ids())

        slow.complete(page(9, 8, 7, end = true))
        runCurrent()
        assertEquals("the left topic's answer is ignored", listOf(702L, 701L), model.ids())
        assertEquals(WorkStatus.Idle, model.status.value)
        assertEquals("and not kept under the topic it was not asked for", null, PageQueries.cache.data<ChatGrid>(QueryKey.ChatMedia(chatId, 7))?.items?.firstOrNull { it.messageId < 100 })
    }

    @Test
    fun `a topic's late answer after a Refresh of another listing is ignored too`() = runTest(dispatcher) {
        keep(30, 20, 10)
        val old = CompletableDeferred<MediaPage>()
        var answers = 0
        val model = model { _, _, topic ->
            answers++
            if (answers == 1) old.await() else page(60, 50, end = true)
        }
        runCurrent()
        assertEquals(listOf(30L, 20L, 10L), model.ids())
        // Refresh while the revalidate is still out: only the newer read counts.
        model.load()
        runCurrent()
        assertEquals(listOf(60L, 50L), model.ids())
        old.complete(page(99, 98, end = true))
        runCurrent()
        assertEquals(listOf(60L, 50L), model.ids())
    }

    @Test
    fun `a chat that is left stops reading and its late answer lands nowhere`() = runTest(dispatcher) {
        var cancelled = false
        val gate = CompletableDeferred<MediaPage>()
        val store = ViewModelStore()
        val provider = ViewModelProvider.create(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T =
                    model { _, _, _ ->
                        try {
                            gate.await()
                        } catch (e: CancellationException) {
                            cancelled = true
                            throw e
                        }
                    } as T
            },
        )
        val left = provider[MediaListViewModel::class]
        runCurrent()
        assertTrue(left.state.value is UiState.Loading)
        assertFalse(cancelled)

        // Changing to another chat clears this one's view model.
        store.clear()
        runCurrent()
        assertTrue("the read of the chat left is cancelled, not waited out", cancelled)
        gate.complete(page(9, 8, end = true))
        runCurrent()
        assertTrue("its answer lands nowhere: ${left.state.value}", left.state.value is UiState.Loading)
    }

    /** Runs the test dispatcher until [done], giving the cache's own threads up to 5 s to answer. */
    private fun TestScope.settle(done: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < until) {
            runCurrent()
            if (done()) return
            Thread.sleep(10)
        }
    }
}
