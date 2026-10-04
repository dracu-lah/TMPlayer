package com.tmplayer.desktop.os

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
 * - Windows: the caption and borders come off the frame's style and it is sized to its monitor,
 *   which Explorer treats as fullscreen (the taskbar steps aside). Leaving puts the style and the
 *   placement back exactly.
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
        OsInfo.isLinux -> Toolkit.getDefaultToolkit().javaClass.name == "sun.awt.X11.XToolkit"
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

    private data class WindowsSaved(val style: Int, val placement: WinUser.WINDOWPLACEMENT)

    private fun windows(on: Boolean): Boolean {
        val user32 = User32.INSTANCE
        val hwnd = windowsHandle(window) ?: return false
        if (on) {
            if (windowsSaved != null) return true
            val placement = WinUser.WINDOWPLACEMENT()
            user32.GetWindowPlacement(hwnd, placement)
            val style = user32.GetWindowLong(hwnd, WinUser.GWL_STYLE)
            val info = WinUser.MONITORINFO()
            user32.GetMonitorInfo(user32.MonitorFromWindow(hwnd, WinUser.MONITOR_DEFAULTTONEAREST), info)
            windowsSaved = WindowsSaved(style, placement)
            user32.SetWindowLong(hwnd, WinUser.GWL_STYLE, style and WinUser.WS_OVERLAPPEDWINDOW.inv())
            val r = info.rcMonitor
            user32.SetWindowPos(hwnd, null, r.left, r.top, r.right - r.left, r.bottom - r.top, SWP_NOOWNERZORDER or SWP_FRAMECHANGED)
        } else {
            val saved = windowsSaved ?: return true
            windowsSaved = null
            user32.SetWindowLong(hwnd, WinUser.GWL_STYLE, saved.style)
            user32.SetWindowPlacement(hwnd, saved.placement)
            user32.SetWindowPos(
                hwnd, null, 0, 0, 0, 0,
                SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_NOOWNERZORDER or SWP_FRAMECHANGED,
            )
        }
        return true
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

        /** The frame's top level HWND (the drawing surface inside it is a child). */
        internal fun windowsHandle(window: Frame): WinDef.HWND? {
            val pointer = Native.getComponentPointer(window) ?: return null
            val hwnd = WinDef.HWND(pointer)
            return User32.INSTANCE.GetAncestor(hwnd, GA_ROOT) ?: hwnd
        }
    }
}
