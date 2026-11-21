package com.tmplayer.ui.auth

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.TmMaterialTheme
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
 * D-3: the typed sign-in pane on a television. The on-screen keyboard covers the lower part of
 * the screen (from about 55% down on the Mi TV stick) and does not move the page, so the Sign in
 * and Start over buttons must end above that, with the password field still focused and the
 * keyboard's Done still submitting.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class LoginFormKeyboardTvTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private var password by mutableStateOf("")
    private var error by mutableStateOf<String?>(null)
    private val submitted = mutableListOf<String>()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private fun show() {
        FormFactor.override(true)
        rule.setContent {
            TmMaterialTheme(dark = true) {
                LoginForm(
                    title = "Two-step verification",
                    blurb = "Your Telegram account has a password. Enter it to finish signing in.",
                    note = "Hint: something to jog the memory",
                    value = password,
                    onValueChange = { password = it },
                    placeholder = "Password",
                    keyboardType = KeyboardType.Password,
                    masked = true,
                    error = error,
                    submitLabel = "Sign in",
                    busyLabel = "Signing in",
                    busy = false,
                    canSubmit = password.isNotEmpty(),
                    onSubmit = { submitted += password },
                    backLabel = "Start over",
                    onBack = {},
                )
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

    /** The keyboard's top edge on the stick: 600 of 1080 px, so 55.5% of the height. */
    private val keyboardTop = 540.dp * 0.55f

    @Test
    fun `the buttons end above the keyboard`() {
        show()
        for (label in listOf("Sign in", "Start over")) {
            val bounds = rule.onNode(hasText(label) and hasClickAction()).getUnclippedBoundsInRoot()
            assertTrue("$label ends at ${bounds.bottom}, the keyboard starts at $keyboardTop", bounds.bottom <= keyboardTop)
        }
    }

    @Test
    fun `an error line under the field still leaves the buttons above the keyboard`() {
        error = "That password is not right."
        show()
        val bounds = rule.onNode(hasText("Sign in") and hasClickAction()).getUnclippedBoundsInRoot()
        assertTrue("Sign in ends at ${bounds.bottom}, the keyboard starts at $keyboardTop", bounds.bottom <= keyboardTop)
    }

    @Test
    fun `the field keeps the focus and the keyboard's Done still signs in`() {
        show()
        rule.onNode(hasSetTextAction()).assertIsFocused()
        rule.onNode(hasSetTextAction()).performTextInput("hunter2")
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).performImeAction()
        rule.waitForIdle()
        assertEquals(listOf("hunter2"), submitted)
        rule.onNodeWithText("Start over").assertExists()
    }
}
