package com.tmplayer.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import com.tmplayer.data.Feedback
import com.tmplayer.data.FeedbackReport
import com.tmplayer.data.FeedbackSender
import com.tmplayer.data.FeedbackSender.State
import com.tmplayer.data.FormFactor
import com.tmplayer.data.LogUpload
import com.tmplayer.data.MemoryFeedbackDraft
import com.tmplayer.data.TitleUpload
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.components.FeedbackActions
import com.tmplayer.ui.components.FeedbackTags
import com.tmplayer.ui.theme.TmMaterialTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * "Report a problem" and "Request a feature" (HX64) by remote alone, through the real sender: the
 * remote starts in the message, Down walks the fields to the buttons, the review starts on Back
 * (the press that sends nothing), Right reaches Send, and the code is shown with Close in focus.
 * The TV panel is pictured for review under build/device-test/feedback; the phone's and the
 * desktop's dialog (the same shared composable) are pictured by the desktop's FeedbackDialogTest.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedbackTvContentTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private val facts = Feedback.Facts("1.40.0", Feedback.Platform.AndroidTv, "Xiaomi MIBOX4, Android 9", "en")
    private val sent = mutableListOf<String>()
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private fun sender() = FeedbackSender(
        MemoryFeedbackDraft(),
        upload = { text -> sent += text; TitleUpload.Outcome.Sent("FB-7K3Q-M4XP") },
        uploadLogs = { LogUpload.Outcome.Sent("TMP-7K3Q") },
    ).also { it.buildLogs = { "TMPlayer logs\nline" } }

    private fun actions(sender: FeedbackSender) = FeedbackActions(
        onEdit = sender::edit,
        onNext = { sender.review(scope) },
        onBack = sender::back,
        onSend = { sender.send(scope) },
        onClose = { sender.close(scope) },
    )

    private fun show(sender: FeedbackSender, tv: Boolean) {
        FormFactor.override(tv)
        sender.open(scope, FeedbackReport.Kind.Problem, facts)
        rule.setContent {
            val state by sender.state.collectAsState()
            TmMaterialTheme(dark = true) {
                if (state !is State.Idle && state !is State.Opening) {
                    FeedbackTvContent(state, sender.logsAvailable, actions(sender))
                }
            }
        }
        if (tv) remoteWindow()
        rule.waitForIdle()
    }

    /** A window the remote works in: out of touch mode and holding focus, as on a television. */
    private fun remoteWindow() {
        val viewRoot = rule.activity.window.decorView.parent
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "ensureTouchMode",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "windowFocusChanged",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
        )
    }

    private fun button(label: String) = rule.onNode(hasText(label) and hasClickAction())

    private fun press(key: Key) {
        rule.onNode(isFocused()).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `the remote starts in the message and Down walks the fields to the buttons`() {
        show(sender(), tv = true)
        rule.onNodeWithTag(FeedbackTags.MESSAGE).assertIsFocused()
        press(Key.DirectionDown)
        rule.onNodeWithTag(FeedbackTags.CONTACT).assertIsFocused()
        press(Key.DirectionDown)
        rule.onNodeWithText(L.feedbackLogsTick).assertIsFocused()
        press(Key.DirectionDown)
        // The buttons are one row; the geometry decides which the Down lands on.
        runCatching { button(L.commonClose).assertIsFocused() }.onSuccess { press(Key.DirectionRight) }
        button(L.feedbackNext).assertIsFocused()
        // OK on Next with nothing typed stays on the form and says why.
        press(Key.DirectionCenter)
        rule.onNodeWithText(L.feedbackMessageEmpty).assertExists()
        assertTrue(sent.isEmpty())
        shot("tv-form.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `the review starts on Back, Right reaches Send, and the code shows with Close focused`() {
        val s = sender()
        show(s, tv = true)
        rule.onNodeWithTag(FeedbackTags.MESSAGE).performTextInput("The picture freezes after a minute.")
        rule.waitForIdle()
        rule.runOnIdle { s.review(scope) }
        rule.waitForIdle()
        rule.onNodeWithText(L.feedbackReviewTitle).assertExists()
        rule.onNodeWithText("The picture freezes after a minute.").assertExists()
        rule.onNodeWithText("Android TV or Google TV, Xiaomi MIBOX4, Android 9, TMPlayer 1.40.0, en").assertExists()
        button(L.commonBack).assertIsFocused()
        shot("tv-review.png")
        press(Key.DirectionRight)
        button(L.feedbackSend).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(1, sent.size)
        rule.onNodeWithText("FB-7K3Q-M4XP").assertExists()
        button(L.commonClose).assertIsFocused()
        shot("tv-sent.png")
    }

    private fun shot(name: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val file = File("../build/device-test/feedback/$name").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }
}
