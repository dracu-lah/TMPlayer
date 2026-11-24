package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.pressKey
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The television's chat list on a large account (3,000 chats, WP-12): Page and Channel keys move the
 * remote a screenful at a time and only the rows on screen are composed; Back from deep in the list
 * goes to its top first. The 3 second first content check is in `ui` jvmTest, not here (no wall clock
 * assertions in Robolectric). `SyntheticAccount` lives in `ui` jvmTest, so the same shape is built
 * here from plain chat summaries.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class ChatListPageKeysTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        rule.mainClock.autoAdvance = true
        ChatListReturn.forget()
        FormFactor.override(false)
        Motion.reduced = false
    }

    private val chats = (1..3_000).map {
        ChatSummary(it.toLong(), "Chat %04d".format(it), miniThumbnail = null, photoFileId = 0, kind = ChatKind.Channel, unreadCount = 0)
    }

    private fun textsOf(node: androidx.compose.ui.semantics.SemanticsNode): String =
        (node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty() + " " +
            node.children.joinToString(" ") { textsOf(it) }).trim()

    private fun indexOnFocus(): Int {
        val text = textsOf(rule.onNode(isFocused()).fetchSemanticsNode())
        return Regex("Chat (\\d{4})").find(text)?.groupValues?.get(1)?.toInt() ?: error("no chat row has the focus: '$text'")
    }

    private val away = FocusRequester()

    private var leaves = 0

    private fun show() {
        FormFactor.override(true)
        Motion.reduced = true
        rule.setContent {
            TMPlayerTheme {
                // The browse shell's own Back, composed before the list: what "leave" is.
                androidx.activity.compose.BackHandler { leaves++ }
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                    Box(Modifier.size(8.dp).focusRequester(away).focusable())
                    Box(Modifier.fillMaxSize().padding(top = 8.dp)) {
                    ChatSection(
                        chats = chats,
                        favorites = emptySet(),
                        recentChats = emptyList(),
                        insets = TouchInsets,
                        autoFocus = true,
                        onOpenChat = {},
                        onHold = {},
                        launchChatId = 0,
                    )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    /** The key the way the system delivers it: through the window, to whatever holds the focus. */
    private fun keyDown(code: Int) {
        rule.runOnUiThread {
            rule.activity.window.decorView.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
        }
    }

    /** Frames driven by hand, one at a time: the clock does not run them inside this screen's measure pass. */
    private fun frames(n: Int = 40) = repeat(n) { rule.mainClock.advanceTimeByFrame() }

    @Test
    fun `a Channel key lands the remote a screenful further on a real chat row, and Up comes back`() {
        show()
        rule.runOnIdle { away.requestFocus() }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        val start = indexOnFocus()
        rule.mainClock.autoAdvance = false
        keyDown(android.view.KeyEvent.KEYCODE_CHANNEL_DOWN)
        frames(60)
        val after = indexOnFocus()
        assertTrue("moved from $start to $after", after - start in 4..80)
        val composed = rule.onAllNodesWithText("Chat ", substring = true).fetchSemanticsNodes().size
        assertTrue("$composed rows composed", composed < 120)
        keyDown(android.view.KeyEvent.KEYCODE_CHANNEL_UP)
        frames(60)
        assertEquals(start, indexOnFocus())
    }

    @Test
    fun `Back from deep in the list goes to the top first, with the tip, and the second Back leaves`() {
        // Come back to the list as from a chat far down: the grid starts deep, the remote on that row.
        ChatListReturn.key = 2_500L
        ChatListReturn.firstIndex = 2_400
        ChatListReturn.firstOffset = 0
        show()
        rule.mainClock.autoAdvance = false
        frames(20)
        assertTrue("deep in the list", indexOnFocus() > 2_000)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        frames()
        assertEquals("the first Back only goes to the top", 0, leaves)
        assertTrue("on the first rows", indexOnFocus() <= 12)
        rule.onNodeWithText("Press Back again to leave").assertExists()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        frames(2)
        assertEquals("the second Back leaves", 1, leaves)
    }
}
