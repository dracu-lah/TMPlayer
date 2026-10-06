package com.tmplayer.desktop.os

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.platform.unix.X11
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.tmplayer.platform.Logger
import java.awt.Frame
import java.awt.Rectangle
import java.awt.Toolkit

/**
 * Fullscreen done by asking the window system directly, for the top level window.
 *
 * Compose's `WindowPlacement.Fullscreen` hands the request to skiko, which addresses the drawing
 * surface it owns: a child of the frame. A window manager only acts on top level windows, so sway
 * (and other X11 window managers through XWayland) ignored it, and on Windows the frame kept its
 * caption. Here the request goes to the frame itself:
 *
 * - X11: a `_NET_WM_STATE` client message (add or remove `_NET_WM_STATE_FULLSCREEN`) sent to the
 *   root window for the frame's top level window, the way `wmctrl` does it.
 * - Windows: a maximized frame is restored first, then the caption and borders come off its style
 *   and it is sized to its monitor, which Explorer treats as fullscreen (the taskbar steps aside).
 *   Leaving puts the style back and maximizes again through a real transition, or puts the
 *   floating rectangle back exactly.
 *
 * [set] returns false when neither applies (macOS, a Wayland toolkit), and the caller falls back
 * to Compose's placement.
 */
class NativeFullscreen(private val window: Frame) {

    private var windowsSaved: WindowsSaved? = null
    private var x11On = false

    /** True when this platform is handled here, so [set] will do the work. */
    val supported: Boolean = when {
        OsInfo.isWindows -> true
        OsInfo.isLinux -> Toolkit.getDefaultToolkit().javaClass.name == "sun.awt.X11.XToolkit" // i18n-ok: a class name
        else -> false
    }

    fun set(on: Boolean): Boolean {
        if (!supported || !window.isDisplayable) return false
        return runCatching {
            if (OsInfo.isWindows) windows(on) else x11(on)
        }.onFailure { Logger.w(TAG, "native fullscreen failed: ${it.message}") }.getOrDefault(false)
    }

    private fun x11(on: Boolean): Boolean {
        // Nothing to undo at startup, when the shell first says "not fullscreen".
        if (on == x11On) return true
        val x = X11.INSTANCE
        val display = x.XOpenDisplay(null) ?: return false
        try {
            val root = x.XDefaultRootWindow(display)
            val top = x11TopLevel(x, display, X11.Window(Native.getWindowID(window)), root)
            val event = X11.XEvent()
            event.type = X11.ClientMessage
            event.setType(X11.XClientMessageEvent::class.java)
            event.xclient.type = X11.ClientMessage
            event.xclient.serial = NativeLong(0)
            event.xclient.send_event = 1
            event.xclient.display = display
            event.xclient.window = top
            event.xclient.message_type = x.XInternAtom(display, "_NET_WM_STATE", false)
            event.xclient.format = 32
            event.xclient.data.setType(Array<NativeLong>::class.java)
            event.xclient.data.l[0] = NativeLong(if (on) 1 else 0)
            event.xclient.data.l[1] = NativeLong(x.XInternAtom(display, "_NET_WM_STATE_FULLSCREEN", false).toLong())
            event.xclient.data.l[2] = NativeLong(0)
            // Source indication 1: a normal application.
            event.xclient.data.l[3] = NativeLong(1)
            val mask = NativeLong((X11.SubstructureRedirectMask or X11.SubstructureNotifyMask).toLong())
            x.XSendEvent(display, root, 0, mask, event)
            x.XFlush(display)
            x11On = on
            return true
        } finally {
            x.XCloseDisplay(display)
        }
    }

    /** Walks up from [start] to the window whose parent is [root]: the one the window manager manages. */
    private fun x11TopLevel(x: X11, display: X11.Display, start: X11.Window, root: X11.Window): X11.Window {
        var current = start
        repeat(MAX_DEPTH) {
            val rootOut = X11.WindowByReference()
            val parentOut = X11.WindowByReference()
            val children = PointerByReference()
            val count = IntByReference()
            if (x.XQueryTree(display, current, rootOut, parentOut, children, count) == 0) return current
            children.value?.let { if (it != Pointer.NULL) x.XFree(it) }
            val parent = parentOut.value ?: return current
            if (parent.toLong() == 0L || parent.toLong() == root.toLong()) return current
            current = parent
        }
        return current
    }

