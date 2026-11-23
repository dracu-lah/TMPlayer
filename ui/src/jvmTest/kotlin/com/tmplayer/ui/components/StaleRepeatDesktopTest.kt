package com.tmplayer.ui.components

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import org.junit.Assert.assertFalse
import org.junit.Test

/** The desktop has no held remote on a slow stick: no repeat of an arrow key is ever dropped. */
@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
class StaleRepeatDesktopTest {

    private fun down(key: Key) = KeyEvent(key, KeyEventType.KeyDown)

    @Test
    fun `the desktop actual never calls a repeat stale`() {
        assertFalse(platformStaleRepeat(down(Key.DirectionDown)))
        assertFalse(platformStaleRepeat(down(Key.Enter)))
    }

    @Test
    fun `no arrow press is dropped on the desktop`() {
        for (key in listOf(Key.DirectionDown, Key.DirectionUp, Key.DirectionLeft, Key.DirectionRight)) {
            assertFalse(staleArrowKeyRepeat(down(key)))
        }
    }
}
