package com.tmplayer.ui.auth

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Density
import com.tmplayer.data.AuthState
import com.tmplayer.data.CodeDelivery
import com.tmplayer.data.FormFactor
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val CODE_ROW = AuthState.Code(
    "+919812345678",
    delivery = CodeDelivery.Sms,
    next = CodeDelivery.Call,
    canResend = true,
)

/** The words around the number and the code (Raju 2.1 and 2.3) on a phone. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class LoginCountryAndResendPhoneTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() = FormFactor.override(false)

    private fun code(state: AuthState.Code, error: String? = null, fontScale: Float = 1f) {
        FormFactor.override(false)
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                TmMaterialTheme(dark = true) {
                    LoginScreen(state = state, onSubmitPassword = {}, submitError = error)
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `the country row reads as a chip with its dial code and is one press`() {
        FormFactor.override(false)
        var opened = 0
        rule.setContent {
            TmMaterialTheme(dark = true) {
                PaneChooser(label = "India", trailing = SignInWords.dialChip("91"), onClick = { opened++ })
            }
        }
        rule.onNodeWithText("India").assertExists()
        rule.onNodeWithText("+91").assertExists()
        rule.onNode(hasClickAction()).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `the resend row is there at once, greyed under its countdown, below a wrong code`() {
        code(CODE_ROW.copy(wrong = true, timeout = 60), error = L.errorsCodeCheck)
        rule.onNodeWithText("Wrong code. Check the latest message from Telegram.").assertExists()
        rule.onNode(hasText("Resend in 1:00") and hasClickAction()).assertIsNotEnabled()
        rule.onNodeWithText("Wrong number", substring = true).assertExists()
    }

    @Test
    fun `at a large font the resend row and the words are still there`() {
        code(CODE_ROW.copy(wrong = true, timeout = 45), error = L.errorsCodeCheck, fontScale = 2f)
        rule.onNode(hasText("Resend in 0:45") and hasClickAction()).assertIsNotEnabled()
        rule.onNodeWithText("Wrong code", substring = true).assertExists()
    }

    @Test
    fun `with no other route the row says what is left to do instead of vanishing`() {
        code(CODE_ROW.copy(canResend = false))
        rule.onNodeWithText("Telegram has no other way", substring = true).assertExists()
        rule.onAllNodesWithText("Resend", substring = true).assertCountEquals(0)
    }

    @Test
    fun `once the clock is out the same row sends the code another way`() {
        code(CODE_ROW.copy(timeout = 0))
        rule.onNode(hasText("Call me instead") and hasClickAction()).assertIsEnabled()
    }

    @Test
    fun `the shared words`() {
        assertEquals("+91", SignInWords.dialChip("91"))
        assertEquals("+1", SignInWords.dialChip("+1 "))
        assertNull(SignInWords.dialChip(""))
        assertNull(SignInWords.dialChip("+"))
        assertNull(SignInWords.resendNone(CODE_ROW))
    }
}

/** The same rows on a television: the remote reaches the resend row and is never trapped by a greyed one. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class LoginCountryAndResendTvTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() = FormFactor.override(false)

    private fun code(state: AuthState.Code, error: String? = null) {
        FormFactor.override(true)
        rule.setContent {
            TmMaterialTheme(dark = true) {
                Column(Modifier.fillMaxSize()) { LoginScreen(state = state, onSubmitPassword = {}, submitError = error) }
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

    private fun focusStartOver() {
        rule.onNode(hasText("Start over") and hasClickAction()).requestFocus()
        rule.onNode(hasText("Start over") and hasClickAction()).assertIsFocused()
    }

    @Test
    fun `the remote reaches the resend row from Start over, Down then Left`() {
        code(CODE_ROW.copy(wrong = true, timeout = 0), L.errorsCodeCheck)
        rule.onNodeWithText("Wrong code. Check the latest message from Telegram.").assertExists()
        focusStartOver()
        press(Key.DirectionDown)
        rule.onNode(hasText("Wrong number", substring = true) and hasClickAction()).assertIsFocused()
        press(Key.DirectionLeft)
        rule.onNode(hasText("Call me instead") and hasClickAction()).assertIsFocused()
        press(Key.DirectionRight)
        rule.onNode(hasText("Wrong number", substring = true) and hasClickAction()).assertIsFocused()
    }

    @Test
    fun `a greyed resend row never traps the remote`() {
        code(CODE_ROW.copy(timeout = 60))
        rule.onNode(hasText("Resend in 1:00") and hasClickAction()).assertIsNotEnabled()
        focusStartOver()
        press(Key.DirectionDown)
        rule.onNode(hasText("Wrong number", substring = true) and hasClickAction()).assertIsFocused()
    }

    @Test
    fun `with no other route the television says so and Wrong number stays reachable`() {
        code(CODE_ROW.copy(canResend = false))
        rule.onNodeWithText("Telegram has no other way", substring = true).assertExists()
        focusStartOver()
        press(Key.DirectionDown)
        rule.onNode(hasText("Wrong number", substring = true) and hasClickAction()).assertIsFocused()
    }

    @Test
    fun `the country chip on a television shows the code beside the name`() {
        FormFactor.override(true)
        rule.setContent {
            TmMaterialTheme(dark = true) {
                PaneChooser(label = "India", trailing = SignInWords.dialChip("91"), onClick = {})
            }
        }
        rule.onNodeWithText("India").assertExists()
        rule.onNodeWithText("+91").assertExists()
    }
}
