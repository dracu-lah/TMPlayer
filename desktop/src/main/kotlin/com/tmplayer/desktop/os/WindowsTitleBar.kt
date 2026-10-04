package com.tmplayer.desktop.os

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import com.tmplayer.platform.Logger
import java.awt.Frame

/**
 * Windows draws the caption itself, white unless asked otherwise, which put a bright bar over the
 * dark app. This asks DWM for the immersive dark caption (Windows 10 1809 and later), and on
 * Windows 11 also paints the caption and its text in the app's own colours, so the title bar reads
 * as part of the window. Each call is ignored by a Windows that does not know it.
 */
object WindowsTitleBar {

    private interface Dwm : Library {
        fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attribute: Int, value: IntByReference, size: Int): Int
    }

    private val dwm: Dwm? by lazy {
        runCatching { Native.load("dwmapi", Dwm::class.java) }
            .onFailure { Logger.w(TAG, "dwmapi not available: ${it.message}") }
            .getOrNull()
    }

    /**
     * Themes [window]'s caption. [caption] and [text] are 0xRRGGBB. Does nothing off Windows or
     * before the window has a handle.
     */
    fun apply(window: Frame, dark: Boolean, caption: Int, text: Int) {
        if (!OsInfo.isWindows || !window.isDisplayable) return
        val api = dwm ?: return
        runCatching {
            val hwnd = NativeFullscreen.windowsHandle(window) ?: return
            val on = IntByReference(if (dark) 1 else 0)
            // 20 on Windows 10 20H1 and later, 19 before it.
            if (api.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, on, 4) != 0) {
                api.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_OLD, on, 4)
            }
            api.DwmSetWindowAttribute(hwnd, DWMWA_CAPTION_COLOR, IntByReference(colorRef(caption)), 4)
            api.DwmSetWindowAttribute(hwnd, DWMWA_TEXT_COLOR, IntByReference(colorRef(text)), 4)
            // The caption repaints on the next frame change; ask for one now.
            User32.INSTANCE.SetWindowPos(
                hwnd, null, 0, 0, 0, 0,
                NativeFullscreen.SWP_NOMOVE or NativeFullscreen.SWP_NOSIZE or NativeFullscreen.SWP_NOZORDER or
                    NativeFullscreen.SWP_NOACTIVATE or NativeFullscreen.SWP_FRAMECHANGED,
            )
        }.onFailure { Logger.w(TAG, "could not theme the title bar: ${it.message}") }
    }

    /** 0xRRGGBB to a Win32 COLORREF, which is 0x00BBGGRR. */
    internal fun colorRef(rgb: Int): Int {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return (b shl 16) or (g shl 8) or r
    }

    private const val TAG = "WindowsTitleBar"
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE_OLD = 19
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_CAPTION_COLOR = 35
    private const val DWMWA_TEXT_COLOR = 36
}
