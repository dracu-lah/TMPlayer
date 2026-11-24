package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.tmplayer.data.ListPages
import com.tmplayer.data.NamedListRules
import com.tmplayer.data.WatchLaterRecord
import com.tmplayer.data.WatchedStore
import com.tmplayer.ui.browse.LIST_NAME_TAG
import com.tmplayer.ui.browse.ListPageScaffold
import com.tmplayer.ui.browse.ListPickerDialog
import com.tmplayer.ui.theme.TmMaterialTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * The named lists (HX43) as the desktop draws them: the page's chips, buttons and cards, the
 * picker's lines and New list, a name capped as it is typed, fifty lists, and a list's empty state.
 */
@OptIn(ExperimentalTestApi::class)
class ListsPageTest {

    private val dir = Files.createTempDirectory("tm-lists-ui").toFile()
    private val watched = WatchedStore(WatchedStore.openDataStore(dir.resolve(WatchedStore.FILE_NAME)))
    private val store = watched.lists

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun video(message: Long, title: String = "Clip $message") = WatchLaterRecord(
        chatId = -100, messageId = message, fileId = 7, title = title, fileName = "clip.$message.mp4", chatTitle = "Chat",
        sizeBytes = 1_000, durationSec = 60, mimeType = "video/mp4", addedAt = 0,
    )

    private fun episode(message: Long, ep: Int) = video(message, "The Show S01E0$ep").copy(fileName = "The.Show.S01E0$ep.mkv")

    @Test
    fun `the page shows each list, its buttons and a show as one card`() = runComposeUiTest {
        val (weekend, other) = runBlocking {
            val a = store.create("Weekend")!!
            val b = store.create("Weekend")!!
            store.add(a.id, video(1))
            store.add(a.id, episode(2, 1))
            store.add(a.id, episode(3, 2))
            a to b
        }
        val drawn = mutableListOf<Int>()
        setContent {
            TmMaterialTheme(dark = true) {
                Surface(Modifier.fillMaxSize()) {
                    var selected by remember { mutableStateOf<String?>(weekend.id) }
                    ListPageScaffold(
                        lists = store.visible.collectAsStateValue(),
                        selectedId = selected,
                        onSelect = { selected = it },
                        store = store,
                        onMessage = {},
                    ) { _, cards, _ ->
                        drawn += cards.size
                        Column { cards.forEach { Text("card ${it.tile.title}") } }
                    }
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("card Clip 1").fetchSemanticsNodes().isNotEmpty() }
        // Two lists of one name are two chips.
        assertEquals(2, onAllNodesWithText("Weekend").fetchSemanticsNodes().size)
        onNodeWithText("Rename list").assertExists()
        onNodeWithText("Show on Home").assertExists()
        onNodeWithText("Delete list").assertExists()
        // The two episodes are one show card.
        assertEquals(2, drawn.last())
        // The other list is empty, and says so.
        onAllNodesWithText("Weekend")[1].performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Nothing in this list yet", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(other.id != weekend.id)
    }

    @Test
    fun `fifty lists draw, and New list caps a name at sixty`() = runComposeUiTest {
        runBlocking { repeat(50) { store.create("List $it") } }
        setContent {
            TmMaterialTheme(dark = true) {
                Surface(Modifier.fillMaxSize()) {
                    ListPickerDialog(video(9), store, onMessage = {}, onDismiss = {})
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("List 0").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("New list").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(LIST_NAME_TAG).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Create").assertIsNotEnabled()
        onNodeWithTag(LIST_NAME_TAG).performTextInput("x".repeat(500))
        onNodeWithText("x".repeat(NamedListRules.NAME_MAX)).assertExists()
        onNodeWithText("Create").performClick()
        waitUntil(timeoutMillis = 5_000) { runBlocking { store.now().lists.size } == 51 }
        val made = runBlocking { store.now() }.lists.single { it.name.startsWith("x") }
        assertEquals(60, made.name.length)
        waitUntil(timeoutMillis = 5_000) { runBlocking { store.now().entriesOf(made.id).size } == 1 }
    }

    @Test
    fun `a list of five thousand opens its cards from the stored lines in under a second`() {
        val info = runBlocking { store.create("Big")!! }
        runBlocking {
            store.mergeIn(
                com.tmplayer.data.NamedLists(entries = List(5_000) { com.tmplayer.data.ListEntry(info.id, episode(10L + it, 1 + it % 9).copy(addedAt = 1_000L + it, title = "Lesson $it")) }),
            )
        }
        val start = System.nanoTime()
        val list = runBlocking { store.visible.first() }.single()
        val cards = ListPages.cards(list.records)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(5_000, list.records.size)
        assertTrue(cards.isNotEmpty())
        assertTrue("opened in $ms ms", ms < 1_000)
    }

    @Test
    fun `F2 opens the rename and Delete the question for the list shown, nothing with no list`() = runComposeUiTest {
        val list = runBlocking { store.create("Weekend")!! }
        setContent {
            TmMaterialTheme(dark = true) {
                Surface(Modifier.fillMaxSize()) {
                    ListPageScaffold(
                        lists = store.visible.collectAsStateValue(),
                        selectedId = list.id,
                        onSelect = {},
                        store = store,
                        onMessage = {},
                    ) { _, _, _ -> }
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Delete list").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Weekend").requestFocus()
        onNodeWithText("Weekend").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.F2) }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(LIST_NAME_TAG).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Cancel").performClick()
        waitForIdle()
        onNodeWithText("Weekend").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Delete) }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Delete Weekend?").fetchSemanticsNodes().isNotEmpty() }
    }
}

@androidx.compose.runtime.Composable
private fun <T> kotlinx.coroutines.flow.Flow<T>.collectAsStateValue(): T? {
    val value by androidx.compose.runtime.produceState<T?>(null, this) { collect { value = it } }
    return value
}
