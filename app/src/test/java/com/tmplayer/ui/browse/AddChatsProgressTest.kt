package com.tmplayer.ui.browse

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.TmMaterialTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the Add chats picker says while it works (WP-95): the count of chats read so far while a
 * big account loads, Done answering a press while the choice is written (and keeping the
 * television's focus), and "3 chats added" handed to the host as the picker closes.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddChatsProgressTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private fun chat(id: Long) = ChatSummary(id, "Chat number $id", miniThumbnail = null, photoFileId = 0, kind = ChatKind.Channel)

    private val first = listOf(chat(1), chat(2))

    /** The spinner and the bar run for ever, so the test clock is stepped by hand rather than left to run. */
    private fun show(
        tv: Boolean,
        model: AddChatsViewModel,
        chats: androidx.compose.runtime.MutableState<List<ChatSummary>> = mutableStateOf(first),
        complete: androidx.compose.runtime.MutableState<Boolean> = mutableStateOf(true),
        closed: () -> Unit = {},
        added: (Int) -> Unit = {},
    ) {
        FormFactor.override(tv)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(!tv)
        rule.setContent {
            TmMaterialTheme(dark = true) {
                AddChatsScreen(
                    chats = chats.value,
                    listComplete = complete.value,
                    starred = emptySet(),
                    model = model,
                    onClose = closed,
                    onAdded = added,
                )
            }
        }
        rule.mainClock.autoAdvance = false
        step(100)
    }

    private fun step(ms: Long) {
        var left = ms
        while (left > 0) {
            val by = minOf(left, 100L)
            rule.mainClock.advanceTimeBy(by)
            shadowOf(Looper.getMainLooper()).idle()
            left -= by
        }
    }

    private fun press(node: SemanticsNodeInteraction, key: Key) {
        node.performKeyInput { pressKey(key) }
        step(200)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xxhdpi")
    fun `a list that is still loading says how many chats it has read, and the line goes when it is whole`() {
        val model = AddChatsViewModel(counts = null, write = {})
        val chats = mutableStateOf(first)
        val complete = mutableStateOf(false)
        show(tv = false, model, chats, complete)
        step(1_500)
        rule.onNodeWithContentDescription("Loading your chats: 2 so far").assertExists()
        chats.value = first + listOf(chat(3), chat(4), chat(5))
        // Drawn at once (at most once a second), spoken at most every ten seconds.
        step(10_500)
        rule.onNodeWithContentDescription("Loading your chats: 5 so far").assertExists()
        complete.value = true
        step(1_500)
        rule.onNode(
            SemanticsMatcher("a loading line") { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.startsWith("Loading your chats") }
            },
        ).assertDoesNotExist()
        model.stop()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xxhdpi")
    fun `Done says it is saving while the choice is written and a second press changes nothing`() {
        val gate = CompletableDeferred<Unit>()
        var writes = 0
        val model = AddChatsViewModel(counts = null, write = { writes++; gate.await() })
        val closed = mutableListOf<Int>()
        var added = -1
        show(tv = false, model, closed = { closed += 1 }, added = { added = it })
        model.toggle(1)
        step(300)
        rule.onNodeWithText("Done").performClick()
        step(300)
        val busy = rule.onNodeWithText("Saving your choice")
        busy.assertExists()
        busy.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Saving your choice"))
        busy.performClick()
        step(300)
        assertEquals("pressed twice, written once", 1, writes)
        assertTrue("still open while it writes", closed.isEmpty())
        gate.complete(Unit)
        step(500)
        assertEquals("the host is told what was added, then the screen closes", 1, added)
        assertEquals(1, closed.size)
        model.stop()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `on a television Done keeps the focus while it saves`() {
        val gate = CompletableDeferred<Unit>()
        val model = AddChatsViewModel(counts = null, write = { gate.await() })
        show(tv = true, model)
        // A remote, not a finger: out of touch mode, with the window focused.
        val viewRoot = rule.activity.window.decorView.parent
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "ensureTouchMode",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "windowFocusChanged",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
        )
        step(300)
        // Tick a chat, then go right to Done.
        model.toggle(1)
        step(300)
        rule.onNodeWithText("Done").requestFocus()
        step(200)
        rule.onNodeWithText("Done").assertIsFocused()
        press(rule.onNodeWithText("Done"), Key.DirectionCenter)
        rule.onNodeWithText("Saving your choice").assertIsFocused()
        gate.complete(Unit)
        step(500)
        model.stop()
    }
}
