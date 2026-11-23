package com.tmplayer.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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
 * Messages on a television: held for their length, an action the remote can walk to, and Back that
 * closes a message before it leaves the screen.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class TvToastTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val drain = DrainMainLooperRule()

    private var undone = 0
    private lateinit var say: (String, String, () -> Unit) -> Unit

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
    }

    @Test
    fun `a television holds a message for its length and an action longer, a phone as before`() {
        assertEquals(4_000L, toastDurationMs("Saved", hasAction = false, touch = false))
        assertEquals(9_000L, toastDurationMs("x".repeat(400), hasAction = false, touch = false))
        assertEquals(2_500L, toastDurationMs("Saved", hasAction = false, touch = true))
        assertEquals(12_000L, toastDurationMs("Hidden", hasAction = true, touch = false))
        assertEquals(8_000L, toastDurationMs("Hidden", hasAction = true, touch = true))
        val mid = toastDurationMs("x".repeat(60), hasAction = false, touch = false)
        assertTrue("a longer line stays longer", mid > 4_000L && mid < 9_000L)
    }

    private fun open() {
        FormFactor.override(true)
        Motion.reduced = true
        rule.setContent {
            TMPlayerTheme {
                say = rememberActionToast()
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                    Box(Modifier.align(Alignment.BottomCenter).size(80.dp).testTag("below").focusable())
                    TopMessageHost()
                }
            }
        }
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

    private fun press(key: Key) {
        rule.onAllNodes(isRoot())[0].performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test
    fun `on a television the action is drawn, the remote reaches it with Up and OK runs it`() {
        open()
        rule.onNodeWithTag("below").requestFocus()
        rule.runOnUiThread { say("Chat hidden", "Undo") { undone++ } }
        rule.waitForIdle()
        rule.onNodeWithText("Chat hidden").assertExists()
        // It took no focus on its own.
        rule.onNodeWithTag("below").assertIsFocused()
        press(Key.DirectionUp)
        rule.onNodeWithText("Undo").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(1, undone)
        rule.onNodeWithText("Chat hidden").assertDoesNotExist()
    }

    @Test
    fun `Back closes a message first`() {
        open()
        rule.runOnUiThread { say("Chat hidden", "Undo") { undone++ } }
        rule.waitForIdle()
        rule.onNodeWithText("Chat hidden").assertExists()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("Chat hidden").assertDoesNotExist()
        assertEquals(0, undone)
    }
}
