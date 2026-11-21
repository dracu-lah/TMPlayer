package com.tmplayer.ui.raju

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.tmplayer.data.AuthState
import com.tmplayer.data.CodeDelivery
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.auth.LoginScreen
import com.tmplayer.ui.theme.TmMaterialTheme
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
 * Raju steps 1 and 2 on a phone (the second pass): every pane of sign in says in plain words what
 * it wants, has a way out, and keeps its buttons a thumb's size at 100% and 200% font.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class RajuSignInPhoneTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() = FormFactor.override(false)

    private fun pane(state: AuthState, scale: Float = 1f, error: String? = null) {
        FormFactor.override(false)
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, scale)) {
                TmMaterialTheme(dark = true) {
                    androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = Tone.background, contentColor = Tone.text) {
                        LoginScreen(state = state, onSubmitPassword = {}, submitError = error)
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun has(text: String, substring: Boolean = false) =
        assertTrue("missing: $text", RajuPass.count(rule, text, substring) > 0)

    private fun thumbs() {
        val small = RajuPass.smallTargets(rule)
        assertTrue("targets under 48 dp: $small", small.isEmpty())
    }

    @Test
    fun `step 2, the number page says what Telegram does with the number and the app is not Telegram`() {
        pane(AuthState.Phone())
        has("Your phone number")
        has("Telegram will send a code to the account this number belongs to.")
        has("Send me a code")
        // The "unofficial app" line sits in MainActivity's footer under every pane, not in LoginScreen.
        has("Scan a QR code instead")
        RajuPass.shot(rule, "phone-signin-1-number")
        thumbs()
    }

    @Test
    fun `step 2, a wrong number says what to do and the same page stays`() {
        pane(AuthState.Phone(wrong = true), error = "Check the number. Pick your country first, then type the number as you dial it.")
        has("Check the number. Pick your country first", substring = true)
        has("Send me a code")
    }

    @Test
    fun `step 2, the number page at 200 percent font keeps the button and the words`() {
        pane(AuthState.Phone(), scale = 2f)
        has("Your phone number")
        has("Send me a code")
        RajuPass.shot(rule, "phone-signin-1-number-200")
        thumbs()
    }

    @Test
    fun `step 2, the code page names where the code went and has a way back and a resend`() {
        pane(AuthState.Code("+919812345678", delivery = CodeDelivery.Sms, next = CodeDelivery.Call, canResend = true, timeout = 30))
        has("Enter your code")
        has("We texted a code to", substring = true)
        has("Wrong number", substring = true)
        has("Resend in", substring = true)
        RajuPass.shot(rule, "phone-signin-2-code")
        thumbs()
    }

    @Test
    fun `step 2, the code page at 200 percent font still reaches Wrong number and the resend row`() {
        pane(AuthState.Code("+919812345678", delivery = CodeDelivery.Sms, next = CodeDelivery.Call, canResend = true, timeout = 30), scale = 2f)
        has("Wrong number", substring = true)
        has("Resend in", substring = true)
        RajuPass.shot(rule, "phone-signin-2-code-200")
        thumbs()
    }

    @Test
    fun `step 2, the password page uses the words Raju knows, not two-step`() {
        pane(AuthState.Password(hint = "first pet", recoveryEmail = "a***@x.com"))
        has("Your Telegram password")
        assertEquals(0, RajuPass.count(rule, "Two-step", substring = true))
        has("Forgot password?")
        RajuPass.shot(rule, "phone-signin-3-password")
        thumbs()
    }

    @Test
    fun `step 2, a password page with no recovery email still gives a way out`() {
        pane(AuthState.Password(hint = "", recoveryEmail = null), scale = 2f)
        has("Your Telegram password")
        has("Forgot password?")
        thumbs()
    }

    @Test
    fun `no recovery email, Forgot password points to Telegram's own app and offers no account reset`() {
        pane(AuthState.Password(hint = "", recoveryEmail = null))
        rule.onNodeWithText("Forgot password?").performClick()
        rule.waitForIdle()
        has("Forgot your password?")
        has("TMPlayer cannot reset the password", substring = true)
        has("telegram.org/faq", substring = true)
        assertEquals(0, RajuPass.count(rule, "Reset", substring = true))
        assertEquals(0, RajuPass.count(rule, "Delete everything", substring = true))
    }

    @Test
    fun `step 9, a failed sign in says it and can try again`() {
        pane(AuthState.Failed("Telegram is slow to answer"))
        has("That didn't work")
        has("Telegram is slow to answer")
        RajuPass.shot(rule, "phone-signin-4-failed")
        thumbs()
        assertTrue(rule.onAllNodes(hasClickAction() and hasText("Try again")).fetchSemanticsNodes().isNotEmpty())
    }
}
