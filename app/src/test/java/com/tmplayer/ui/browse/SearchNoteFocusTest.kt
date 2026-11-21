package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.tv.material3.Text
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.components.TmButton
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The foot of a chat search that read only the newest part of the chat, on the television: Down
 * from the last row of results reaches Keep looking, OK presses it, and Up goes back to the results.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class SearchNoteFocusTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private var deeper = 0

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
    }

    private fun open(canGoDeeper: Boolean) {
        FormFactor.override(true)
        Motion.reduced = true
        rule.setContent {
            TMPlayerTheme {
                LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize().background(Tone.background)) {
                    items(6) { index -> TmButton(onClick = {}) { Text("Result $index") } }
                    searchNoteItem("Only the newest 500 messages of this chat were searched.", if (canGoDeeper) ({ deeper++ }) else null)
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
        rule.onNode(androidx.compose.ui.test.isRoot()).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test
    fun `Down from the last results reaches Keep looking and OK presses it`() {
        open(canGoDeeper = true)
        rule.onNodeWithText("Result 4").requestFocus()
        rule.onNodeWithText("Result 4").assertIsFocused()
        press(Key.DirectionDown)
        rule.onNodeWithText("Keep looking").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(1, deeper)
        // Up goes back into the results (the tile over the button, after the note's text).
        press(Key.DirectionUp)
        rule.onNodeWithText("Keep looking").assertIsNotFocused()
        val back = (3..5).count { runCatching { rule.onNodeWithText("Result $it").assertIsFocused() }.isSuccess }
        assertEquals(1, back)
    }

    @Test
    fun `with nothing deeper the note is said and the remote stays on the results`() {
        open(canGoDeeper = false)
        rule.onNodeWithText("Only the newest 500 messages of this chat were searched.").assertExists()
        rule.onNodeWithText("Result 5").requestFocus()
        press(Key.DirectionDown)
        rule.onNodeWithText("Result 5").assertIsFocused()
        assertEquals(0, deeper)
    }
}
