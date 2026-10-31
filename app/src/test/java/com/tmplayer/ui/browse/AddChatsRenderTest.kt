package com.tmplayer.ui.browse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.tmplayer.data.ChatCount
import com.tmplayer.data.ChatCountCalls
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.CountAnswer
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.theme.TmMaterialTheme
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The Add chats picker (HX27) on a phone held upright and on a television, over a long account:
 * once with every count answered (the Recommended band, a chat that could not be counted, one past
 * the exact figures) and once with the counter silent (every row on its placeholder). Frames go
 * to `build/device-test/review/` and to TMPLAYER_SHOTS=<dir> when that is set.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddChatsRenderTest {

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun phone() {
        val model = AddChatsViewModel(counts = Counts)
        save("add-chats-phone.png", render(tv = false, model, CHATS))
        // Only the rows on screen are counted (and the head of the list, which the recommendation
        // is made from): a chat deep in the list was never asked about.
        val counted = model.state.value.counted
        assertTrue("the first row was counted", counted.containsKey(CHATS.first().id))
        assertFalse("a row far below the screen was not counted", counted.containsKey(CHATS.last().id))
        assertEquals(ChatCount.Unknown, counted[UNCOUNTABLE])
        model.stop()
    }

    @Test
    @Config(sdk = [28], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun tv() {
        val model = AddChatsViewModel(counts = Counts)
        save("add-chats-tv.png", render(tv = true, model, CHATS))
        model.stop()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `rows hold their place while the counts are still coming`() {
        val model = AddChatsViewModel(counts = Silent)
        save("add-chats-counting-phone.png", render(tv = false, model, SILENT_CHATS))
        // Nothing answered, so nothing is recommended or ticked, and every row is a placeholder.
        assertTrue(model.state.value.recommended.isEmpty())
        assertTrue(model.state.value.ticked.isEmpty())
        model.stop()
    }

    private fun render(tv: Boolean, model: AddChatsViewModel, chats: List<ChatSummary>): Bitmap {
        FormFactor.override(tv)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            TmMaterialTheme(dark = true) {
                AddChatsScreen(chats = chats, listComplete = true, starred = setOf(STARRED), model = model, onClose = {})
            }
        }
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        return bitmap
    }

    private fun save(name: String, bitmap: Bitmap) {
        val dirs = listOfNotNull(
            File("../build/device-test/review").absoluteFile,
            System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() }?.let(::File),
        )
        for (dir in dirs) {
            val file = dir.apply { mkdirs() }.resolve(name)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(file.length() > 500)
        }
    }

    private companion object {
        /** A chat Telegram would not count, and one holding more than the exact figures go to. */
        const val UNCOUNTABLE = 105L
        const val HUGE = 103L
        const val STARRED = 102L

        fun chat(id: Long, title: String, kind: ChatKind = ChatKind.Channel, archived: Boolean = false) =
            ChatSummary(id, title, miniThumbnail = null, photoFileId = 0, kind = kind, isArchived = archived)

        /** Saved Messages, a few named chats and then two hundred more, so most of it is off screen. */
        val CHATS: List<ChatSummary> = listOf(chat(100, "Saved Messages", ChatKind.Saved)) +
            listOf(
                chat(101, "Harbour Notes", ChatKind.Channel),
                chat(STARRED, "Lantern Club", ChatKind.Group),
                chat(HUGE, "Night Lantern Archive"),
                chat(104, "Mira", ChatKind.Direct),
                chat(UNCOUNTABLE, "Quiet Reels"),
            ) + (1..200).map { chat(1_000L + it, "Chat number $it", archived = it % 5 == 0) }

        /** Its own ids: the counter keeps what it counted, and these must start from nothing. */
        val SILENT_CHATS: List<ChatSummary> = CHATS.map { it.copy(id = it.id + 50_000L) }

        val Counts = object : ChatCountCalls {
            override suspend fun videoCount(chatId: Long, localOnly: Boolean): CountAnswer = when (chatId) {
                UNCOUNTABLE -> CountAnswer.Failed("Bad Request")
                HUGE -> CountAnswer.Counted(4_200)
                101L -> CountAnswer.Counted(340)
                STARRED -> CountAnswer.Counted(57)
                else -> CountAnswer.Counted((chatId % 9).toInt())
            }
        }

        val Silent = object : ChatCountCalls {
            override suspend fun videoCount(chatId: Long, localOnly: Boolean): CountAnswer = awaitCancellation()
        }
    }
}
