package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.tmplayer.data.AuthState
import com.tmplayer.data.CodeDelivery
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Raju steps 1, 2 and 9 on the desktop (the second pass): every sign in pane says in plain words
 * what it wants, the keyboard (Tab) reaches the control that gets him on, and a failed pane has a
 * way to try again. The frames go to design/raju-pass-2/.
 */
@OptIn(ExperimentalTestApi::class)
class RajuSignInDesktopTest {

    private val code = AuthState.Code(
        "+919812345678",
        delivery = CodeDelivery.Sms,
        next = CodeDelivery.Call,
        canResend = true,
        timeout = 30,
    )

    private fun androidx.compose.ui.test.ComposeUiTest.show(state: AuthState) = setContent {
        TmMaterialTheme(dark = true) {
            Surface(Modifier.fillMaxSize(), color = Tone.background) { SignInScreen(state) }
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.has(text: String) =
        assertTrue("missing: $text", onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty())

    /** True when Tab (up to a few presses) puts the keyboard on a pressable thing that says [text]. */
    private fun androidx.compose.ui.test.ComposeUiTest.tabReaches(text: String): Boolean {
        val target = hasText(text, substring = true) and hasClickAction() and isFocused()
        repeat(8) {
            if (onAllNodes(target).fetchSemanticsNodes().isNotEmpty()) return true
            onRoot().performKeyInput { pressKey(Key.Tab) }
            waitForIdle()
        }
        return onAllNodes(target).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun `the QR page gives the three steps and Tab reaches the phone number way`() = runComposeUiTest {
        show(AuthState.Qr("tg://login?token=AAAA"))
        has("Open Telegram on your phone")
        has("Go to Settings, then Devices")
        has("Link Desktop Device")
        assertTrue("Tab reaches Sign in with a phone number", tabReaches("Sign in with a phone number"))
        shot("desktop-signin-qr", AuthState.Qr("tg://login?token=AAAA"))
    }

    @Test
    fun `the number page says what the number is for`() = runComposeUiTest {
        show(AuthState.Phone())
        has("Your phone number")
        has("The number your Telegram account uses, with its country code.")
        shot("desktop-signin-number", AuthState.Phone())
    }

    @Test
    fun `the code page names where the code went and Tab reaches Wrong number`() = runComposeUiTest {
        show(code)
        has("Enter the code")
        has("We texted a code to")
        has("Resend in")
        assertTrue("Tab reaches Wrong number", tabReaches("Wrong number"))
        shot("desktop-signin-code", code)
    }

    @Test
    fun `the password page speaks of his Telegram password and has a way out`() = runComposeUiTest {
        val state = AuthState.Password(hint = "first pet", recoveryEmail = "a***@x.com")
        show(state)
        has("Your Telegram password")
        assertTrue("no two-step jargon in the title", onAllNodesWithText("Two-step", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue("Tab reaches Forgot password", tabReaches("Forgot password"))
        shot("desktop-signin-password", state)
    }

    @Test
    fun `a failed sign in says it and has Try again`() = runComposeUiTest {
        val state = AuthState.Failed("Telegram is slow to answer")
        show(state)
        has("Telegram is slow to answer")
        assertTrue("Tab reaches Try again", tabReaches("Try again"))
        shot("desktop-signin-failed", state)
    }

    private fun shot(name: String, state: AuthState) {
        val png = ImageComposeScene(1280, 800, Density(1f)) {
            TmMaterialTheme(dark = true) {
                Surface(Modifier.size(1280.dp, 800.dp), color = Tone.background) { SignInScreen(state) }
            }
        }.use { scene ->
            var now = 0L
            scene.render(now)
            repeat(8) {
                Thread.sleep(50)
                now += 100_000_000L
                scene.render(now)
            }
            now += 600_000_000L
            scene.render(now).encodeToData(EncodedImageFormat.PNG)?.bytes
        }
        if (png == null || png.size < 1000) return
        val file = File("../design/raju-pass-2/$name.png").absoluteFile
        file.parentFile?.mkdirs()
        file.writeBytes(png)
    }
}
