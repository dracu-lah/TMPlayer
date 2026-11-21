package com.tmplayer.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.tmplayer.data.FormFactor
import com.tmplayer.data.LogBundle
import com.tmplayer.data.ReportSender.State
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * U19: Send to developer asks with the safe press focused, as its comment always said and as the
 * title report and every confirm do: an OK meant for something else never sends the report. The
 * buttons stand in one order with the other panels, Cancel then Send report.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class SendToDeveloperPanelTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    @Test
    fun `the question lands on Cancel, not on Send report`() {
        FormFactor.override(true)
        val report = LogBundle.Result(
            text = "report",
            bytes = 2048,
            parts = listOf(LogBundle.Part(LogBundle.Kind.DEVICE, 1)),
            droppedSessions = 0,
            droppedLogLines = 0,
        )
        var state by mutableStateOf<State>(State.Confirm(report))
        rule.setContent {
            TmMaterialTheme(dark = true) {
                SendPanel(state = state, onSend = {}, onCancelSend = {}, onClose = {}, onAgain = {})
            }
        }
        rule.waitForIdle()
        // The panel's window as a television's: out of touch mode and holding focus, which
        // Robolectric does not give a dialog by itself. Then the step's focus is asked again.
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST")
        val roots = (global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<android.view.View>).toList()
        val panel = roots.last().parent
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            panel, "ensureTouchMode",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            panel, "windowFocusChanged",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
        )
        state = State.Sending(report)
        rule.waitForIdle()
        state = State.Confirm(report)
        rule.waitForIdle()
        rule.onNodeWithText("Cancel").assertIsFocused()
        rule.onNodeWithText("Send report").assertIsNotFocused()
        // Cancel first, Send report last at the end edge.
        val cancel = rule.onNodeWithText("Cancel").fetchSemanticsNode().boundsInRoot
        val send = rule.onNodeWithText("Send report").fetchSemanticsNode().boundsInRoot
        assertTrue("Cancel stands before Send report", cancel.right <= send.left)
    }
}
