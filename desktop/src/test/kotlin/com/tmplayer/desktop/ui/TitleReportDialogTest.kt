package com.tmplayer.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import com.tmplayer.data.TitleReport
import com.tmplayer.data.TitleSender.State
import com.tmplayer.ui.components.TitleReportDialog
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Wrong title?" (N1) on the desktop: the pair word for word, the keyboard on No thanks first,
 * Send only after the tick, and the chat name only on its own tick.
 */
@OptIn(ExperimentalTestApi::class)
class TitleReportDialogTest {

    private val calls = mutableListOf<String>()

    private fun androidx.compose.ui.test.ComposeUiTest.show(start: State) {
        setContent {
            var state by remember { mutableStateOf(start) }
            TmMaterialTheme(dark = true) {
                TitleReportDialog(
                    state = state,
                    onAgree = { on -> calls += "agree $on"; (state as? State.Review)?.let { state = it.copy(agreed = on) } },
                    onIncludeChat = { on -> calls += "chat $on"; (state as? State.Review)?.let { state = it.copy(includeChat = on) } },
                    onSend = { calls += "send" },
                    onDecline = { calls += "decline" },
                    onClose = { calls += "close" },
                )
            }
        }
        waitForIdle()
    }

    private val review = State.Review("Night Train +91 98765 43210 @someone.mkv", "Night Train", "Home videos")

    @Test
    fun `the scrubbed pair is shown, the keyboard is on No thanks and Send waits for the tick`() = runComposeUiTest {
        show(review)
        onNodeWithText("Night Train @user.mkv").assertExists()
        onNodeWithText("Night Train").assertExists()
        onNodeWithText("No thanks").assertIsFocused()
        onNode(hasText("Send") and hasClickAction()).assertIsNotEnabled()
        // Enter on the focused button is the safe answer.
        onNode(isFocused()).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(listOf("decline"), calls)
    }

    @Test
    fun `ticking the box makes Send work and Send sends`() = runComposeUiTest {
        show(review)
        onNodeWithText("Yes, send these two lines").performClick()
        waitForIdle()
        onNode(hasText("Send") and hasClickAction()).assertIsEnabled()
        onNode(hasText("Send") and hasClickAction()).performClick()
        assertEquals(listOf("agree true", "send"), calls)
    }

    @Test
    fun `the chat name has its own tick, off until pressed`() = runComposeUiTest {
        show(review)
        onNodeWithText("Also send the chat name: Home videos").performClick()
        waitForIdle()
        assertEquals(listOf("chat true"), calls)
        onNode(hasText("Send") and hasClickAction()).assertIsNotEnabled()
    }

    @Test
    fun `after a send the code is shown with Close`() = runComposeUiTest {
        show(State.Sent(TitleReport.of("Night.Train.2024.mkv", "Night Train")!!, "NAME-7K3Q", duplicate = false))
        onNodeWithText("NAME-7K3Q").assertExists()
        onNodeWithText("Close").performClick()
        assertEquals(listOf("close"), calls)
    }

    @Test
    fun `a pair held for later says so`() = runComposeUiTest {
        show(State.Queued(TitleReport.of("Night.Train.2024.mkv", "Night Train")!!))
        onNodeWithText("Kept for later").assertExists()
    }
}
