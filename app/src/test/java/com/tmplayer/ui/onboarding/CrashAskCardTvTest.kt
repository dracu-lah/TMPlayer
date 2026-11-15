package com.tmplayer.ui.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.tmplayer.data.FormFactor
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
 * S1 on a television, by remote alone: the "Help fix crashes?" card takes the remote on Yes, Right
 * walks to No and then the privacy page, and Back answers No.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class CrashAskCardTvTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val answers = mutableListOf<Boolean>()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private fun show() {
        FormFactor.override(true)
        rule.setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.fillMaxSize()) {
                    CrashAskCard(onAnswer = { answers += it }, modifier = Modifier.align(Alignment.BottomEnd))
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

    private fun button(label: String) = rule.onNode(hasText(label) and hasClickAction())

    private fun press(key: Key) {
        rule.onNode(isFocused()).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test
    fun `the remote lands on Yes and walks to No and down to the privacy page`() {
        show()
        rule.onNodeWithText("Help fix crashes?").assertExists()
        button("Yes, send reports").assertIsFocused()
        press(Key.DirectionRight)
        button("No").assertIsFocused()
        // The privacy page is the full-width button under Yes and No: Down reaches it.
        press(Key.DirectionDown)
        button("Privacy policy").assertIsFocused()
        // The arrows stay in the card: Up does not leave it for the page behind.
        press(Key.DirectionUp)
        rule.onNode(isFocused()).assertExists()
        assertEquals(emptyList<Boolean>(), answers)
    }

    @Test
    fun `OK on Yes answers yes`() {
        show()
        button("Yes, send reports").assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf(true), answers)
    }

    @Test
    fun `Back answers no`() {
        show()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals(listOf(false), answers)
    }
}
