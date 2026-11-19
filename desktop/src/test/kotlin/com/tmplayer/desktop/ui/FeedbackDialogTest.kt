package com.tmplayer.desktop.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.tmplayer.data.Feedback
import com.tmplayer.data.FeedbackReport
import com.tmplayer.data.FeedbackSender
import com.tmplayer.data.FeedbackSender.State
import com.tmplayer.data.LogUpload
import com.tmplayer.data.MemoryFeedbackDraft
import com.tmplayer.data.TitleUpload
import com.tmplayer.i18n.L
import com.tmplayer.ui.components.FeedbackActions
import com.tmplayer.ui.components.FeedbackDialog
import com.tmplayer.ui.components.FeedbackTags
import com.tmplayer.ui.theme.TmMaterialTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Report a problem" and "Request a feature" (HX64) on the desktop, through the real sender: the
 * keyboard starts in the message, an empty one stays on the form, the review shows the words and
 * the facts as they go, the keyboard is on Back there, and Send sends once.
 */
@OptIn(ExperimentalTestApi::class)
class FeedbackDialogTest {

    private val facts = Feedback.Facts("1.40.0", Feedback.Platform.Linux, "Fedora Linux 43, AppImage", "en")
    private val sent = mutableListOf<String>()
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun sender(answer: TitleUpload.Outcome = TitleUpload.Outcome.Sent("FB-7K3Q-M4XP")) = FeedbackSender(
        MemoryFeedbackDraft(),
        upload = { text -> sent += text; answer },
        uploadLogs = { LogUpload.Outcome.Sent("TMP-7K3Q") },
    ).also { it.buildLogs = { "TMPlayer logs\nline" } }

    private fun androidx.compose.ui.test.ComposeUiTest.show(sender: FeedbackSender, kind: FeedbackReport.Kind = FeedbackReport.Kind.Problem) {
        sender.open(scope, kind, facts)
        setContent {
            val state by sender.state.collectAsState()
            TmMaterialTheme(dark = true) {
                if (state !is State.Idle && state !is State.Opening) {
                    FeedbackDialog(
                        state,
                        sender.logsAvailable,
                        FeedbackActions(
                            onEdit = sender::edit,
                            onNext = { sender.review(scope) },
                            onBack = sender::back,
                            onSend = { sender.send(scope) },
                            onClose = { sender.close(scope) },
                            onGithub = {},
                            onEmail = {},
                        ),
                    )
                }
            }
        }
        waitForIdle()
    }

    @Test
    fun `the keyboard starts in the message, and an empty one stays on the form`() = runComposeUiTest {
        val s = sender()
        show(s)
        onNodeWithTag(FeedbackTags.MESSAGE).assertIsFocused()
        onNode(hasText(L.feedbackNext) and hasClickAction()).performClick()
        waitForIdle()
        onNodeWithText(L.feedbackMessageEmpty).assertExists()
        assertTrue(sent.isEmpty())
        save("desktop-form-empty.png")
    }

    @Test
    fun `the review shows what goes word for word, Back is under the keyboard and Send sends once`() = runComposeUiTest {
        val s = sender()
        show(s)
        onNodeWithTag(FeedbackTags.MESSAGE).performTextInput("Subtitles drift after a seek.")
        onNodeWithTag(FeedbackTags.CONTACT).performTextInput("me@example.org")
        save("desktop-form.png")
        onNode(hasText(L.feedbackNext) and hasClickAction()).performClick()
        waitForIdle()
        onNodeWithText(L.feedbackReviewTitle).assertExists()
        onNodeWithText("Subtitles drift after a seek.").assertExists()
        onNodeWithText("me@example.org").assertExists()
        onNodeWithText("Linux, Fedora Linux 43, AppImage, TMPlayer 1.40.0, en").assertExists()
        onNodeWithText(L.feedbackLogsNone).assertExists()
        onNode(hasText(L.commonBack) and hasClickAction()).assertIsFocused()
        save("desktop-review.png")
        // Enter on the focused button is the safe answer: back to the form, nothing sent.
        onNode(isFocused()).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertIs<State.Editing>(s.state.value)
        onNode(hasText(L.feedbackNext) and hasClickAction()).performClick()
        waitForIdle()
        onNode(hasText(L.feedbackSend) and hasClickAction()).performClick()
        waitForIdle()
        assertEquals(1, sent.size)
        onNodeWithTag(FeedbackTags.CODE).assertExists()
        onNodeWithText("FB-7K3Q-M4XP").assertExists()
        save("desktop-sent.png")
    }

    @Test
    fun `ticked logs show their size in the review`() = runComposeUiTest {
        val s = sender()
        show(s, FeedbackReport.Kind.Feature)
        onNodeWithText(L.feedbackFeatureTitle).assertExists()
        onNodeWithTag(FeedbackTags.MESSAGE).performTextInput("A sleep timer")
        onNodeWithText(L.feedbackLogsTick).performClick()
        onNode(hasText(L.feedbackNext) and hasClickAction()).performClick()
        waitForIdle()
        val size = com.tmplayer.data.MediaText.formatSize("TMPlayer logs\nline".encodeToByteArray().size.toLong())
        onNodeWithText(L.feedbackLogsSize(size)).assertExists()
    }

    @Test
    fun `offline keeps it with Try again`() = runComposeUiTest {
        val s = sender(TitleUpload.Outcome.NoConnection)
        show(s)
        onNodeWithTag(FeedbackTags.MESSAGE).performTextInput("It froze.")
        onNode(hasText(L.feedbackNext) and hasClickAction()).performClick()
        waitForIdle()
        onNode(hasText(L.feedbackSend) and hasClickAction()).performClick()
        waitForIdle()
        onNodeWithText(L.feedbackQueuedTitle).assertExists()
        onNode(hasText(L.commonTryAgain) and hasClickAction()).assertExists()
        save("desktop-queued.png")
    }

    @Test
    fun `at a phone's size the form and the review fit and scroll, not cut`() = runDesktopComposeUiTest(411, 891) {
        val s = sender()
        show(s)
        onNodeWithTag(FeedbackTags.MESSAGE).performTextInput("Downloads stop when the screen turns off.")
        onNode(hasText(L.feedbackNext) and hasClickAction()).assertExists()
        save("phone-form.png")
        onNode(hasText(L.feedbackNext) and hasClickAction()).performClick()
        waitForIdle()
        onNodeWithText("Downloads stop when the screen turns off.").assertExists()
        onNodeWithText(L.feedbackContactNone).assertExists()
        onNode(hasText(L.feedbackSend) and hasClickAction()).assertExists()
        save("phone-review.png")
    }

    private fun <T> assertIs(type: Class<T>, value: Any?) = assertTrue("$value", type.isInstance(value))

    private inline fun <reified T> assertIs(value: Any?) = assertIs(T::class.java, value)

    /** A picture of the dialog for review, written only when TMPLAYER_SHOTS names a folder. */
    private fun androidx.compose.ui.test.ComposeUiTest.save(name: String) {
        val out = System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        val image = onNode(isDialog()).captureToImage().toAwtImage()
        javax.imageio.ImageIO.write(image, "png", File(out).apply { mkdirs() }.resolve(name))
    }
}
