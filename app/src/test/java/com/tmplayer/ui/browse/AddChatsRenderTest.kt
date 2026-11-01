package com.tmplayer.ui.browse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
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

    /**
     * The mandatory chat choice (ChatsGate) on a phone: its own heading, no Back button, Done not
     * pressable until a chat is ticked, and the system Back leaves rather than closing or asking
     * to discard.
     */
    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `required mode has no Back, needs a tick and leaves on Back`() {
        FormFactor.override(false)
        val model = AddChatsViewModel(write = {})
        var closed = 0
        var left = 0
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            TmMaterialTheme(dark = true) {
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
        idle()
        assertTrue(node(activity, "Choose chats to watch") != null)
        assertTrue("no Back button", node(activity, "Back", clickable = true) == null)
        assertTrue(node(activity, "Choose at least one chat") != null)
        assertTrue("Done waits for a tick", node(activity, "Choose at least one chat", clickable = true) == null)

        activity.onBackPressedDispatcher.onBackPressed()
        idle()
        assertEquals(1, left)
        assertEquals(0, closed)
        assertTrue("never asks to discard", node(activity, "Discard changes?") == null)

        click(activity, "Harbour Notes")
        idle()
        assertTrue("a tick makes Done pressable", node(activity, "Done", clickable = true) != null)
        // No frame is drawn here: drawing the window while the tick box still animates left the
        // native canvas in a state that stopped the next test's counts from landing.
        model.stop()
    }

    /** A save that fails says so with Try again, rather than sitting on the press for ever. */
    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `a failed save shows the error and Try again`() {
        FormFactor.override(false)
        var tries = 0
        val model = AddChatsViewModel(write = {
            tries++
            throw java.io.IOException("No space left on device")
        })
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            TmMaterialTheme(dark = true) {
                AddChatsScreen(chats = CHATS, listComplete = true, starred = emptySet(), model = model, onClose = {}, required = true)
            }
        }
        idle()
        click(activity, "Harbour Notes")
        idle()
        click(activity, "Done")
        idle()
        assertTrue("the error is kept", model.state.value.error != null)
        assertFalse("not stuck saving", model.state.value.saving)
        assertFalse(model.state.value.saved)
        assertTrue(node(activity, "Try again", clickable = true) != null)
        // Nothing tries again by itself; Try again does.
        assertEquals(1, tries)
        save("add-chats-save-failed-phone.png", draw(activity))
        click(activity, "Try again")
        idle()
        assertEquals(2, tries)
        model.stop()
    }

    private fun idle() = repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }

    /** Every merged semantics node of the window's Compose views. */
    private fun nodes(activity: ComponentActivity): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(view: View) {
            if (view is ViewRootForTest) out += view.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(activity.window.decorView)
        return out
    }

    /** The node whose words include [text] (and that can be pressed, when [clickable]), or null. */
    private fun node(activity: ComponentActivity, text: String, clickable: Boolean = false): SemanticsNode? =
        nodes(activity).firstOrNull { node ->
            val words = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            words.any { it == text } && (!clickable || node.config.getOrNull(SemanticsActions.OnClick) != null)
        }

    private fun click(activity: ComponentActivity, text: String) {
        val target = node(activity, text, clickable = true) ?: error(
            "nothing pressable reads $text among " +
                nodes(activity).map { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("|") { it.text } }.filter { it.isNotBlank() },
        )
        target.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke()
    }

    private fun draw(activity: ComponentActivity): Bitmap {
        val root = activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        return bitmap
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
