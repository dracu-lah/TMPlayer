package com.tmplayer.desktop.os

import com.tmplayer.platform.Logger
import java.awt.AWTEvent
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent

/**
 * Sideways scrolling on Linux, and the mouse's real Back and Forward buttons there.
 *
 * X11 sends a touchpad's two finger sideways swipe, and a mouse's tilt wheel, as presses of
 * buttons 6 and 7. The JDK's X11 toolkit has no sideways wheel event, so it hands those on as
 * ordinary presses of Java's buttons 4 and 5 (`XWindow.handleButtonPressRelease`: every button
 * past the wheel's 4 and 5 is renumbered two down), which are exactly the numbers Compose reads
 * as [androidx.compose.ui.input.pointer.PointerButton.Back] and Forward. So on Linux a sideways
 * swipe went back a page, a burst of them went back through every screen, and nothing that
 * scrolls sideways (the Home carousel, its rows) ever saw a scroll at all. A mouse's own side
 * buttons, X11's 8 and 9, arrive as Java's 6 and 7, which Compose does not call Back.
 *
 * [install] puts a queue in front of AWT's that turns each of those presses into the sideways
 * wheel step it is: a wheel event with Shift held, which is how Compose Desktop (and the JDK on
 * macOS, for a trackpad) spells a horizontal scroll. Their releases and clicks are dropped. The
 * side buttons are renumbered to 4 and 5, so Back and Forward on the mouse work as on Windows.
 * Nothing changes on Windows or macOS, or on a toolkit other than X11's.
 */
object X11SideScroll {

    fun install() {
        if (!OsInfo.isLinux) return
        val toolkit = Toolkit.getDefaultToolkit()
        if (toolkit.javaClass.name != X11_TOOLKIT) return
        runCatching { toolkit.systemEventQueue.push(Queue()) }
            .onSuccess { Logger.i(TAG, "sideways scroll from buttons 6 and 7") }
            .onFailure { Logger.w(TAG, "could not take sideways scroll: ${it.message}") }
    }

    private class Queue : EventQueue() {
        override fun dispatchEvent(event: AWTEvent) {
            // A constructor that refuses a button number leaves the event as it came.
            val translated = runCatching { translate(event) }.getOrElse { event } ?: return
            super.dispatchEvent(translated)
        }
    }

    /** What [event] should be once the X11 numbering is undone; null to drop it. */
    internal fun translate(event: AWTEvent): AWTEvent? {
        if (event !is MouseEvent || event is MouseWheelEvent) return event
        if (event.button == SCROLL_LEFT || event.button == SCROLL_RIGHT) {
            return if (event.id == MouseEvent.MOUSE_PRESSED) sideways(event) else null
        }
        val side = sideButton(event.button) ?: return event
        return renumbered(event, side)
    }

    /** One step of the sideways wheel: left is a negative turn, as up is on the vertical wheel. */
    private fun sideways(event: MouseEvent): MouseWheelEvent {
        val turn = if (event.button == SCROLL_LEFT) -1 else 1
        val modifiers = (event.modifiersEx and BUTTON_MASKS.inv()) or InputEvent.SHIFT_DOWN_MASK
        return MouseWheelEvent(
            event.component, MouseEvent.MOUSE_WHEEL, event.`when`, modifiers,
            event.x, event.y, event.xOnScreen, event.yOnScreen,
            0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, WHEEL_AMOUNT, turn, turn.toDouble(),
        )
    }

    /** Java's Back (4) or Forward (5) for the number X11's side button arrives under; null for any other. */
    internal fun sideButton(button: Int): Int? = when (button) {
        SIDE_BACK -> BACK
        SIDE_FORWARD -> FORWARD
        else -> null
    }

    private fun renumbered(event: MouseEvent, button: Int): MouseEvent {
        var modifiers = event.modifiersEx
        val from = InputEvent.getMaskForButton(event.button)
        if (modifiers and from != 0) modifiers = (modifiers and from.inv()) or InputEvent.getMaskForButton(button)
        return MouseEvent(
            event.component, event.id, event.`when`, modifiers,
            event.x, event.y, event.xOnScreen, event.yOnScreen,
            event.clickCount, event.isPopupTrigger, button,
        )
    }

    private const val X11_TOOLKIT = "sun.awt.X11.XToolkit"
    private const val TAG = "X11SideScroll"

    /** Java's numbers for X11's buttons 6 and 7 (sideways scroll) and 8 and 9 (the side buttons). */
    internal const val SCROLL_LEFT = 4
    internal const val SCROLL_RIGHT = 5
    internal const val SIDE_BACK = 6
    internal const val SIDE_FORWARD = 7
    private const val BACK = 4
    private const val FORWARD = 5

    /** Lines per step, what the JDK gives its own vertical wheel steps on X11. */
    private const val WHEEL_AMOUNT = 3

    private val BUTTON_MASKS = InputEvent.getMaskForButton(SCROLL_LEFT) or InputEvent.getMaskForButton(SCROLL_RIGHT)
}
