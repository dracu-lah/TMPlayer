package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.tmplayer.data.ChatCountCalls
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.CountAnswer
import com.tmplayer.ui.browse.AddChatsScreen
import com.tmplayer.ui.browse.AddChatsViewModel
import com.tmplayer.ui.nav.BackStack
import com.tmplayer.ui.nav.LocalBackStack
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.TmSchemes
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Add chats picker (HX27) over a long account, in both themes: each frame has something on it
 * besides the ground, and no row shows a video count. With TMPLAYER_SHOTS=<dir> the
 * frames are written there.
 */
@OptIn(ExperimentalTestApi::class)
class AddChatsRenderTest {

    /**
     * The mandatory chat choice (ChatsGate): its own heading, no Back button, Done unavailable
     * until a chat is ticked, and Back (Esc) calls the leave action instead of closing or asking
     * to discard.
     */
    /** Nothing recommended and nothing ticked in the required choice: a way out, not a dead end (ON-4). */
    @Test
    fun `required mode with nothing recommended offers Continue without choosing`() = runComposeUiTest {
        val model = AddChatsViewModel(write = {})
        val back = BackStack()
        var went = 0
        setContent {
            CompositionLocalProvider(LocalBackStack provides back) {
                TmMaterialTheme(dark = true) {
                    Box(Modifier.size(1280.dp, 900.dp)) {
                        AddChatsScreen(
                            chats = CHATS,
                            listComplete = true,
                            starred = emptySet(),
                            model = model,
                            onClose = {},
                            required = true,
                            onContinue = { went++ },
                        )
                    }
                }
            }
        }
        waitForIdle()
        // Saved Messages is ticked for a fresh start (HX86): Done works and nothing else is offered
        // until the viewer unticks it.
        onNode(hasText("Choose at least one chat")).assertDoesNotExist()
        onNode(hasText("Continue without choosing") and hasClickAction()).assertDoesNotExist()
        onNode(hasText("Saved Messages") and hasClickAction()).performClick()
        waitForIdle()
        onNode(hasText("Choose at least one chat")).assertExists()
        onNode(hasText("Continue without choosing") and hasClickAction()).performClick()
        assertEquals(1, went)
    }

    @Test
    fun `required mode has no Back, needs a tick and does not close on Back`() = runComposeUiTest {
        val model = AddChatsViewModel(write = {})
        val back = BackStack()
        var closed = 0
        var left = 0
        setContent {
            CompositionLocalProvider(LocalBackStack provides back) {
                TmMaterialTheme(dark = true) {
                    Box(Modifier.size(1280.dp, 900.dp)) {
                        AddChatsScreen(
                            chats = CHATS,
                            listComplete = true,
                            starred = emptySet(),
                            model = model,
                            onClose = { closed++ },
                            required = true,
                            onLeave = { left++ },
                        )
                    }
                }
            }
        }
        waitForIdle()
        onNode(hasText("Which chats have your videos?")).assertExists()
        onNode(hasText("Back") and hasClickAction()).assertDoesNotExist()
        // Saved Messages is ticked for a fresh start (HX86): Done works. Untick it and Done says what
        // is missing and cannot be pressed.
        onNode(hasText("Done") and hasClickAction()).assertExists()
        onNode(hasText("Saved Messages") and hasClickAction()).performClick()
        waitForIdle()
        onNode(hasText("Choose at least one chat")).assertExists()
        onNode(hasText("Choose at least one chat") and hasClickAction()).assertDoesNotExist()
        onNode(hasText("Done") and hasClickAction()).assertDoesNotExist()

        // Back leaves (the app, on a phone) rather than closing, and never asks to discard.
        onNode(hasText("Harbour Notes") and hasClickAction()).performClick()
        waitForIdle()
        back.handleBack()
        waitForIdle()
        assertEquals(1, left)
        assertEquals(0, closed)
        onNode(hasText("Discard changes?")).assertDoesNotExist()

        // A tick makes Done pressable.
        onNode(hasText("Done") and hasClickAction()).assertExists()
        model.stop()
    }

