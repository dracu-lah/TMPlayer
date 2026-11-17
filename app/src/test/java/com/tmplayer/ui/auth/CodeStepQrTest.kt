package com.tmplayer.ui.auth

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import com.tmplayer.data.AuthState
import com.tmplayer.data.CodeDelivery
import com.tmplayer.data.FormFactor
import com.tmplayer.i18n.L
import com.tmplayer.ui.theme.TMPlayerTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The code step's way out when the code does not come: "Scan a QR code instead" where the code
 * went to Telegram on another device (an iPad waited for one that never arrived, 2026-11-15), and
 * not where it went by text, when there may be no Telegram to scan with. On a phone and with a
 * remote alike, and pressing it asks for the QR sign in.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CodeStepQrTest {

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private fun open(content: @Composable () -> Unit): ComponentActivity {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { TMPlayerTheme { content() } }
        idle()
        return activity
    }

    private fun idle() = repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }

    private fun nodes(activity: ComponentActivity): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(view: View) {
            if (view is ViewRootForTest) out += view.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(activity.window.decorView)
        return out
    }

    private fun qrButton(activity: ComponentActivity) = nodes(activity).firstOrNull { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == L.loginUseQr } &&
            node.config.getOrNull(SemanticsActions.OnClick) != null
    }

    private fun codeStep(delivery: CodeDelivery, tv: Boolean, onQr: () -> Unit = {}): ComponentActivity {
        FormFactor.override(tv)
        return open {
            LoginScreen(
                state = AuthState.Code(phoneNumber = "+39 000 000 0000", delivery = delivery, length = 5),
                onSubmitPassword = {},
                submitError = null,
                onRefreshQr = onQr,
            )
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w390dp-h844dp-port-night-xhdpi")
    fun `a code sent to the Telegram app offers the QR code on a phone, and pressing it asks for it`() {
        var asked = 0
        val activity = codeStep(CodeDelivery.TelegramApp, tv = false) { asked++ }
        val button = qrButton(activity)
        assertNotNull("the QR way is offered", button)
        button!!.config[SemanticsActions.OnClick].action?.invoke()
        idle()
        assertEquals("the QR sign in was asked for once", 1, asked)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `a code sent to the Telegram app offers the QR code with a remote too`() {
        assertNotNull(qrButton(codeStep(CodeDelivery.TelegramApp, tv = true)))
    }

    @Test
    @Config(sdk = [35], qualifiers = "w390dp-h844dp-port-night-xhdpi")
    fun `a code sent by text offers no QR code`() {
        assertNull(qrButton(codeStep(CodeDelivery.Sms, tv = false)))
    }
}
