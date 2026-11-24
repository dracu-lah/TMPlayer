package com.tmplayer.desktop.os

import com.sun.jna.CallbackReference
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.win32.StdCallLibrary
import com.tmplayer.platform.Logger
import java.awt.Frame

/**
 * The Win32 side of [SnapLayouts]: window procedures put in front of the frame's window and the
 * windows inside it (`GWLP_WNDPROC` subclassing), and the pointer's place in client pixels. Only
 * Windows 11 calls this; the logic it carries lives in [SnapLayouts] and is tested with a fake.
 *
 * Subclassing under the JetBrains Runtime is the fragile part (WP-51 review): a handler that
 * throws is switched off for good and the original procedure answers from then on, so a bug here
 * costs the flyout, never the window.
 */
internal class SnapLayoutsWindows(private val frame: Frame) : WindowProcHooks {

    /** The frame's place on screen, for the pointer's client position and the leave notice. */
    val host: SnapHost = object : SnapHost {
        override fun toClient(screenX: Int, screenY: Int): Pair<Int, Int>? {
            val hwnd = NativeFullscreen.windowsHandle(frame) ?: return null
            val rect = WinDef.RECT()
            if (!User32.INSTANCE.GetWindowRect(hwnd, rect)) return null
            // An undecorated window has no frame around its client area, so the two share a corner.
            return (screenX - rect.left) to (screenY - rect.top)
        }

        override fun trackLeave() {
            runCatching {
                val hwnd = NativeFullscreen.windowsHandle(frame) ?: return
                val track = TrackMouseEvent().apply {
                    cbSize = size()
                    dwFlags = TME_LEAVE or TME_NONCLIENT
                    hwndTrack = hwnd.pointer
                }
                ext.TrackMouseEvent(track)
            }
        }
    }

    override fun install(top: WindowProc, child: WindowProc): AutoCloseable? {
        if (!OsInfo.isWindows || !frame.isDisplayable) return null
        val hwnd = NativeFullscreen.windowsHandle(frame) ?: return null
        val hooks = mutableListOf<Hook>()
        val first = Hook(hwnd, top)
        if (!first.attach()) return null
        hooks += first
        val children = mutableListOf<WinDef.HWND>()
        User32.INSTANCE.EnumChildWindows(hwnd, { h, _ ->
            children += h
            true
        }, null)
        for (kid in children) {
            val hook = Hook(kid, child)
            if (hook.attach()) hooks += hook
        }
        return AutoCloseable { hooks.asReversed().forEach { it.detach() } }
    }

    /** One window's procedure: [proc] first, the procedure that was there before for everything else. */
    private class Hook(private val hwnd: WinDef.HWND, private val proc: WindowProc) : WinUser.WindowProc {
        @Volatile private var old: Pointer? = null
        @Volatile private var broken = false

        fun attach(): Boolean {
            val previous = runCatching {
                User32.INSTANCE.SetWindowLongPtr(hwnd, GWLP_WNDPROC, CallbackReference.getFunctionPointer(this))
            }.onFailure { Logger.w(TAG, "could not subclass a window: ${it.message}") }.getOrNull()
            old = previous
            return previous != null
        }

        fun detach() {
            broken = true
            val previous = old ?: return
            runCatching {
                val current = User32.INSTANCE.GetWindowLongPtr(hwnd, GWLP_WNDPROC).toPointer()
                // Someone else subclassed after us: ours stays in the chain, passing everything on.
                if (current == CallbackReference.getFunctionPointer(this)) {
                    User32.INSTANCE.SetWindowLongPtr(hwnd, GWLP_WNDPROC, previous)
                }
            }
        }

        override fun callback(h: WinDef.HWND, msg: Int, wParam: WinDef.WPARAM, lParam: WinDef.LPARAM): WinDef.LRESULT {
            if (!broken) {
                try {
                    proc(msg, wParam.toLong(), lParam.toLong())?.let { return WinDef.LRESULT(it) }
                } catch (t: Throwable) {
                    broken = true
                    Logger.w(TAG, "a window message handler failed and is off: ${t.message}")
                }
            }
            val previous = old ?: return User32.INSTANCE.DefWindowProc(h, msg, wParam, lParam)
            return User32.INSTANCE.CallWindowProc(previous, h, msg, wParam, lParam)
        }
    }

    @Structure.FieldOrder("cbSize", "dwFlags", "hwndTrack", "dwHoverTime")
    class TrackMouseEvent : Structure() {
        @JvmField var cbSize: Int = 0
        @JvmField var dwFlags: Int = 0
        @JvmField var hwndTrack: Pointer? = null
        @JvmField var dwHoverTime: Int = 0
    }

    private interface User32Ext : StdCallLibrary {
        fun TrackMouseEvent(track: TrackMouseEvent): Boolean
    }

    private companion object {
        val ext: User32Ext by lazy { Native.load("user32", User32Ext::class.java) }
        const val TAG = "SnapLayouts"
        const val GWLP_WNDPROC = -4
        const val TME_LEAVE = 0x2
        const val TME_NONCLIENT = 0x10
    }
}