    /**
     * What the Windows branch changed on the way in, so the way out can put it back. [style] is
     * read after the window was restored, so it never carries `WS_MAXIMIZE`; [wasMaximized] says
     * whether the window was maximized before. [monitor] is the monitor the window went
     * fullscreen on, [screen] its whole rectangle (which the borderless window covers) and [work]
     * its work area at the time, both in screen coordinates. [rounded] is true when DWM took the
     * square corner request and the default has to be asked for again.
     */
    private class WindowsSaved(
        val style: Int,
        val exStyle: Int,
        val placement: WinUser.WINDOWPLACEMENT,
        val monitor: WinUser.HMONITOR,
        val screen: WinDef.RECT,
        val work: WinDef.RECT,
        val wasMaximized: Boolean,
        val rounded: Boolean,
    )

    /**
     * Set when something maximized the window while it was fullscreen (Compose writing
     * `placement = Maximized`, a stray `WM_SIZE`), so leaving lands maximized whatever the window
     * was before. mpv's `pending_maximize`, SDL's `windowed_mode_was_maximized`.
     */
    private var pendingMaximize = false

    init {
        if (OsInfo.isWindows) {
            window.addWindowStateListener { event ->
                val saved = windowsSaved ?: return@addWindowStateListener
                if (event.newState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH) {
                    pendingMaximize = true
                    // Maximized, the borderless frame is at best the work area; fullscreen is
                    // the whole monitor, so it goes back there until fullscreen ends.
                    runCatching {
                        val hwnd = windowsHandle(window) ?: return@runCatching
                        val r = saved.screen
                        User32.INSTANCE.SetWindowPos(
                            hwnd, null, r.left, r.top, r.right - r.left, r.bottom - r.top,
                            SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED,
                        )
                    }.onFailure { Logger.w(TAG, "could not hold the fullscreen size: ${it.message}") }
                } else if (event.newState == Frame.NORMAL) {
                    pendingMaximize = false
                }
            }
        }
    }

