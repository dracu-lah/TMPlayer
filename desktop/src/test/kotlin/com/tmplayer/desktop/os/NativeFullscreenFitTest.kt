package com.tmplayer.desktop.os

import com.sun.jna.platform.win32.WinDef
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one pure step of the Windows fullscreen round trip: fitting the restored rectangle into the
 * work area it comes back to when the window changed monitor, or the taskbar moved, while it was
 * fullscreen. The rest is native calls, checked on the Windows CI runner instead.
 */
class NativeFullscreenFitTest {

    private fun rect(left: Int, top: Int, right: Int, bottom: Int) = WinDef.RECT().also {
        it.left = left
        it.top = top
        it.right = right
        it.bottom = bottom
    }

    private fun WinDef.RECT.asList() = listOf(left, top, right, bottom)

    @Test
    fun `a rectangle already inside stays where it is`() {
        val r = rect(100, 100, 900, 700)
        NativeFullscreen.fitInto(r, rect(0, 0, 1920, 1032))
        assertEquals(listOf(100, 100, 900, 700), r.asList())
    }

    @Test
    fun `a rectangle hanging off the work area moves back inside, same size`() {
        val r = rect(1500, 800, 2300, 1400)
        NativeFullscreen.fitInto(r, rect(0, 0, 1920, 1032))
        assertEquals(listOf(1120, 432, 1920, 1032), r.asList())
    }

    @Test
    fun `a rectangle larger than a smaller monitor shrinks to it`() {
        val r = rect(0, 0, 2400, 1300)
        NativeFullscreen.fitInto(r, rect(1920, 0, 3200, 984))
        assertEquals(listOf(1920, 0, 3200, 984), r.asList())
    }

    @Test
    fun `a work area to the left of the primary, at negative coordinates, is honoured`() {
        val r = rect(200, 50, 1000, 650)
        NativeFullscreen.fitInto(r, rect(-1280, 0, 0, 984))
        assertEquals(listOf(-800, 50, 0, 650), r.asList())
    }
}
