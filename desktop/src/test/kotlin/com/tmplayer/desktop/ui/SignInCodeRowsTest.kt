package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.assertCountEquals
import com.tmplayer.data.AuthState
import com.tmplayer.data.CodeDelivery
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import org.junit.Test

/** The desktop's code step (Raju 2.3): the resend row is always there, greyed under its clock, or says why not. */
@OptIn(ExperimentalTestApi::class)
class SignInCodeRowsTest {

    private val sent = AuthState.Code(
        "+919812345678",
        delivery = CodeDelivery.Sms,
        next = CodeDelivery.Call,
        canResend = true,
    )

    private fun androidx.compose.ui.test.ComposeUiTest.show(state: AuthState.Code) = setContent {
        TmMaterialTheme(dark = true) {
            Surface(Modifier.fillMaxSize(), color = Tone.background) { SignInScreen(state) }
        }
    }

    @Test
    fun `the resend row is there at once, greyed under its countdown`() = runComposeUiTest {
        show(sent.copy(timeout = 60))
        onNode(hasText("Resend in 1:00") and hasClickAction()).assertIsNotEnabled()
        onNode(hasText("Wrong number", substring = true) and hasClickAction()).assertIsEnabled()
    }

    @Test
    fun `when the clock is out it sends the code another way`() = runComposeUiTest {
        show(sent.copy(timeout = 0))
        onNode(hasText("Call me instead") and hasClickAction()).assertIsEnabled()
    }

    @Test
    fun `with no other route it says what is left to do`() = runComposeUiTest {
        show(sent.copy(canResend = false))
        onAllNodesWithText("Telegram has no other way", substring = true).assertCountEquals(1)
        onAllNodesWithText("Resend", substring = true).assertCountEquals(0)
    }
}
