package com.tmplayer.ui.browse

import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Add chats search box's clear control (Raju review): a cross in the field on the phone and
 * the desktop, none on the television (a second stop beside the field took the remote's Down from
 * the rows, which `AddChatsTvKeysTest` guards), where Back from the field empties the words.
 * Back clears the words before it closes on the phone too.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddChatsClearTest {

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
        idle()
    }

    private val chats = listOf(
        ChatSummary(1, "Harbour Notes", miniThumbnail = null, photoFileId = 0, kind = ChatKind.Channel),
        ChatSummary(2, "Night Train", miniThumbnail = null, photoFileId = 0, kind = ChatKind.Channel),
    )

    private fun open(
        tv: Boolean,
        model: AddChatsViewModel,
        closed: () -> Unit = {},
        typingField: Boolean = false,
    ): ComponentActivity {
        FormFactor.override(tv)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(!tv)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.window.decorView.dispatchWindowFocusChanged(true)
        activity.setContent {
            TmMaterialTheme(dark = true) {
                AddChatsScreen(
                    chats = chats,
                    listComplete = true,
                    starred = emptySet(),
                    model = model,
                    onClose = closed,
                    required = false,
                    searchField = if (!typingField) null else { _, onValueChange, _, modifier, _ ->
                        // A press on this stand-in for the television's box types "abc".
                        Text("search probe", modifier.clickable { onValueChange("abc") })
                    },
                )
            }
        }
        idle()
        return activity
    }

    private fun idle() = repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }

    private fun nodes(activity: ComponentActivity): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(view: View) {
            if (view is ViewRootForTest) out += view.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(activity.window.decorView)
        return out
    }

    private fun clearControl(activity: ComponentActivity) = nodes(activity).firstOrNull { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it == "Clear search" }
    }

    private fun words(node: SemanticsNode): List<String> = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun focusedWords(activity: ComponentActivity): List<String> =
        nodes(activity).firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }?.let(::words).orEmpty()

    private fun press(activity: ComponentActivity, code: Int) {
        val now = SystemClock.uptimeMillis()
        activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        idle()
    }

    private fun back(activity: ComponentActivity) {
        activity.onBackPressedDispatcher.onBackPressed()
        idle()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xxhdpi")
    fun `the phone's box has no cross over an empty box`() {
        val model = AddChatsViewModel(counts = null, write = {})
        assertNull("nothing to clear yet", clearControl(open(tv = false, model)))
        model.stop()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xxhdpi")
    fun `the phone shows the cross over typed words and it empties them`() {
        val model = AddChatsViewModel(counts = null, write = {})
        model.type("harb")
        val activity = open(tv = false, model)
        val cross = clearControl(activity)
        assertNotNull("a cross over the words", cross)
        cross!!.config.getOrNull(SemanticsActions.OnClick)!!.action!!.invoke()
        idle()
        assertEquals("the cross empties the words", "", model.state.value.query)
        assertNull("and goes", clearControl(activity))
        model.stop()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xxhdpi")
    fun `Back on the phone clears the words before it closes`() {
        val model = AddChatsViewModel(counts = null, write = {})
        model.type("harb")
        var closed = 0
        val activity = open(tv = false, model, closed = { closed++ })
        back(activity)
        assertEquals("Back empties the words first", "", model.state.value.query)
        assertEquals("and does not close yet", 0, closed)
        back(activity)
        assertEquals("a second Back closes", 1, closed)
        model.stop()
    }

    @Test
    @Config(sdk = [28], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `the television has no cross, and Back from the field empties the words`() {
        val model = AddChatsViewModel(counts = null, write = {})
        model.type("harb")
        val activity = open(tv = true, model)
        assertNull("no clear control for the remote to stop on", clearControl(activity))
        model.stop()

        val typing = AddChatsViewModel(counts = null, write = {})
        val field = open(tv = true, typing, typingField = true)
        // The same walk as the Search entry's test: Left to the rail, Down to Search, OK onto the keys.
        press(field, KeyEvent.KEYCODE_DPAD_LEFT)
        repeat(5) { press(field, KeyEvent.KEYCODE_DPAD_DOWN) }
        press(field, KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("the remote lands on the keyboard's first key", listOf("A"), focusedWords(field))
        nodes(field).first { words(it).contains("search probe") }.config.getOrNull(SemanticsActions.OnClick)!!.action!!.invoke()
        idle()
        assertEquals("abc", typing.state.value.query)
        assertNull("still no cross with words in", clearControl(field))
        back(field)
        assertEquals("Back from the keys empties the words", "", typing.state.value.query)
        typing.stop()
    }
}
