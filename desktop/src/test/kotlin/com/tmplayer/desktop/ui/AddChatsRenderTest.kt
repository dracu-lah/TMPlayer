package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
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
 * besides the ground, and only the rows on screen were counted. With TMPLAYER_SHOTS=<dir> the
 * frames are written there.
 */
@OptIn(ExperimentalTestApi::class)
class AddChatsRenderTest {

    /**
     * The mandatory chat choice (ChatsGate): its own heading, no Back button, Done unavailable
     * until a chat is ticked, and Back (Esc) calls the leave action instead of closing or asking
     * to discard.
     */
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
        onNode(hasText("Choose chats to watch")).assertExists()
        onNode(hasText("Back") and hasClickAction()).assertDoesNotExist()
        // Nothing ticked: Done says what is missing and cannot be pressed.
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
    fun `the picker draws the bands and counts only what is on screen`() {
        for (dark in listOf(true, false)) {
            val model = AddChatsViewModel(counts = Counts)
            shot("add-chats-${theme(dark)}", 1366, 900, dark, model) {
                AddChatsScreen(chats = CHATS, listComplete = true, starred = setOf(102L), model = model, onClose = {})
            }
            val counted = model.state.value.counted
            assertTrue("the first row was counted", counted.containsKey(CHATS.first().id))
            assertFalse("a row far below the window was not counted", counted.containsKey(CHATS.last().id))
            model.stop()
        }
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

    private companion object {
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
