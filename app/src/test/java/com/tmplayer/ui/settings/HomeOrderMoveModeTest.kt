package com.tmplayer.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.tmplayer.data.FormFactor
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
 * Settings, Home, Order of rows on a television (EG-F7, EG-F23, EG-F27), by remote alone: OK picks a
 * row up, Up and Down move it (nothing is written yet), OK puts it down and writes the order, and
 * Back puts it back and writes nothing.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class HomeOrderMoveModeTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private val written = mutableListOf<List<String>>()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private fun show() {
        FormFactor.override(true)
        rule.setContent {
            TmMaterialTheme(dark = true) {
                Column(Modifier.fillMaxSize()) {
                    HomeOrderEditor(
                        rows = listOf(
                            HomeOrderRow("continue", "Continue watching"),
                            HomeOrderRow("recent", "Recently added"),
                            HomeOrderRow("chat-1", "Chat 1"),
                            HomeOrderRow("chat-2", "Chat 2"),
                        ),
                        onOrder = { written += it },
                        remote = true,
                    )
                }
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

    private fun press(key: Key) {
        rule.onNode(androidx.compose.ui.test.isFocused()).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test
    fun `OK picks a row up, Up moves it, OK drops it and the order is written once`() {
        show()
        rule.onNode(hasText("Chat 1")).requestFocus()
        rule.onNode(hasText("Chat 1")).assertIsFocused()

        press(Key.DirectionCenter)
        rule.onAllNodesWithText("Moving. Up and Down move it, OK puts it down, Back puts it back").assertCountEquals(1)
        rule.onNode(hasText("Chat 1") and hasText("3 of 4")).assertIsFocused()

        press(Key.DirectionUp)
        rule.onNode(hasText("Chat 1") and hasText("2 of 4")).assertIsFocused()
        press(Key.DirectionUp)
        rule.onNode(hasText("Chat 1") and hasText("1 of 4")).assertIsFocused()
        // The top is the end of the line: one more Up changes nothing.
        press(Key.DirectionUp)
        rule.onNode(hasText("Chat 1") and hasText("1 of 4")).assertIsFocused()
        assertEquals("nothing is written while the row is in hand", emptyList<List<String>>(), written)

        press(Key.DirectionCenter)
        assertEquals(listOf(listOf("chat-1", "continue", "recent", "chat-2")), written)
        // Put down, the row keeps the remote, and Down walks on to the next row as it does anywhere.
        rule.onNode(hasText("Chat 1")).assertIsFocused()
    }

    @Test
    fun `Down moves a picked row down and Back puts it back without writing`() {
        show()
        rule.onNode(hasText("Continue watching")).requestFocus()
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        rule.onNode(hasText("Continue watching") and hasText("3 of 4")).assertIsFocused()

        press(Key.Back)
        rule.onNode(hasText("Continue watching") and hasText("1 of 4")).assertIsFocused()
        assertEquals(emptyList<List<String>>(), written)
    }

    @Test
    fun `a row that is not picked leaves Up and Down to the focus`() {
        show()
        rule.onNode(hasText("Recently added")).requestFocus()
        press(Key.DirectionDown)
        rule.onNode(hasText("Chat 1")).assertIsFocused()
        assertEquals(emptyList<List<String>>(), written)
    }
}
