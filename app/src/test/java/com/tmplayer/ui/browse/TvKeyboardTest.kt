package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.components.TvKeyboard
import com.tmplayer.ui.components.TvKeyboardKeys
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
 * The television's own search keyboard: what its keys type, and that the remote walks the grid
 * (Right along a row, Down to the next, OK types) with nothing but the D-pad.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class TvKeyboardTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val drain = DrainMainLooperRule()

    private var words = ""
    private var searched = 0

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
    }

    @Test
    fun `the keys are six rows of six, letters then digits`() {
        assertEquals(6, TvKeyboardKeys.rows.size)
        assertEquals(List(6) { 6 }, TvKeyboardKeys.rows.map { it.size })
        assertEquals("abcdefghijklmnopqrstuvwxyz0123456789", TvKeyboardKeys.rows.flatten().joinToString(""))
    }

    @Test
    fun `space never leads or doubles, delete drops one character and a whole emoji`() {
        assertEquals("", TvKeyboardKeys.space(""))
        assertEquals("night ", TvKeyboardKeys.space("night"))
        assertEquals("night ", TvKeyboardKeys.space("night "))
        assertEquals("nigh", TvKeyboardKeys.delete("night"))
        assertEquals("", TvKeyboardKeys.delete(""))
        assertEquals("a", TvKeyboardKeys.delete("a🎬"))
        assertEquals("night", TvKeyboardKeys.type("nigh", 't'))
    }

    private fun open() {
        FormFactor.override(true)
        Motion.reduced = true
        rule.setContent {
            TMPlayerTheme {
                var text by remember { mutableStateOf(words) }
                Row(Modifier.fillMaxSize().background(Tone.background)) {
                    TvKeyboard(
                        text = text,
                        onText = { text = it; words = it },
                        onSearch = { searched++ },
                        modifier = Modifier.width(288.dp),
                    )
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
    fun `the remote walks the grid and OK types, Delete and Space and Search answer`() {
        open()
        rule.onNodeWithContentDescription("a").requestFocus()
        rule.onNodeWithContentDescription("a").assertIsFocused()
        press(Key.DirectionCenter)
        press(Key.DirectionRight)
        rule.onNodeWithContentDescription("b").assertIsFocused()
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        rule.onNodeWithContentDescription("h").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals("abh", words)
        // Down the column to the foot row: Space first.
        repeat(5) { press(Key.DirectionDown) }
        rule.onNodeWithContentDescription("Space").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals("abh ", words)
        press(Key.DirectionRight)
        rule.onNodeWithContentDescription("Delete").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals("abh", words)
        press(Key.DirectionRight)
        rule.onNodeWithContentDescription("Search").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(1, searched)
    }
}
