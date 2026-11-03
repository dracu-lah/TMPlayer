package com.tmplayer.ui.browse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import com.tmplayer.data.ChatCountCalls
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatPicking
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.ChatVideoCount
import com.tmplayer.data.CountAnswer
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.After
import org.junit.Assert.assertEquals
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
 * The three pane picker with a remote alone on a large account (HX76): 3,000 chats, the mandatory
 * choice as the setup step shows it. Every line of the focus map: start on Done when the
 * Recommended band ticked something (no longer: nothing is ticked for the viewer), else the first row; the rail, the list and the panel each
 * answer Left, Right, Up, Down and Back; the chats with no videos fold into one row that opens in
 * place; a held Down speeds up; the search keyboard's Search lands on a row.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class AddChatsTvKeysTest {

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    @Test
    fun `the remote starts on the first row when the Recommended band is shown, nothing is ticked for the viewer`() {
        val model = AddChatsViewModel(counts = Counts, write = {})
        val activity = open(model, chats = CHATS)
        assertTrue(model.state.value.ticked.isEmpty())
        assertEquals(listOf("Saved Messages"), focusedTitle(activity))
        save("add-chats-tv-three-panes.png", draw(activity))
        model.stop()
        idle()
        ChatVideoCount.forget()
    }

    @Test
    fun `the remote starts on the first row when nothing is ticked`() {
        val model = AddChatsViewModel(counts = null, write = {})
        val activity = open(model, chats = CHATS)
        assertEquals(listOf("Saved Messages"), focusedTitle(activity))
        model.stop()
    }

    @Test
    fun `no chats at all starts on the rail and the empty scope stays on it`() {
        val model = AddChatsViewModel(counts = Counts, write = {})
        val activity = open(model, chats = emptyList())
        assertTrue(focusedWords(activity).contains("All"))
        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue("an empty list keeps the remote on the rail", focusedWords(activity).contains("All"))
        model.stop()
    }

    @Test
    fun `the rail answers Up, Down, Right and OK, and Left from a row comes back to it`() {
        val model = AddChatsViewModel(counts = null, write = {})
        val activity = open(model, chats = CHATS)
        press(activity, KeyEvent.KEYCODE_DPAD_LEFT)
        assertTrue("Left goes to the rail on the current entry", focusedWords(activity).contains("All"))
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(focusedWords(activity).contains("Channels"))
        press(activity, KeyEvent.KEYCODE_DPAD_UP)
        assertTrue(focusedWords(activity).contains("All"))
        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("Right goes into the list", listOf("Saved Messages"), focusedTitle(activity))
        press(activity, KeyEvent.KEYCODE_DPAD_LEFT)
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("OK goes into the list too", listOf("Saved Messages"), focusedTitle(activity))
        model.stop()
    }

    @Test
    fun `a row goes right to Done and the panel goes left to the row it came from`() {
        val model = AddChatsViewModel(counts = null, write = {})
        // Not required, so Done is on the panel with nothing ticked.
        val activity = open(model, chats = CHATS, required = false)
        // The focus fill, ring and ink are painted in the draw phase (HX76): the pictures show
        // the row the remote left drawn plain again and the one it reached lit.
        save("add-chats-tv-row-focused.png", draw(activity))
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        save("add-chats-tv-row-focus-moved.png", draw(activity))
        val row = focusedTitle(activity)
        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(listOf("Save with no chats"), focusedWords(activity))
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("No Select all on TV: Down stays on Done", listOf("Save with no chats"), focusedWords(activity))
        press(activity, KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("Left goes back to the row it came from", row, focusedTitle(activity))
        model.stop()
    }

    @Test
    fun `Back goes from a row to the rail and a second Back leaves the required choice`() {
        val model = AddChatsViewModel(counts = null, write = {})
        var left = 0
        val activity = open(model, chats = CHATS, onLeave = { left++ })
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        activity.onBackPressedDispatcher.onBackPressed()
        idle()
        assertTrue("Back goes to the rail", focusedWords(activity).contains("All"))
        assertEquals(0, left)
        activity.onBackPressedDispatcher.onBackPressed()
        idle()
        assertEquals("a second Back leaves", 1, left)
        model.stop()
    }

    @Test
    fun `the chats with no videos fold into one row that opens in place`() {
        val model = AddChatsViewModel(counts = SmallCounts, write = {})
        val activity = open(model, chats = SMALL, required = false)
        // On the first row (the remote starts there when nothing was ticked), then down to the fold.
        assertEquals(listOf("Saved Messages"), focusedTitle(activity))
        var presses = 0
        while (focusedWords(activity).none { it.endsWith("chats with no videos") } && presses++ < 20) {
            press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        }
        val fold = focusedWords(activity)
        assertTrue("a fold row was reached: $fold", fold.any { it == "25 chats with no videos" })
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("the remote stands on the first chat of the fold", listOf("Quiet 1"), focusedTitle(activity))
        assertTrue("the fold is gone once open", nodes(activity).none { words(it).any { w -> w.endsWith("chats with no videos") } })
        model.stop()
    }

    @Test
    fun `a held Down speeds up`() {
        val model = AddChatsViewModel(counts = Counts, write = {}, countsRest = false)
        val activity = open(model, chats = CHATS)
        // Twenty repeats of a held Down: the first few walk, the rest move half a screen each.
        hold(activity, KeyEvent.KEYCODE_DPAD_DOWN, repeats = 20)
        val deep = focusedTitle(activity).single()
        val place = deep.removePrefix("Chat number ").toIntOrNull() ?: 0
        // Walking row by row would reach about 20; half-screen jumps reach about 80.
        assertTrue("a held Down went far, to $deep", place > 60)
        model.stop()
    }

    @Test
    fun `a held Down does not count the rows it runs past`() {
        // The answers are kept app wide, and the other tests here have counted these chats.
        ChatVideoCount.forget()
        val asked = java.util.Collections.synchronizedSet(HashSet<Long>())
        val recording = object : ChatCountCalls {
            override suspend fun videoCount(chatId: Long, localOnly: Boolean): CountAnswer {
                asked += chatId
                return Counts.videoCount(chatId, localOnly)
            }
        }
        val model = AddChatsViewModel(counts = recording, write = {}, countsRest = false)
        val activity = open(model, chats = CHATS)
        hold(activity, KeyEvent.KEYCODE_DPAD_DOWN, repeats = 30)
        // The settle after the last jump is a coroutine delay, which runs on the real clock.
        Thread.sleep(700)
        idle()
        val place = focusedTitle(activity).single().removePrefix("Chat number ").toInt()
        val head = ChatPicking.headStart(CHATS).toSet()
        assertTrue("the held Down went past the head start, to $place", 1_000L + place > head.max() + 40)
        // Counted: the head start, and the rows on screen where the remote stopped. Not the
        // screenfuls in between, each a Telegram call whose answer rebuilt the list mid-jump.
        val passed = asked.filter { it !in head && it < 1_000L + place - 20 }
        assertTrue("rows run past were counted: ${passed.size} of them", passed.isEmpty())
        // The screenful under the stop is composed ahead for the next jump, but not on screen.
        val ahead = asked.filter { it > 1_000L + place + 12 }
        assertTrue("rows composed ahead were counted: $ahead", ahead.isEmpty())
        assertTrue("the rows where it stopped are counted: at $place", (1_000L + place) in asked)
        model.stop()
    }

    @Test
    fun `the Search entry opens the field and the keyboard's Search puts the remote on a row`() {
        val model = AddChatsViewModel(counts = null, write = {})
        val activity = open(model, chats = CHATS, search = true)
        press(activity, KeyEvent.KEYCODE_DPAD_LEFT)
        repeat(5) { press(activity, KeyEvent.KEYCODE_DPAD_DOWN) }
        assertTrue(focusedWords(activity).contains("Search"))
        press(activity, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("OK on Search puts the remote in the field", listOf("search probe"), focusedWords(activity))
        click(activity, "search probe")
        idle()
        assertEquals(listOf("Saved Messages"), focusedTitle(activity))
        model.stop()
    }

    private fun open(
        model: AddChatsViewModel,
        chats: List<ChatSummary>,
        search: Boolean = false,
        required: Boolean = true,
        onLeave: () -> Unit = {},
    ): ComponentActivity {
        FormFactor.override(true)
        // A television is never in touch mode, and in touch mode Compose lets nothing pressable
        // take the focus; the test window starts in it, so it is put the way a television is.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.window.decorView.dispatchWindowFocusChanged(true)
        activity.setContent {
            TmMaterialTheme(dark = true) {
                AddChatsScreen(
                    chats = chats,
                    listComplete = true,
                    starred = emptySet(),
                    model = model,
                    onClose = {},
                    required = required,
                    onLeave = onLeave,
                    // A stand-in for the television's search box: a press is its keyboard's Search.
                    searchField = if (!search) null else { _, _, _, modifier, onSubmit ->
                        Text("search probe", modifier.clickable { onSubmit() })
                    },
                )
            }
        }
        idle()
        return activity
    }

    private fun idle() = repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }

    private fun press(activity: ComponentActivity, code: Int) {
        val now = SystemClock.uptimeMillis()
        activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        idle()
    }

    private fun hold(activity: ComponentActivity, code: Int, repeats: Int) {
        val down = SystemClock.uptimeMillis()
        for (repeat in 0..repeats) {
            activity.dispatchKeyEvent(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, code, repeat))
            repeat(2) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50)) }
        }
        activity.dispatchKeyEvent(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0))
        idle()
    }

    private fun nodes(activity: ComponentActivity): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(view: View) {
            if (view is ViewRootForTest) out += view.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(activity.window.decorView)
        return out
    }

    private fun words(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    /** Every word on the focused control. */
    private fun focusedWords(activity: ComponentActivity): List<String> =
        nodes(activity).firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }?.let(::words).orEmpty()

    /** The focused row's chat name: its first words past the avatar's letter. */
    private fun focusedTitle(activity: ComponentActivity): List<String> = focusedWords(activity).filter { it.length > 1 }.take(1)

    private fun click(activity: ComponentActivity, text: String) {
        val target = nodes(activity).first { words(it).contains(text) && it.config.getOrNull(SemanticsActions.OnClick) != null }
        target.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke()
    }

    private fun draw(activity: ComponentActivity): Bitmap {
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
        fun chat(id: Long, title: String, kind: ChatKind = ChatKind.Channel) =
            ChatSummary(id, title, miniThumbnail = null, photoFileId = 0, kind = kind)

        /** Saved Messages and 2,999 more: the size of the account the report came from. */
        val CHATS: List<ChatSummary> = listOf(chat(100, "Saved Messages", ChatKind.Saved)) +
            (1..2_999).map { chat(1_000L + it, "Chat number $it") }

        /** The first twelve hold 50 videos, every seventh after them 5, the rest none. */
        val Counts = object : ChatCountCalls {
            override suspend fun videoCount(chatId: Long, localOnly: Boolean): CountAnswer =
                CountAnswer.Counted(
                    when {
                        chatId in 1_001L..1_012L -> 50
                        chatId % 7 == 0L -> 5
                        else -> 0
                    },
                )
        }

        /** Saved Messages, five chats with videos and twenty five without. */
        val SMALL: List<ChatSummary> = listOf(chat(100, "Saved Messages", ChatKind.Saved)) +
            (1..5).map { chat(2_000L + it, "Loud $it") } + (1..25).map { chat(3_000L + it, "Quiet $it") }

        val SmallCounts = object : ChatCountCalls {
            override suspend fun videoCount(chatId: Long, localOnly: Boolean): CountAnswer =
                CountAnswer.Counted(if (chatId in 2_001L..2_005L) 3 else 0)
        }
    }
}
