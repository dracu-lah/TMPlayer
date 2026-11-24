package com.tmplayer.desktop.os

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP-51: the Snap Layouts hit test and its install, on any OS, with a fake in place of Win32. */
class SnapLayoutsTest {

    private class FakeHost(var origin: Pair<Int, Int> = 0 to 0) : SnapHost {
        var tracked = 0
        override fun toClient(screenX: Int, screenY: Int) = (screenX - origin.first) to (screenY - origin.second)
        override fun trackLeave() {
            tracked++
        }
    }

    private val button = PixelRect(1000, 0, 1060, 48)
    private val host = FakeHost()
    private var toggled = 0
    private val hover = mutableListOf<Boolean>()
    private var bounds: PixelRect? = button

    private val logic = SnapLayouts(host, { bounds }, { toggled++ }, { hover += it })

    private fun point(x: Int, y: Int): Long = ((y and 0xFFFF).toLong() shl 16) or (x and 0xFFFF).toLong()

    @Test
    fun `over the maximize button the frame says HTMAXBUTTON and the picture is transparent`() {
        assertEquals(SnapLayouts.HTMAXBUTTON, logic.onTopLevel(SnapLayouts.WM_NCHITTEST, 0, point(1010, 20)))
        assertEquals(SnapLayouts.HTTRANSPARENT, logic.onChild(SnapLayouts.WM_NCHITTEST, 0, point(1010, 20)))
    }

    @Test
    fun `anywhere else the message goes on`() {
        assertNull(logic.onTopLevel(SnapLayouts.WM_NCHITTEST, 0, point(999, 20)))
        assertNull(logic.onTopLevel(SnapLayouts.WM_NCHITTEST, 0, point(1010, 48)))
        assertNull(logic.onChild(SnapLayouts.WM_NCHITTEST, 0, point(10, 10)))
        assertNull(logic.onTopLevel(0x0200, 0, point(1010, 20)))
    }

    @Test
    fun `no button drawn means no hit`() {
        bounds = null
        assertNull(logic.onTopLevel(SnapLayouts.WM_NCHITTEST, 0, point(1010, 20)))
    }

    @Test
    fun `a window on a second monitor to the left still hits, with negative screen points`() {
        host.origin = -1920 to 40
        // The window sits at x = -1920, y = 40: the button's screen point is (-920, 60).
        assertEquals(SnapLayouts.HTMAXBUTTON, logic.onTopLevel(SnapLayouts.WM_NCHITTEST, 0, point(-920, 60)))
        assertEquals(-920 to 60, SnapLayouts.screenPoint(point(-920, 60)))
    }

    @Test
    fun `a press is swallowed and the release maximizes, only over the button`() {
        assertEquals(0L, logic.onTopLevel(SnapLayouts.WM_NCLBUTTONDOWN, SnapLayouts.HTMAXBUTTON, 0))
        assertEquals(0, toggled)
        assertEquals(0L, logic.onTopLevel(SnapLayouts.WM_NCLBUTTONUP, SnapLayouts.HTMAXBUTTON, 0))
        assertEquals(1, toggled)
        // Another part of the window's own presses is not ours.
        assertNull(logic.onTopLevel(SnapLayouts.WM_NCLBUTTONUP, 2, 0))
        assertEquals(1, toggled)
    }

    @Test
    fun `hover follows the pointer in and out and asks Windows for the leave`() {
        logic.onTopLevel(SnapLayouts.WM_NCMOUSEMOVE, SnapLayouts.HTMAXBUTTON, 0)
        logic.onTopLevel(SnapLayouts.WM_NCMOUSEMOVE, 2, 0)
        logic.onTopLevel(SnapLayouts.WM_NCMOUSEMOVE, SnapLayouts.HTMAXBUTTON, 0)
        logic.onTopLevel(SnapLayouts.WM_NCMOUSELEAVE, 0, 0)
        assertEquals(listOf(true, false, true, false), hover)
        assertEquals(2, host.tracked)
    }

    @Test
    fun `only Windows 11 installs`() {
        assertTrue(SnapLayouts.supported("Windows 11"))
        assertFalse(SnapLayouts.supported("Windows 10"))
        assertFalse(SnapLayouts.supported("Linux"))
        assertFalse(SnapLayouts.supported("Mac OS X"))
    }

    private class FakeHooks(val answer: () -> AutoCloseable?) : WindowProcHooks {
        var installs = 0
        override fun install(top: WindowProc, child: WindowProc): AutoCloseable? {
            installs++
            return answer()
        }
    }

    @Test
    fun `on Windows 10 or another OS nothing is installed and nothing is asked`() {
        val hooks = FakeHooks { AutoCloseable { } }
        val result = SnapLayouts.install(logic, hooks, osName = "Windows 10")
        assertEquals(SnapLayouts.Outcome.NotWindows11, result.outcome)
        assertEquals(0, hooks.installs)
    }

    @Test
    fun `a hook that fails or throws leaves the window as it was`() {
        assertEquals(SnapLayouts.Outcome.Failed, SnapLayouts.install(logic, FakeHooks { null }, "Windows 11").outcome)
        val thrower = FakeHooks { error("subclassing refused") }
        assertEquals(SnapLayouts.Outcome.Failed, SnapLayouts.install(logic, thrower, "Windows 11").outcome)
    }

    @Test
    fun `a hook that installs is undone on close`() {
        var closed = 0
        val result = SnapLayouts.install(logic, FakeHooks { AutoCloseable { closed++ } }, "Windows 11")
        assertEquals(SnapLayouts.Outcome.Installed, result.outcome)
        result.close()
        assertEquals(1, closed)
    }
}
