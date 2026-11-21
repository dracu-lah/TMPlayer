package com.tmplayer.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.tmplayer.data.FormFactor
import com.tmplayer.data.TitleSender.State
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "Wrong title?" (N1) on a television, by remote alone: the remote starts on No thanks, Up walks
 * the two ticks, Send works only once the first is on, and after a send the
 * short code is shown large with Close in focus.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class TitleReportTvPanelTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private val calls = mutableListOf<String>()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private val review = State.Review("Night.Train.2024.1080p.mkv", "Night Train", "Home videos")

    private fun show(start: State) {
        FormFactor.override(true)
        rule.setContent {
            var state by remember { mutableStateOf(start) }
            TmMaterialTheme(dark = true) {
                val actions = TitleReportActions(
                    onAgree = { on -> calls += "agree $on"; (state as? State.Review)?.let { state = it.copy(agreed = on) } },
                    onIncludeChat = { on -> calls += "chat $on"; (state as? State.Review)?.let { state = it.copy(includeChat = on) } },
                    onSend = { calls += "send" },
                    onDecline = { calls += "decline" },
                    onClose = { calls += "close" },
                )
                TitleReportTvContent(state, actions)
            }
        }
        // A window the remote works in: out of touch mode and holding focus, as on a television.
        val viewRoot = rule.activity.window.decorView.parent
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "ensureTouchMode",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "windowFocusChanged",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
        )
        rule.waitForIdle()
    }

    private fun button(label: String) = rule.onNode(hasText(label) and hasClickAction())

    private fun press(key: Key) {
        rule.onNode(isFocused()).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test
    fun `the remote starts on No thanks and Send waits for the tick`() {
        show(review)
        rule.onNodeWithText("File name").assertExists()
        rule.onNodeWithText("Night.Train.2024.1080p.mkv").assertExists()
        rule.onNodeWithText("Title you chose").assertExists()
        button("No thanks").assertIsFocused()
        button("Send").assertIsNotEnabled()
        // OK on the focused No thanks is the safe answer.
        press(Key.DirectionCenter)
        assertEquals(listOf("decline"), calls)
    }

    @Test
    fun `Up walks the chat tick then the agree tick, OK ticks, and Send then works`() {
        show(review)
        press(Key.DirectionUp)
        rule.onNodeWithText("Also send the chat name: Home videos").assertIsFocused()
        press(Key.DirectionUp)
        rule.onNodeWithText("Yes, send these two lines").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("agree true"), calls)
        button("Send").assertIsEnabled()
        // Down twice: the chat tick, then the buttons; Right reaches Send.
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        // The buttons are one row: Right reaches Send from No thanks (the geometry decides which the Down lands on).
        runCatching { button("No thanks").assertIsFocused() }.onSuccess { press(Key.DirectionRight) }
        button("Send").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("agree true", "send"), calls)
    }

    @Test
    fun `the chat name tick is its own and starts off`() {
        show(review)
        press(Key.DirectionUp)
        press(Key.DirectionCenter)
        assertEquals(listOf("chat true"), calls)
        button("Send").assertIsNotEnabled()
    }

    @Test
    fun `no chat tick is drawn when there is no chat name`() {
        show(State.Review("Night.Train.2024.mkv", "Night Train", null))
        rule.onNodeWithText("Yes, send these two lines").assertExists()
        assertEquals(0, rule.onAllNodesWithTextCount("Also send the chat name"))
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size

    @Test
    fun `after a send the code is shown and Close holds the remote`() {
        show(State.Sent(assertNotNullReport(), "NAME-7K3Q", duplicate = false))
        rule.onNodeWithText("NAME-7K3Q").assertExists()
        button("Close").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("close"), calls)
    }

    @Test
    fun `a pair kept for later says so`() {
        show(State.Queued(assertNotNullReport()))
        rule.onNodeWithText("Kept for later").assertExists()
        button("Close").assertIsFocused()
    }

    @Test
    fun `a number in the name is shown already taken out`() {
        show(State.Review("Night Train +91 98765 43210 @someone.mkv", "Night Train", null))
        rule.onNodeWithText("Night Train @user.mkv").assertExists()
    }

    private fun assertNotNullReport() =
        com.tmplayer.data.TitleReport.of("Night.Train.2024.mkv", "Night Train")!!
}
