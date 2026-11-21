package com.tmplayer.ui.raju

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.tmplayer.data.AuthState
import com.tmplayer.data.CodeDelivery
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.auth.LoginScreen
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Raju steps 1 and 2 on a television (the second pass): the QR page tells him three things to do
 * with his phone in hand, and on every pane the remote lands on a control and can walk to the way
 * out (Start over, Wrong number, Use my phone number) without a dead end.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class RajuSignInTvTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() = FormFactor.override(false)

    private fun pane(state: AuthState) {
        FormFactor.override(true)
        rule.setContent {
            TmMaterialTheme(dark = true) {
                androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = Tone.background, contentColor = Tone.text) {
                    androidx.compose.runtime.CompositionLocalProvider(androidx.tv.material3.LocalContentColor provides Tone.text) {
                        LoginScreen(state = state, onSubmitPassword = {}, submitError = null)
                    }
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

    private fun has(text: String, substring: Boolean = false) =
        assertTrue("missing: $text", RajuPass.count(rule, text, substring) > 0)

    private fun focusedCount() = rule.onAllNodes(isFocused()).fetchSemanticsNodes().size

    /** Walks Down, Right, Down, Left a few times and returns true when a press reached [text]. */
    private fun remoteReaches(text: String): Boolean {
        val target = hasText(text, substring = true) and hasClickAction()
        val d = Key.DirectionDown
        val u = Key.DirectionUp
        val l = Key.DirectionLeft
        val r = Key.DirectionRight
        val keys = listOf(d, d, d, d, d, l, l, r, r, u, u, u, u, u, l, d, d, r)
        trail.clear()
        fun here(): Boolean {
            val on = rule.onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()
            trail += on?.config?.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: "(none)"
            return rule.onAllNodes(target and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        if (here()) return true
        for (k in keys) {
            press(k)
            if (here()) return true
        }
        return false
    }

    private val trail = mutableListOf<String>()

    private fun assertReached(text: String) {
        val ok = remoteReaches(text)
        assertTrue("the remote reaches $text, focus walked: $trail", ok)
    }

    @Test
    fun `the QR page tells him the three things to do on his phone`() {
        pane(AuthState.Qr("tg://login?token=AAAA"))
        has("Sign in to Telegram")
        has("Open Telegram on your phone")
        has("Go to Settings, then Devices")
        has("Link Desktop Device", substring = true)
        RajuPass.shot(rule, "tv-signin-qr")
    }

    @Test
    fun `the QR page has a button for the other way and the remote reaches it`() {
        pane(AuthState.Qr("tg://login?token=AAAA"))
        has("Use my phone number")
        assertReached("Use my phone number")
    }

    @Test
    fun `the number page takes the remote and says what the number is for`() {
        pane(AuthState.Phone())
        has("Your phone number")
        press(Key.DirectionDown)
        assertTrue("the remote lands on a control", focusedCount() > 0)
        // The number box keeps the arrows for its cursor unless the screen lets them go: the remote
        // must still reach the button that sends the code.
        assertReached("Send me a code")
        RajuPass.shot(rule, "tv-signin-number")
    }

    @Test
    fun `the code page lets the remote reach Wrong number`() {
        pane(AuthState.Code("+919812345678", delivery = CodeDelivery.TelegramApp, next = CodeDelivery.Sms, canResend = true, timeout = 20))
        has("Enter your code")
        has("Check Telegram, not your texts", substring = true)
        assertReached("Wrong number")
        RajuPass.shot(rule, "tv-signin-code")
    }

    @Test
    fun `the password page lets the remote reach Forgot password`() {
        pane(AuthState.Password(hint = "first pet", recoveryEmail = "a***@x.com"))
        has("Your Telegram password")
        assertReached("Forgot password")
        RajuPass.shot(rule, "tv-signin-password")
    }

    @Test
    fun `a failed sign in on the television can try again with the remote`() {
        pane(AuthState.Failed("Telegram is slow to answer"))
        assertReached("Try again")
    }
}
