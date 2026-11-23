package com.tmplayer.ui.components

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.tmplayer.ui.DrainMainLooperRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A held arrow key's stale repeats are dropped before they reach the grid's items (the lists that
 * kept moving after the button was let go on a slow TV stick), and nothing else is.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class StaleRepeatTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val drain = DrainMainLooperRule()

    /** Delivered the way the system does, through the window, to whatever holds the focus. */
    private fun press(code: Int, repeat: Int, ageMs: Long) {
        val at = SystemClock.uptimeMillis() - ageMs
        rule.runOnUiThread {
            rule.activity.window.decorView.dispatchKeyEvent(android.view.KeyEvent(at, at, android.view.KeyEvent.ACTION_DOWN, code, repeat))
        }
    }

    @Test
    fun staleRepeatsOfAnArrowAreDroppedAndTheRestPass() {
        var received = 0
        rule.setContent {
            Box(Modifier.dropStaleArrowRepeats()) {
                Box(Modifier.size(20.dp).testTag("item").onKeyEvent { if (it.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) received++; true }.focusable())
            }
        }
        rule.onNodeWithTag("item").requestFocus()
        rule.onNodeWithTag("item").assertIsFocused()
        // Sanity: the harness's own press reaches the item through the modifier.
        rule.onNodeWithTag("item").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionDown) }
        assertEquals(1, received)
        received = 0
        // A first press and a fresh repeat both move.
        press(android.view.KeyEvent.KEYCODE_DPAD_DOWN, repeat = 0, ageMs = 0)
        press(android.view.KeyEvent.KEYCODE_DPAD_DOWN, repeat = 1, ageMs = 10)
        assertEquals(2, received)
        // A repeat that waited 500 ms is dropped, in any arrow direction.
        press(android.view.KeyEvent.KEYCODE_DPAD_DOWN, repeat = 3, ageMs = 500)
        press(android.view.KeyEvent.KEYCODE_DPAD_UP, repeat = 3, ageMs = 500)
        assertEquals(2, received)
        // An old first press (no repeat) and another key are never touched.
        press(android.view.KeyEvent.KEYCODE_DPAD_DOWN, repeat = 0, ageMs = 500)
        press(android.view.KeyEvent.KEYCODE_ENTER, repeat = 3, ageMs = 500)
        assertEquals(4, received)
    }

    /** The shared modifier in :ui (what the shared lists use) drops the same repeats as :app's forwarder. */
    @Test
    fun theSharedModifierDropsTheSameRepeatsAsTheForwarder() {
        var received = 0
        rule.setContent {
            Box(Modifier.dropStaleRepeats()) {
                Box(Modifier.size(20.dp).testTag("item").onKeyEvent { if (it.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) received++; true }.focusable())
            }
        }
        rule.onNodeWithTag("item").requestFocus()
        press(android.view.KeyEvent.KEYCODE_DPAD_DOWN, repeat = 0, ageMs = 0)
        press(android.view.KeyEvent.KEYCODE_DPAD_RIGHT, repeat = 1, ageMs = 10)
        assertEquals(2, received)
        press(android.view.KeyEvent.KEYCODE_DPAD_DOWN, repeat = 4, ageMs = 500)
        press(android.view.KeyEvent.KEYCODE_DPAD_LEFT, repeat = 4, ageMs = 500)
        assertEquals(2, received)
        press(android.view.KeyEvent.KEYCODE_DPAD_CENTER, repeat = 4, ageMs = 500)
        assertEquals(3, received)
    }
}