    /**
     * Windows. A maximized window is restored before its frame comes off and maximized again
     * after the frame goes back on, the step Chromium, mpv and SDL all take. Skipping it was the
     * taskbar bug: `SW_SHOWMAXIMIZED` on a window that still carries `WS_MAXIMIZE` moves and
     * sizes nothing, so the window kept the whole monitor with a caption on top and its bottom
     * under the taskbar, while Windows, AWT and Compose all said "maximized".
     */
    private fun windows(on: Boolean): Boolean {
        val user32 = User32.INSTANCE
        val hwnd = windowsHandle(window) ?: return false
        if (on) {
            if (windowsSaved != null) return true
            val placement = WinUser.WINDOWPLACEMENT()
            user32.GetWindowPlacement(hwnd, placement)
            val wasMaximized = user32.GetWindowLong(hwnd, WinUser.GWL_STYLE) and WinUser.WS_MAXIMIZE != 0
            val monitor = user32.MonitorFromWindow(hwnd, WinUser.MONITOR_DEFAULTTONEAREST)
            val info = WinUser.MONITORINFO()
            user32.GetMonitorInfo(monitor, info)
            if (wasMaximized) {
                // A real restore, so that the maximize on the way out is a real transition. AWT
                // sees SIZE_RESTORED and Compose reads Floating for the moment; WindowMemory is
                // frozen, so that in between state is never saved.
                val restore = copyOf(placement)
                restore.showCmd = WinUser.SW_SHOWNORMAL
                user32.SetWindowPlacement(hwnd, restore)
            }
            val style = user32.GetWindowLong(hwnd, WinUser.GWL_STYLE) and WinUser.WS_MAXIMIZE.inv()
            val exStyle = user32.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE)
            val saved = WindowsSaved(
                style = style,
                exStyle = exStyle,
                placement = placement,
                monitor = monitor,
                screen = copyOf(info.rcMonitor),
                work = copyOf(info.rcWork),
                wasMaximized = wasMaximized,
                rounded = setCorners(hwnd, DWMWCP_DONOTROUND),
            )
            windowsSaved = saved
            pendingMaximize = false
            // No caption, no sizing border and no maximize box, so neither Win+Up nor the title
            // bar shake can maximize the borderless window. The edge styles are Chromium's set.
            user32.SetWindowLong(
                hwnd, WinUser.GWL_STYLE,
                style and (WinUser.WS_CAPTION or WinUser.WS_THICKFRAME or WinUser.WS_MAXIMIZEBOX).inv(),
            )
            user32.SetWindowLong(
                hwnd, WinUser.GWL_EXSTYLE,
                exStyle and (WS_EX_DLGMODALFRAME or WS_EX_WINDOWEDGE or WS_EX_CLIENTEDGE or WS_EX_STATICEDGE).inv(),
            )
            // A frame with no thick frame that is maximized anyway covers the taskbar
            // (JDK-4737788). AWT answers WM_GETMINMAXINFO from this, so it is held to the work area.
            window.maximizedBounds = workArea()
            val r = saved.screen
            user32.SetWindowPos(
                hwnd, null, r.left, r.top, r.right - r.left, r.bottom - r.top,
                SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED,
            )
        } else {
            val saved = windowsSaved ?: return true
            val maximize = saved.wasMaximized || pendingMaximize
            windowsSaved = null
            pendingMaximize = false
            window.maximizedBounds = null
            // WS_MAXIMIZE stays off (a maximize during fullscreen may have set it): the placement
            // below has to find a window that is not maximized, or it is the same no op again.
            user32.SetWindowLong(
                hwnd, WinUser.GWL_STYLE,
                (saved.style and (WinUser.WS_MAXIMIZE or WinUser.WS_MINIMIZE).inv()) or WinUser.WS_VISIBLE,
            )
            user32.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, saved.exStyle)
            val placement = copyOf(saved.placement)
            placement.showCmd = if (maximize) WinUser.SW_SHOWMAXIMIZED else WinUser.SW_SHOWNORMAL
            // Moved to another monitor while fullscreen, or the taskbar moved: the restored
            // rectangle is fitted into the work area it now lands in (Chromium's AdjustToFit).
            val monitor = user32.MonitorFromWindow(hwnd, WinUser.MONITOR_DEFAULTTONEAREST)
            val info = WinUser.MONITORINFO()
            user32.GetMonitorInfo(monitor, info)
            if (monitor != saved.monitor || !sameRect(info.rcWork, saved.work)) {
                fitInto(placement.rcNormalPosition, toWorkspace(info.rcWork))
            }
            // In the maximized case this is the step that matters: Windows works the maximized
            // rectangle out again from the caption and thick frame now back on and from the
            // current work area, and the WM_SIZE it sends brings AWT's zoomed flag and Compose's
            // Maximized back without a Java call.
            user32.SetWindowPlacement(hwnd, placement)
            user32.SetWindowPos(
                hwnd, null, 0, 0, 0, 0,
                SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_NOOWNERZORDER or SWP_FRAMECHANGED,
            )
            if (saved.rounded) setCorners(hwnd, DWMWCP_DEFAULT)
        }
        return true
    }

    /** The work area of the monitor the window is on, in AWT's screen coordinates. */
    private fun workArea(): Rectangle? {
        val gc = window.graphicsConfiguration ?: return null
        val b = gc.bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(gc)
        return Rectangle(
            b.x + insets.left,
            b.y + insets.top,
            b.width - insets.left - insets.right,
            b.height - insets.top - insets.bottom,
        )
    }

    /** Asks Windows 11 for a corner style; true when DWM took it (Windows 10 does not know it). */
    private fun setCorners(hwnd: WinDef.HWND, preference: Int): Boolean {
        val api = dwm ?: return false
        return runCatching {
            api.DwmSetWindowAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, IntByReference(preference), 4) == 0
        }.getOrDefault(false)
    }

    private interface Dwm : Library {
        fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attribute: Int, value: IntByReference, size: Int): Int
    }

    companion object {
        private const val TAG = "NativeFullscreen"
        private const val MAX_DEPTH = 16
        internal const val SWP_NOSIZE = 0x0001
        internal const val SWP_NOMOVE = 0x0002
        internal const val SWP_NOZORDER = 0x0004
        internal const val SWP_NOACTIVATE = 0x0010
        internal const val SWP_FRAMECHANGED = 0x0020
        internal const val SWP_NOOWNERZORDER = 0x0200
        private const val GA_ROOT = 2

        // Not in JNA's WinUser.
        private const val WS_EX_DLGMODALFRAME = 0x00000001
        private const val WS_EX_WINDOWEDGE = 0x00000100
        private const val WS_EX_CLIENTEDGE = 0x00000200
        private const val WS_EX_STATICEDGE = 0x00020000
        private const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
        private const val DWMWCP_DEFAULT = 0
        private const val DWMWCP_DONOTROUND = 1

        private val dwm: Dwm? by lazy {
            runCatching { Native.load("dwmapi", Dwm::class.java) }
                .onFailure { Logger.w(TAG, "dwmapi not available: ${it.message}") }
                .getOrNull()
        }

        private fun copyOf(r: WinDef.RECT): WinDef.RECT = WinDef.RECT().also {
            it.left = r.left
            it.top = r.top
            it.right = r.right
            it.bottom = r.bottom
        }

        /** A detached copy: the constructor sets `length`, the rest is copied field by field. */
        private fun copyOf(p: WinUser.WINDOWPLACEMENT): WinUser.WINDOWPLACEMENT = WinUser.WINDOWPLACEMENT().also {
            it.flags = p.flags
            it.showCmd = p.showCmd
            it.ptMinPosition.x = p.ptMinPosition.x
            it.ptMinPosition.y = p.ptMinPosition.y
            it.ptMaxPosition.x = p.ptMaxPosition.x
            it.ptMaxPosition.y = p.ptMaxPosition.y
            it.rcNormalPosition.left = p.rcNormalPosition.left
            it.rcNormalPosition.top = p.rcNormalPosition.top
            it.rcNormalPosition.right = p.rcNormalPosition.right
            it.rcNormalPosition.bottom = p.rcNormalPosition.bottom
        }

        private fun sameRect(a: WinDef.RECT, b: WinDef.RECT): Boolean =
            a.left == b.left && a.top == b.top && a.right == b.right && a.bottom == b.bottom

        /**
         * [screen] (screen coordinates) in workspace coordinates, which is what
         * `WINDOWPLACEMENT.rcNormalPosition` uses: they are offset by the primary monitor's
         * taskbar when it sits at the top or on the left.
         */
        private fun toWorkspace(screen: WinDef.RECT): WinDef.RECT {
            val user32 = User32.INSTANCE
            val primary = WinUser.MONITORINFO()
            val origin = WinDef.POINT.ByValue(0, 0)
            user32.GetMonitorInfo(user32.MonitorFromPoint(origin, WinUser.MONITOR_DEFAULTTOPRIMARY), primary)
            val dx = primary.rcWork.left - primary.rcMonitor.left
            val dy = primary.rcWork.top - primary.rcMonitor.top
            return copyOf(screen).also {
                it.left -= dx
                it.right -= dx
                it.top -= dy
                it.bottom -= dy
            }
        }

        /** Shrinks [r] to fit [area] if it is larger, then moves it inside. Both in one coordinate space. */
        internal fun fitInto(r: WinDef.RECT, area: WinDef.RECT) {
            val width = minOf(r.right - r.left, area.right - area.left)
            val height = minOf(r.bottom - r.top, area.bottom - area.top)
            val left = r.left.coerceIn(area.left, area.right - width)
            val top = r.top.coerceIn(area.top, area.bottom - height)
            r.left = left
            r.top = top
            r.right = left + width
            r.bottom = top + height
        }

        /** The frame's top level HWND (the drawing surface inside it is a child). */
        internal fun windowsHandle(window: Frame): WinDef.HWND? {
            val pointer = Native.getComponentPointer(window) ?: return null
            val hwnd = WinDef.HWND(pointer)
            return User32.INSTANCE.GetAncestor(hwnd, GA_ROOT) ?: hwnd
        }
    }
}
