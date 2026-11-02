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
import com.tmplayer.data.ChatSummary
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
 * The chat picker with a remote alone on a large account (HX76): 3,000 chats, the mandatory
 * choice as the setup step shows it. The remote starts on the first row, not in the search box;
 * Done is one Right away from any row; Back goes up to the filters and then leaves; a held Down
 * speeds up; Left goes back to the start of the band; the search keyboard's Search lands on a row.
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
    fun `the remote reaches Done, the top and deep rows in a few presses`() {
        val model = AddChatsViewModel(counts = Counts, write = {})
        var left = 0
        val activity = open(model, chats = CHATS, onLeave = { left++ })

        assertEquals("the remote starts on the first row", listOf("Saved Messages"), focusedTitle(activity))
        save("add-chats-tv-remote.png", draw(activity))

        // Done (or, with nothing ticked yet, Select all beside it) is one press from any row.
        press(activity, KeyEvent.KEYCODE_DPAD_DOWN)
        press(activity, KeyEvent.KEYCODE_DPAD_RIGHT)
        val foot = focusedWords(activity)
        assertTrue("one Right lands in the foot, on $foot", foot.any { it in setOf("Done", "Select all", "Clear") })

        // Back from the foot goes to the filters, a second Back leaves the mandatory choice.
        activity.onBackPressedDispatcher.onBackPressed()
        idle()
        assertEquals("Back goes up to the filters", listOf("All"), focusedWords(activity))
        assertEquals(0, left)
        activity.onBackPressedDispatcher.onBackPressed()
        idle()
        assertEquals("a second Back leaves", 1, left)
        model.stop()
    }

    @Test
    fun `a held Down speeds up and Left goes back to the start of the band`() {
        val model = AddChatsViewModel(counts = Counts, write = {})
        val activity = open(model, chats = CHATS)
        assertEquals(listOf("Saved Messages"), focusedTitle(activity))

        // Twenty repeats of a held Down: the first few walk, the rest move a screenful each.
        hold(activity, KeyEvent.KEYCODE_DPAD_DOWN, repeats = 20)
        val deep = focusedTitle(activity).single()
        val place = deep.removePrefix("Chat number ").toIntOrNull() ?: 0
        assertTrue("a held Down went far, to $deep", place > 40)

        // Left goes back to the start of the band, then the band before it.
        press(activity, KeyEvent.KEYCODE_DPAD_LEFT)
        val bandStart = focusedTitle(activity).single()
        assertTrue("Left lands on a band's first row, not $deep", bandStart != deep)
        press(activity, KeyEvent.KEYCODE_DPAD_LEFT)
        press(activity, KeyEvent.KEYCODE_DPAD_LEFT)
        press(activity, KeyEvent.KEYCODE_DPAD_LEFT)
        press(activity, KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("Left at the top reaches the filters", listOf("All"), focusedWords(activity))
        model.stop()
    }

    @Test
    fun `the search keyboard's Search puts the remote on a row`() {
        val model = AddChatsViewModel(counts = Counts, write = {})
        val activity = open(model, chats = CHATS, search = true)
        click(activity, "search probe")
        idle()
        assertEquals(listOf("Saved Messages"), focusedTitle(activity))
        model.stop()
    }

    @Test
    fun `no chats leaves the remote on the filters`() {
        val model = AddChatsViewModel(counts = Counts, write = {})
        val activity = open(model, chats = emptyList())
        assertEquals(listOf("All"), focusedWords(activity))
        model.stop()
    }

    private fun open(
        model: AddChatsViewModel,
        chats: List<ChatSummary>,
        search: Boolean = false,
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
                    required = true,
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

        val Counts = object : ChatCountCalls {
            override suspend fun videoCount(chatId: Long, localOnly: Boolean): CountAnswer =
                CountAnswer.Counted(if (chatId in 1_001L..1_012L) 50 else 0)
        }
    }
}
