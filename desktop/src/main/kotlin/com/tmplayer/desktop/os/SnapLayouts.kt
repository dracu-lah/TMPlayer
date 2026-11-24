package com.tmplayer.desktop.os

import com.tmplayer.platform.Logger

/**
 * Windows 11's Snap Layouts flyout, which opens when the pointer rests on a window's maximize
 * button. Windows only shows it for a button it believes is the caption's maximize button, so a
 * window that draws its own buttons (see [WindowChromeMode.Custom]) has to answer `WM_NCHITTEST`
 * over its button with `HTMAXBUTTON`. The decision of WP-51 is: try this hit test first, and
 * where it cannot be installed leave the window as it was (the buttons still work as clicks; the
 * system title bar is one setting, or `TMPLAYER_SYSTEM_TITLEBAR=1`, away).
 *
 * Everything here that is not Win32 is plain logic, tested on any OS: [SnapLayouts] reads the
 * messages, [WindowProcHooks] is the one thing that touches the operating system, and a fake
 * stands in for it in the tests.
 */
class SnapLayouts(
    private val host: SnapHost,
    /** The maximize button in the window's own pixels (client coordinates), or null while none is drawn. */
    private val maximizeButton: () -> PixelRect?,
    /** Run when the button is clicked, which Windows delivers as a non client message. */
    private val onToggleMaximize: () -> Unit,
    /** Whether the pointer is on the button, for the drawn hover state, which Compose never sees. */
    private val onHover: (Boolean) -> Unit,
) {
    /** A message on the frame's own window. Null passes the message on. */
    fun onTopLevel(msg: Int, wParam: Long, lParam: Long): Long? {
        val onButton = wParam.toInt().toLong() == HTMAXBUTTON
        when (msg) {
            WM_NCHITTEST -> return if (overButton(lParam)) HTMAXBUTTON else null
            WM_NCMOUSEMOVE -> {
                onHover(onButton)
                if (onButton) host.trackLeave()
            }
            WM_NCMOUSELEAVE, WM_MOUSELEAVE -> onHover(false)
            // The press is swallowed so Windows does not maximize on its own; the release acts.
            WM_NCLBUTTONDOWN -> if (onButton) return 0L
            WM_NCLBUTTONUP -> if (onButton) {
                onToggleMaximize()
                return 0L
            }
        }
        return null
    }

    /**
     * A message on the window the picture is drawn in (a child of the frame's window). It is
     * transparent to the hit test over the button, so the frame's window is asked instead.
     */
    fun onChild(msg: Int, @Suppress("UNUSED_PARAMETER") wParam: Long, lParam: Long): Long? =
        if (msg == WM_NCHITTEST && overButton(lParam)) HTTRANSPARENT else null

    private fun overButton(lParam: Long): Boolean {
        val rect = maximizeButton() ?: return false
        val (sx, sy) = screenPoint(lParam)
        val (x, y) = host.toClient(sx, sy) ?: return false
        return rect.contains(x, y)
    }

    enum class Outcome { Installed, NotWindows11, Failed }

    class Installed(val outcome: Outcome, private val handle: AutoCloseable?) : AutoCloseable {
        override fun close() {
            runCatching { handle?.close() }
        }
    }

    companion object {
        const val WM_NCHITTEST = 0x0084
        const val WM_NCMOUSEMOVE = 0x00A0
        const val WM_NCLBUTTONDOWN = 0x00A1
        const val WM_NCLBUTTONUP = 0x00A2
        const val WM_NCMOUSELEAVE = 0x02A2
        const val WM_MOUSELEAVE = 0x02A3

        /** The hit test answers: over the maximize button, and "ask the window behind". */
        const val HTMAXBUTTON = 9L
        const val HTTRANSPARENT = -1L

        /** A mouse message's `lParam` is the screen point, x in the low word and y in the high word, each signed. */
        fun screenPoint(lParam: Long): Pair<Int, Int> =
            lParam.toInt().toShort().toInt() to (lParam.toInt() shr 16).toShort().toInt()

        /** Windows 11 names itself in `os.name`; Windows 10 has no Snap Layouts flyout. */
        fun supported(osName: String = System.getProperty("os.name").orEmpty()): Boolean =
            osName.startsWith("Windows 11") || osName.startsWith("Windows 12")

        /**
         * Puts the hit test on a frame through [hooks]. The answer says what happened, so a caller
         * can log it: only [Outcome.Installed] changes anything, and closing it undoes it. A hook
         * that throws or answers null is [Outcome.Failed] and leaves the window as it was.
         */
        fun install(
            logic: SnapLayouts,
            hooks: WindowProcHooks,
            osName: String = System.getProperty("os.name").orEmpty(),
        ): Installed {
            if (!supported(osName)) return Installed(Outcome.NotWindows11, null)
            val handle = runCatching { hooks.install(logic::onTopLevel, logic::onChild) }
                .onFailure { Logger.w(TAG, "Snap Layouts hit test not installed: ${it.message}") }
                .getOrNull()
            return if (handle == null) Installed(Outcome.Failed, null) else Installed(Outcome.Installed, handle)
        }

        private const val TAG = "SnapLayouts"
    }
}

/** A rectangle in whole pixels: the left and top edges are in, the right and bottom are out. */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun contains(x: Int, y: Int) = x >= left && x < right && y >= top && y < bottom
}

/** What the hit test needs from the window, apart from the messages. */
interface SnapHost {
    /** A screen point in the window's client pixels, or null when the window is gone. */
    fun toClient(screenX: Int, screenY: Int): Pair<Int, Int>?

    /** Asks Windows for a `WM_NCMOUSELEAVE` once the pointer leaves the non client area. */
    fun trackLeave()
}

/** A window's message handler: the message, `wParam` and `lParam`, and the result, or null to pass the message on. */
typealias WindowProc = (msg: Int, wParam: Long, lParam: Long) -> Long?

/** The one place that reaches into Windows: puts message handlers in front of a frame's windows. */
interface WindowProcHooks {
    /**
     * Subclasses the frame's window with [top] and each window inside it with [child], which is what
     * answers for the drawn picture. Returns what takes them off again, or null when it could not.
     */
    fun install(top: WindowProc, child: WindowProc): AutoCloseable?
}
