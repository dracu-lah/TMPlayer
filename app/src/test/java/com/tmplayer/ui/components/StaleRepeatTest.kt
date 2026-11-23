package com.tmplayer.ui.components

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.requestFocus
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

    private fun key(code: Int, repeat: Int, ageMs: Long): KeyEvent {
        val at = SystemClock.uptimeMillis() - ageMs
        return KeyEvent(android.view.KeyEvent(at, at, android.view.KeyEvent.ACTION_DOWN, code, repeat))
    }

    @Test
    fun staleRepeatsOfAnArrowAreDroppedAndTheRestPass() {
        var received = 0
        rule.setContent {
            Box(Modifier.dropStaleArrowRepeats()) {
                Box(Modifier.testTag("item").focusable().onKeyEvent { received++; true })
            }
        }
        rule.onNodeWithTag("item").requestFocus()
        val node = rule.onNodeWithTag("item")
        // A first press and a fresh repeat both move.
        node.performKeyPress(key(android.view.KeyEvent.KEYCODE_DPAD_DOWN, repeat = 0, ageMs = 0))
        node.performKeyPress(key(android.view.KeyEvent.KEYCODE_DPAD_DOWN, repeat = 1, ageMs = 10))
        assertEquals(2, received)
        // A repeat that waited 500 ms is dropped, in any arrow direction.
        node.performKeyPress(key(android.view.KeyEvent.KEYCODE_DPAD_DOWN, repeat = 3, ageMs = 500))
        node.performKeyPress(key(android.view.KeyEvent.KEYCODE_DPAD_UP, repeat = 3, ageMs = 500))
        assertEquals(2, received)
        // An old first press (no repeat) and another key are never touched.
        node.performKeyPress(key(android.view.KeyEvent.KEYCODE_DPAD_DOWN, repeat = 0, ageMs = 500))
        node.performKeyPress(key(android.view.KeyEvent.KEYCODE_ENTER, repeat = 3, ageMs = 500))
        assertEquals(4, received)
    }
}
