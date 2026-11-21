package com.tmplayer.ui.components

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.tmplayer.data.FormFactor
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

/**
 * Clear history and the other clear-alls ask first on a phone and under a remote (HX59,
 * [rememberClearAllConfirm]): the prompt says what goes, the remote starts on Cancel, Cancel puts
 * it back on the Clear button, Clear clears once, a second press asks once, an empty list asks
 * nothing, and a list emptied meanwhile closes the question and clears nothing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class ClearAllConfirmTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private val cards = mutableIntStateOf(3)
    private val cleared = mutableListOf<Int>()

    private fun show() {
        rule.setContent {
            TmMaterialTheme(dark = true) {
                val ask = rememberClearAllConfirm(
                    count = cards.intValue,
                    title = "Clear history?",
                    message = "All ${cards.intValue} videos leave Continue watching and Watched.",
                    detail = "The videos stay in their chats.",
                ) { cleared += cards.intValue }
                val first = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
                Column {
                    Button(onClick = ask, modifier = Modifier.focusRequester(first)) { Text("Clear history") }
                    Button(onClick = { ask(); ask() }) { Text("Twice") }
                }
            }
        }
        idle()
    }

    private fun idle() {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        repeat(40) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
            Thread.sleep(2)
        }
        rule.waitForIdle()
    }

    private fun leaveTouchMode(root: Any?) {
        ReflectionHelpers.callInstanceMethod<Any>(root, "ensureTouchMode", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false))
        ReflectionHelpers.callInstanceMethod<Any>(root, "windowFocusChanged", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
    }

    /** The newest window (the prompt's, or the activity's once it is gone) takes the remote. */
    private fun windowTakesKeys() {
        leaveTouchMode(rule.activity.window.decorView.parent)
        org.robolectric.shadows.ShadowDialog.getLatestDialog()?.window?.decorView?.parent?.let { leaveTouchMode(it) }
        idle()
    }

    private fun press(key: Key, on: String? = null) {
        rule.onNode(if (on == null) isFocused() else hasText(on) and isFocused()).performKeyInput { pressKey(key) }
        idle()
    }

    private fun waitFor(text: String, present: Boolean = true) {
        repeat(100) {
            idle()
            if ((rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()) == present) return
        }
        error((if (present) "never showed: " else "never went: ") + text)
    }

    private fun waitFocused(text: String) {
        repeat(100) {
            idle()
            if (runCatching { rule.onNode(hasText(text)).assertIsFocused() }.isSuccess) return
        }
        rule.onNode(hasText(text)).assertIsFocused()
    }

    private fun asking() = rule.onAllNodes(hasText("Clear history?")).fetchSemanticsNodes().size

    @Test
    fun `under a remote Clear history asks and the remote starts on Cancel`() {
        FormFactor.override(true)
        show()
        windowTakesKeys()
        waitFocused("Clear history")
        press(Key.DirectionCenter)
        waitFor("Clear history?")
        windowTakesKeys()
        waitFocused("Cancel")
        assertEquals(emptyList<Int>(), cleared)
    }

    @Test
    fun `Cancel clears nothing and the remote is back on the Clear history button`() {
        FormFactor.override(true)
        show()
        windowTakesKeys()
        press(Key.DirectionCenter)
        waitFor("Clear history?")
        windowTakesKeys()
        waitFocused("Cancel")
        press(Key.DirectionCenter, on = "Cancel")
        waitFor("Clear history?", present = false)
        windowTakesKeys()
        waitFocused("Clear history")
        assertEquals(emptyList<Int>(), cleared)
    }

    @Test
    fun `Clear clears once`() {
        FormFactor.override(true)
        show()
        windowTakesKeys()
        press(Key.DirectionCenter)
        waitFor("Clear history?")
        windowTakesKeys()
        waitFocused("Cancel")
        press(Key.DirectionRight, on = "Cancel")
        waitFocused("Clear")
        press(Key.DirectionCenter, on = "Clear")
        waitFor("Clear history?", present = false)
        assertEquals(listOf(3), cleared)
    }

    @Test
    fun `a second press while the question is open asks once`() {
        FormFactor.override(true)
        show()
        windowTakesKeys()
        press(Key.DirectionDown)
        waitFocused("Twice")
        press(Key.DirectionCenter)
        waitFor("Clear history?")
        assertEquals(1, asking())
        assertEquals(emptyList<Int>(), cleared)
    }

    @Test
    fun `an empty list asks nothing`() {
        FormFactor.override(true)
        cards.intValue = 0
        show()
        windowTakesKeys()
        press(Key.DirectionCenter)
        idle()
        assertEquals(0, asking())
        assertEquals(emptyList<Int>(), cleared)
    }

    @Test
    fun `a list emptied while the question was open closes it and clears nothing`() {
        FormFactor.override(true)
        show()
        windowTakesKeys()
        press(Key.DirectionCenter)
        waitFor("Clear history?")
        cards.intValue = 0
        waitFor("Clear history?", present = false)
        assertEquals(emptyList<Int>(), cleared)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `on a phone the question is Material's dialog and Clear clears once`() {
        FormFactor.override(false)
        show()
        rule.onNode(hasText("Clear history")).performClick()
        waitFor("Clear history?")
        rule.onNode(hasText("Cancel")).performClick()
        waitFor("Clear history?", present = false)
        assertEquals(emptyList<Int>(), cleared)
        rule.onNode(hasText("Clear history")).performClick()
        waitFor("Clear history?")
        rule.onNode(hasText("Clear")).performClick()
        waitFor("Clear history?", present = false)
        assertEquals(listOf(3), cleared)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `on a phone Twice asks once`() {
        FormFactor.override(false)
        show()
        rule.onNode(hasText("Twice")).performClick()
        waitFor("Clear history?")
        assertEquals(1, asking())
        assertEquals(emptyList<Int>(), cleared)
    }
}
