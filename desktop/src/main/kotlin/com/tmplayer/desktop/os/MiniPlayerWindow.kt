package com.tmplayer.desktop.os

import java.awt.Dimension
import java.awt.Frame
import java.awt.Rectangle
import java.awt.Toolkit

/** Where the mini player sits: a 16:9 window a quarter of the screen wide, bottom right. */
object MiniGeometry {
    const val MARGIN = 24
    const val MIN_WIDTH = 400
    const val MAX_WIDTH = 640

    fun place(screen: Bounds): Bounds {
        val width = (screen.width / 4).coerceIn(MIN_WIDTH, MAX_WIDTH).coerceAtMost(screen.width - 2 * MARGIN).coerceAtLeast(1)
        val height = width * 9 / 16
        return Bounds(
            x = screen.x + screen.width - width - MARGIN,
            y = screen.y + screen.height - height - MARGIN,
            width = width,
            height = height,
        )
    }
}

/**
 * The mini player (B1.4, B2.1): the window itself shrinks to a small always on top picture in the
 * corner of its screen, and [leave] puts it back exactly as it was (size, place, maximised, on top
 * or not). The plan's version is a second undecorated window; moving the libmpv surface between
 * two windows would mean handing the engine across compositions, so this keeps the one window and
 * the one player, and the controls simply lay out smaller.
 *
 * [WindowMemory] is told to look away meanwhile, so quitting from the mini player does not reopen
 * the app at mini size next time.
 */
class MiniPlayerWindow(private val window: Frame) {

    private data class Saved(val bounds: Rectangle, val state: Int, val onTop: Boolean, val minimum: Dimension?)

    private var saved: Saved? = null

    val active: Boolean get() = saved != null

    fun toggle() = if (active) leave() else enter()

    fun enter() {
        if (active) return
        saved = Saved(window.bounds, window.extendedState, window.isAlwaysOnTop, window.minimumSize)
        WindowMemory.freeze(WindowMemory.Hold.MiniPlayer, true)
        if (window.extendedState and Frame.MAXIMIZED_BOTH != 0) window.extendedState = Frame.NORMAL
        window.minimumSize = Dimension(320, 180)
        val spot = MiniGeometry.place(usableArea())
        window.bounds = Rectangle(spot.x, spot.y, spot.width, spot.height)
        window.isAlwaysOnTop = true
    }

    fun leave() {
        val s = saved ?: return
        saved = null
        window.isAlwaysOnTop = s.onTop
        window.minimumSize = s.minimum
        window.bounds = s.bounds
        if (s.state != window.extendedState) window.extendedState = s.state
        WindowMemory.freeze(WindowMemory.Hold.MiniPlayer, false)
    }

    private fun usableArea(): Bounds {
        val config = window.graphicsConfiguration
        val b = config.bounds
        val inset = runCatching { Toolkit.getDefaultToolkit().getScreenInsets(config) }.getOrNull()
            ?: return Bounds(b.x, b.y, b.width, b.height)
        return Bounds(b.x + inset.left, b.y + inset.top, b.width - inset.left - inset.right, b.height - inset.top - inset.bottom)
    }
}
