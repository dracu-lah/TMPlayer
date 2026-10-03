package com.tmplayer.desktop.os

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniGeometryTest {

    @Test
    fun `a quarter of a 1080p screen, bottom right, 16 to 9`() {
        val spot = MiniGeometry.place(Bounds(0, 0, 1920, 1040))
        assertEquals(480, spot.width)
        assertEquals(270, spot.height)
        assertEquals(1920 - 480 - MiniGeometry.MARGIN, spot.x)
        assertEquals(1040 - 270 - MiniGeometry.MARGIN, spot.y)
    }

    @Test
    fun `clamped on small and very large screens, and on a second monitor`() {
        assertEquals(MiniGeometry.MIN_WIDTH, MiniGeometry.place(Bounds(0, 0, 1280, 720)).width)
        assertEquals(MiniGeometry.MAX_WIDTH, MiniGeometry.place(Bounds(0, 0, 5120, 2880)).width)
        val right = MiniGeometry.place(Bounds(1920, 0, 2560, 1440))
        assertTrue(right.x > 1920 && right.x + right.width <= 1920 + 2560)
    }

    @Test
    fun `never wider than the screen`() {
        val tiny = MiniGeometry.place(Bounds(0, 0, 300, 200))
        assertTrue(tiny.width <= 300 - 2 * MiniGeometry.MARGIN)
    }
}
