package com.tmplayer.ui.browse

import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.SettingsStore
import com.tmplayer.ui.components.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The "first visit" tip belongs to the one launch on an install that has never seen the chat list.
 * TDLib never answers in these tests, so what the screen shows is decided by the snapshot alone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatListFirstVisitTest {

    private val dispatcher = StandardTestDispatcher()
    private val dir: File = Files.createTempDirectory("chatlist").toFile()
    private val file = dir.resolve(SettingsStore.FILE_NAME)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        Dispatchers.resetMain()
        dir.deleteRecursively()
    }

    private val chats = listOf(
        ChatSummary(id = 1, title = "Films", miniThumbnail = null, photoFileId = 0, kind = ChatKind.Channel),
        ChatSummary(id = 2, title = "Shows", miniThumbnail = null, photoFileId = 0, kind = ChatKind.Channel),
    )

    /** The store reads on its own thread, so the wait is measured on the clock, not in test time. */
    private suspend fun <T> inRealTime(block: suspend () -> T): T =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(5_000) { block() } }

    @Test
    fun `a list seen before opens on it, with no skeleton and no tip`() = runTest(dispatcher) {
        val store = SettingsStore(SettingsStore.openDataStore(file))
        store.saveChatSnapshot(chats)

        val model = ChatListViewModel(store)
        val shown = inRealTime { model.state.first { it !is UiState.Loading } }

        assertTrue(shown is UiState.Content)
        assertEquals(chats.map { it.id }, (shown as UiState.Content).value.chats.map { it.id })
    }

    @Test
    fun `an install that has never had a list says the first sync is the slow one`() = runTest(dispatcher) {
        val model = ChatListViewModel(SettingsStore(SettingsStore.openDataStore(file)))
        val shown = inRealTime { model.state.first { (it as? UiState.Loading)?.tip != null } }

        assertEquals(FIRST_LOAD_TIP, (shown as UiState.Loading).tip)
    }

    @Test
    fun `a snapshot that cannot be read is not taken for a first visit`() = runTest(dispatcher) {
        file.parentFile.mkdirs()
        file.writeBytes(byteArrayOf(0x7f, 0x01, 0x02, 0x03, 0x04))
        val model = ChatListViewModel(SettingsStore(SettingsStore.openDataStore(file)))

        // The read is attempted and fails; give it the time a real one would take to answer.
        repeat(50) {
            Thread.sleep(10)
            testScheduler.advanceUntilIdle()
        }
        val state = model.state.value
        assertTrue(state is UiState.Loading)
        assertNull((state as UiState.Loading).tip)
    }
}
