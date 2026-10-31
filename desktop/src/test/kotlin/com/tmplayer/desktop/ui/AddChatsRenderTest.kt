package com.tmplayer.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.tmplayer.data.ChatCountCalls
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.CountAnswer
import com.tmplayer.ui.browse.AddChatsScreen
import com.tmplayer.ui.browse.AddChatsViewModel
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.TmSchemes
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Add chats picker (HX27) over a long account, in both themes: each frame has something on it
 * besides the ground, and only the rows on screen were counted. With TMPLAYER_SHOTS=<dir> the
 * frames are written there.
 */
class AddChatsRenderTest {

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