    /**
     * Telegram's folders are filter chips right after All (HX29): a click narrows the list to the
     * folder's chats, "Add these" takes that folder only, a large one asks first, and a folder
     * that could not be read says so with Try again.
     */
    @Test
    fun `folder chips narrow the list and Add these takes that folder only`() = runComposeUiTest {
        val model = AddChatsViewModel(write = {})
        val inFolders = withFolders()
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(1280.dp, 900.dp)) {
                    AddChatsScreen(chats = inFolders, listComplete = true, starred = emptySet(), model = model, onClose = {}, folders = FOLDERS)
                }
            }
        }
        waitForIdle()
        onNode(hasText("Films") and hasClickAction()).assertExists()
        onNode(hasText("Family") and hasClickAction()).assertExists()
        // Telegram's own Channels folder is the Channels chip already: one of them, not two.
        onAllNodes(hasText("Channels") and hasClickAction()).assertCountEquals(1)
        onNode(hasText("Films") and hasClickAction()).performClick()
        waitForIdle()
        assertEquals(7, model.state.value.folder)
        onNode(hasText("Harbour Notes") and hasClickAction()).assertExists()
        onNode(hasText("Lantern Club") and hasClickAction()).assertDoesNotExist()
        onNode(hasText("Add these 3 chats") and hasClickAction()).performClick()
        waitForIdle()
        assertEquals(setOf(200L, 201L, 203L, 204L), model.state.value.ticked)
        onNode(hasText("Family") and hasClickAction()).performClick()
        waitForIdle()
        // Mira is in both folders and ticked already: she is not asked twice, and Films' other chats stay.
        onNode(hasText("Add these 81 chats") and hasClickAction()).assertExists()
        onNode(hasText("All") and hasClickAction()).performClick()
        waitForIdle()
        assertEquals(null, model.state.value.folder)
        assertEquals(setOf(200L, 201L, 203L, 204L), model.state.value.ticked)
        model.stop()
    }

    @Test
    fun `a large folder asks before it is ticked`() = runComposeUiTest {
        val model = AddChatsViewModel(write = {})
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(1280.dp, 900.dp)) {
                    AddChatsScreen(chats = withFolders(), listComplete = true, starred = emptySet(), model = model, onClose = {}, folders = FOLDERS)
                }
            }
        }
        waitForIdle()
        onNode(hasText("Family") and hasClickAction()).performClick()
        waitForIdle()
        onNode(hasText("Add these 81 chats") and hasClickAction()).performClick()
        waitForIdle()
        onNode(hasText("Select 81 chats?")).assertExists()
        assertEquals("only Saved Messages is ticked before the answer", setOf(200L), model.state.value.ticked)
        onNode(hasText("Select") and hasClickAction()).performClick()
        waitForIdle()
        assertEquals(82, model.state.value.ticked.size)
        model.stop()
    }

    @Test
    fun `a folder that could not be read is not empty, and Try again reads it again`() = runComposeUiTest {
        val model = AddChatsViewModel(write = {})
        var retried = 0
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(1280.dp, 900.dp)) {
                    AddChatsScreen(
                        chats = CHATS, listComplete = true, starred = emptySet(), model = model, onClose = {},
                        folders = FOLDERS,
                        folderStatus = com.tmplayer.ui.browse.FolderStatus(failed = setOf(7)),
                        onRetryFolders = { retried++ },
                    )
                }
            }
        }
        waitForIdle()
        onNode(hasText("Films") and hasClickAction()).performClick()
        waitForIdle()
        onNode(hasText("This folder could not be read from Telegram. It is not empty.")).assertExists()
        onNode(hasText("Try again") and hasClickAction()).performClick()
        assertEquals(1, retried)
        model.stop()
    }

    @Test
    fun `the folder chips are drawn in both themes`() {
        for (dark in listOf(true, false)) {
            val model = AddChatsViewModel()
            model.folder(7)
            shot("add-chats-folder-${theme(dark)}", 1366, 900, dark, model) {
                AddChatsScreen(chats = withFolders(), listComplete = true, starred = emptySet(), model = model, onClose = {}, folders = FOLDERS)
            }
            model.stop()
        }
    }

    /** A save that fails says so with Try again rather than sitting on the press for ever. */
    @Test
    fun `a failed save shows the error and Try again`() = runComposeUiTest {
        var tries = 0
        val model = AddChatsViewModel(write = {
            tries++
            throw java.io.IOException("No space left on device")
        })
        var closed = false
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(1280.dp, 900.dp)) {
                    AddChatsScreen(
                        chats = CHATS,
                        listComplete = true,
                        starred = emptySet(),
                        model = model,
                        onClose = { closed = true },
                        required = true,
                    )
                }
            }
        }
        waitForIdle()
        onNode(hasText("Harbour Notes") and hasClickAction()).performClick()
        waitForIdle()
        onNode(hasText("Done") and hasClickAction()).performClick()
        waitUntil(timeoutMillis = 3_000) { model.state.value.error != null }
        waitForIdle()
        assertFalse("not stuck saving", model.state.value.saving)
        assertFalse("not saved", model.state.value.saved)
        assertFalse(closed)
        onNode(hasText("Try again") and hasClickAction()).assertExists()
        // Nothing tries again by itself; Try again does, once per press.
        assertEquals(1, tries)
        onNode(hasText("Try again") and hasClickAction()).performClick()
        waitUntil(timeoutMillis = 3_000) { tries == 2 }
        model.stop()
    }

    @Test
    fun `the picker draws the bands and counts the head of the list`() {
        for (dark in listOf(true, false)) {
            val model = AddChatsViewModel(counts = Counts)
            shot("add-chats-${theme(dark)}", 1366, 900, dark, model) {
                AddChatsScreen(chats = CHATS, listComplete = true, starred = setOf(102L), model = model, onClose = {})
            }
            val counted = model.state.value.counted
            assertTrue("the first row was counted", counted.containsKey(CHATS.first().id))
            model.stop()
        }
    }

    /** T5: no row says how many videos a chat holds, and a Recommended row says why it is there in words. */
    @Test
    fun `rows show no video count and a recommended row says why`() = runComposeUiTest {
        val model = AddChatsViewModel(counts = Counts, countsRest = false)
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(1280.dp, 900.dp)) {
                    AddChatsScreen(chats = CHATS, listComplete = true, starred = emptySet(), model = model, onClose = {})
                }
            }
        }
        waitUntil(timeoutMillis = 10_000) { model.state.value.recommended.isNotEmpty() }
        waitForIdle()
        assertTrue(model.state.value.counted.isNotEmpty())
        onAllNodes(hasText("Has many videos")).assertCountEquals(model.state.value.recommended.size)
        onAllNodes(hasText("About ", substring = true)).assertCountEquals(0)
        onAllNodes(hasText("1,000+", substring = true)).assertCountEquals(0)
        onAllNodes(hasText("Could not count", substring = true)).assertCountEquals(0)
        model.stop()
    }

    @Test
    fun `a short window and a partial list still draw`() {
        val model = AddChatsViewModel(counts = Counts)
        shot("add-chats-short", 900, 420, true, model) {
            AddChatsScreen(chats = CHATS.take(8), listComplete = false, starred = emptySet(), model = model, onClose = {}, partial = true)
        }
        model.stop()
    }

    @Test
    fun `while the recommendation is worked out its place is held and the other bands wait`() {
        // Counts that never answer: the band is not taken within the frames drawn here.
        val slow = object : ChatCountCalls {
            override suspend fun videoCount(chatId: Long, localOnly: Boolean): CountAnswer = kotlinx.coroutines.awaitCancellation()
        }
        val model = AddChatsViewModel(counts = slow)
        var held = false
        shot("add-chats-holding", 1366, 900, true, model) {
            AddChatsScreen(chats = CHATS, listComplete = true, starred = setOf(102L), model = model, onClose = {})
            // Read while the screen is up: closing it stops the counting, which ends the wait.
            val now = model.state.collectAsState().value
            if (now.recommending) held = true
        }
        assertTrue("the band's place was held while it was worked out", held)
        assertFalse("closing the screen ends the wait", model.state.value.recommending)
    }

    /** Renders until the counts have landed (they answer off the main thread), then keeps the last frame. */
    private fun shot(name: String, w: Int, h: Int, dark: Boolean, model: AddChatsViewModel, content: @Composable () -> Unit) {
        val png = ImageComposeScene(w, h, Density(1f)) {
            TmMaterialTheme(dark = dark) { content() }
        }.use { scene ->
            var now = 0L
            scene.render(now)
            repeat(40) {
                if (model.state.value.counted.isNotEmpty() && it > 4) return@repeat
                Thread.sleep(50)
                now += 100_000_000L
                scene.render(now)
            }
            now += 600_000_000L
            scene.render(now).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }
        val bitmap = Bitmap.makeFromImage(Image.makeFromEncoded(png))
        val ground = TmSchemes.of(dark).background.toArgb()
        var drawn = 0
        for (y in 0 until bitmap.height step 4) for (x in 0 until bitmap.width step 4) if (bitmap.getColor(x, y) != ground) drawn++
        assertTrue("$name has something on it", drawn > 50)
        val out = System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        File(out).apply { mkdirs() }.resolve("$name.png").writeBytes(png)
    }

    private fun theme(dark: Boolean) = if (dark) "dark" else "light"

    /** Films holds three chats, Family eighty one (Mira is in both), and the rest are in none. */
    private fun withFolders(): List<ChatSummary> = CHATS.map { chat ->
        when (chat.id) {
            201L, 203L -> chat.copy(folderIds = listOf(7))
            204L -> chat.copy(folderIds = listOf(7, 8))
            else -> if (chat.id in 2_001L..2_080L) chat.copy(folderIds = listOf(8)) else chat
        }
    }

    /**
     * The All chats switch on the desktop (HX86): a clickable row above the search that reads On or
     * Off, the folder chips are greyed with the line "All chats is on" while it is on, a tick and
     * Select all change nothing, Done works with nothing ticked, and off brings the ticks back.
     */
    @Test
    fun `the All chats switch greys the folders and the ticks while on and gives them back off`() = runComposeUiTest {
        val written = mutableListOf<com.tmplayer.data.AddedChoice>()
        val model = AddChatsViewModel(write = {}, writeChoice = { written += it })
        setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.size(1280.dp, 900.dp)) {
                    AddChatsScreen(chats = withFolders(), listComplete = true, starred = emptySet(), model = model, onClose = {}, folders = FOLDERS)
                }
            }
        }
        waitForIdle()
        val switch = androidx.compose.ui.test.SemanticsMatcher("the All chats switch") { node ->
            node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text).orEmpty().any { it.text == "All chats" } &&
                node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.StateDescription) != null
        }
        onNode(switch).assertExists()
        assertEquals("Off", onNode(switch).fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.StateDescription))
        onNode(hasText("Harbour Notes") and hasClickAction()).performClick()
        waitForIdle()
        assertEquals(setOf(200L, 201L), model.state.value.ticked)
        onNode(switch).performClick()
        waitForIdle()
        assertEquals("On", onNode(switch).fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.StateDescription))
        // In the switch and in the foot: the line the greyed controls point at.
        assertTrue(onAllNodes(hasText("All chats is on")).fetchSemanticsNodes().isNotEmpty())
        // The folder chips take no press while it is on, and a tick or Select all changes nothing.
        onNode(hasText("Films")).assertExists()
        onNode(hasText("Films") and hasClickAction()).assertIsNotEnabled()
        onNode(hasText("Mira") and hasClickAction()).assertDoesNotExist()
        model.toggle(204L)
        model.selectAll(setOf(205L, 206L))
        assertEquals(setOf(200L, 201L), model.state.value.ticked)
        onNode(switch).performClick()
        waitForIdle()
        assertEquals("the kept ticks are back", setOf(200L, 201L), model.state.value.ticked)
        onNode(hasText("Films") and hasClickAction()).assertIsEnabled()
        onNode(switch).performClick()
        waitForIdle()
        onNode(hasText("Done") and hasClickAction()).performClick()
        waitUntil(timeoutMillis = 3_000) { written.isNotEmpty() }
        assertEquals(com.tmplayer.data.AddedMode.All, written.single().mode)
        assertEquals(listOf(200L, 201L), written.single().chats.map { it.id })
        model.stop()
    }

    private companion object {
        val FOLDERS = listOf(
            com.tmplayer.data.ChatFolderSummary(7, "Films", icon = "Custom"),
            com.tmplayer.data.ChatFolderSummary(8, "Family", icon = "Custom"),
            com.tmplayer.data.ChatFolderSummary(9, "Channels", icon = "Channels"),
        )

        fun chat(id: Long, title: String, kind: ChatKind = ChatKind.Channel, archived: Boolean = false) =
            ChatSummary(id, title, miniThumbnail = null, photoFileId = 0, kind = kind, isArchived = archived)

        /** Saved Messages, a few named chats and then two hundred more, so most of it is below the window. */
        val CHATS: List<ChatSummary> = listOf(chat(200, "Saved Messages", ChatKind.Saved)) +
            listOf(
                chat(201, "Harbour Notes"),
                chat(102, "Lantern Club", ChatKind.Group),
                chat(203, "Night Lantern Archive"),
                chat(204, "Mira", ChatKind.Direct),
                chat(205, "Quiet Reels"),
            ) + (1..200).map { chat(2_000L + it, "Chat number $it", archived = it % 5 == 0) }

        val Counts = object : ChatCountCalls {
            override suspend fun videoCount(chatId: Long, localOnly: Boolean): CountAnswer = when (chatId) {
                205L -> CountAnswer.Failed("Bad Request")
                203L -> CountAnswer.Counted(4_200)
                201L -> CountAnswer.Counted(340)
                102L -> CountAnswer.Counted(57)
                else -> CountAnswer.Counted((chatId % 9).toInt())
            }
        }
    }
}
