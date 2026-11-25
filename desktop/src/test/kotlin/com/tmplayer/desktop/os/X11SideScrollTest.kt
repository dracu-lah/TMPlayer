package com.tmplayer.desktop.os

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Component
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent

/**
 * HX63, the gesture to action mapping on Linux (X11): a touchpad's two finger sideways swipe
 * arrives as buttons 6 and 7, which the JDK numbers 4 and 5 (Java's Back and Forward). The most
 * conservative reading is kept: a swipe is a sideways scroll (so the Home carousel and the rows
 * move, one page per gesture, see SwipeStepsTest), never Back; Back and Forward are the mouse's
 * side buttons only, renumbered to 4 and 5.
 */
class X11SideScrollTest {

    private val source = object : Component() {}

    /**
     * AWT's own constructor refuses a button above the third when the machine has no such button or
     * no display (a Windows or macOS runner, a headless Linux one), so the extra buttons of X11 are
     * told through the getter, as the toolkit's real events would give them.
     */
    private fun mouse(id: Int, button: Int, modifiers: Int = 0, clicks: Int = 1): MouseEvent =
        if (button <= MouseEvent.BUTTON3) {
            MouseEvent(source, id, 1_000L, modifiers, 10, 20, 110, 120, clicks, false, button)
        } else {
            object : MouseEvent(source, id, 1_000L, modifiers, 10, 20, 110, 120, clicks, false, NOBUTTON) {
                override fun getButton() = button
            }
        }

    @Test
    fun `a swipe to the left is a negative sideways wheel step with shift`() {
        val out = X11SideScroll.translate(mouse(MouseEvent.MOUSE_PRESSED, X11SideScroll.SCROLL_LEFT))
        assertTrue(out is MouseWheelEvent)
        out as MouseWheelEvent
        assertEquals(-1, out.wheelRotation)
        assertTrue("shift marks it sideways", out.modifiersEx and InputEvent.SHIFT_DOWN_MASK != 0)
    }

    @Test
    fun `a swipe to the right is a positive sideways wheel step with shift`() {
        val out = X11SideScroll.translate(mouse(MouseEvent.MOUSE_PRESSED, X11SideScroll.SCROLL_RIGHT)) as MouseWheelEvent
        assertEquals(1, out.wheelRotation)
        assertTrue(out.modifiersEx and InputEvent.SHIFT_DOWN_MASK != 0)
    }

    @Test
    fun `the release and the click of a swipe are dropped so it is never Back`() {
        for (button in listOf(X11SideScroll.SCROLL_LEFT, X11SideScroll.SCROLL_RIGHT)) {
            assertNull(X11SideScroll.translate(mouse(MouseEvent.MOUSE_RELEASED, button)))
            assertNull(X11SideScroll.translate(mouse(MouseEvent.MOUSE_CLICKED, button)))
        }
    }

    @Test
    fun `the mouse side buttons become Back and Forward`() {
        // The renumbering itself; the event it builds needs a mouse with five buttons, which only
        // the machine's own toolkit can promise, so the number is checked apart from the event.
        assertEquals(4, X11SideScroll.sideButton(X11SideScroll.SIDE_BACK))
        assertEquals(5, X11SideScroll.sideButton(X11SideScroll.SIDE_FORWARD))
        assertNull(X11SideScroll.sideButton(MouseEvent.BUTTON1))
        assertNull(X11SideScroll.sideButton(X11SideScroll.SCROLL_LEFT))
    }

    @Test
    fun `ordinary buttons and the real wheel are left as they are`() {
        val left = mouse(MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON1)
        assertSame(left, X11SideScroll.translate(left))
        val wheel = MouseWheelEvent(source, MouseEvent.MOUSE_WHEEL, 1L, 0, 1, 1, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 1)
        assertSame(wheel, X11SideScroll.translate(wheel))
    }
}
