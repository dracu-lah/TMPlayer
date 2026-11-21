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
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.tv.material3.Text
import com.tmplayer.data.FormFactor
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.components.TmButton
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
 * R1: the row that ends a chat's grid when "Hide small clips" left videos out, on the television
 * (Down from the last row reaches Show them, OK presses it), with the number only when it is
 * exact, and nothing when nothing is hidden.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class SmallHiddenRowTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private var shown = 0

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
    }

    private fun open(count: Int, exact: Boolean) {
        FormFactor.override(true)
        Motion.reduced = true
        rule.setContent {
            TMPlayerTheme {
                LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize().background(Tone.background)) {
                    items(6) { index -> TmButton(onClick = {}) { Text("Video $index") } }
                    smallHiddenItem(count, exact, onShow = { shown++ })
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
    fun `Down from the last videos reaches Show them and OK presses it`() {
        open(3, exact = true)
        rule.onNodeWithText("Video 4").requestFocus()
        rule.onNodeWithText("Video 4").assertIsFocused()
        press(Key.DirectionDown)
        rule.onNodeWithText(L.gridFilterShowThem).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(1, shown)
    }

    @Test
    fun `an exact count is said, and a lower bound says some`() {
        open(3, exact = true)
        rule.onNodeWithText("3 small videos hidden").assertExists()
        rule.onNodeWithText(L.gridSmallHiddenSome).assertDoesNotExist()
    }

    @Test
    fun `before the whole chat is read the row promises no number`() {
        open(3, exact = false)
        rule.onNodeWithText("Some small videos are hidden").assertExists()
        rule.onNodeWithText("3 small videos hidden").assertDoesNotExist()
    }

    @Test
    fun `one hidden video is singular, and nothing hidden draws no row`() {
        open(1, exact = true)
        rule.onNodeWithText("1 small video hidden").assertExists()
    }

    @Test
    fun `nothing hidden draws no row`() {
        open(0, exact = true)
        rule.onNodeWithText(L.gridFilterShowThem).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-port-xhdpi", fontScale = 1.3f)
    fun `at a large font on a phone the button drops under the words and is still pressable`() {
        FormFactor.override(false)
        Motion.reduced = true
        rule.setContent {
            TMPlayerTheme {
                LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize().background(Tone.background)) {
                    smallHiddenItem(12, exact = true, onShow = { shown++ })
                }
            }
        }
        val words = rule.onNodeWithText("12 small videos hidden").getUnclippedBoundsInRoot()
        val button = rule.onNodeWithText(L.gridFilterShowThem).getUnclippedBoundsInRoot()
        assertTrue("the button sits under the words: $words $button", button.top >= words.bottom)
        rule.onNodeWithText(L.gridFilterShowThem).performClick()
        assertEquals(1, shown)
    }
}
