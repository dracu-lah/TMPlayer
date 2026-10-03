package com.tmplayer.desktop.os

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WindowGeometryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val laptop = Bounds(0, 0, 1920, 1080)
    private val rightMonitor = Bounds(1920, 0, 2560, 1440)

    @Test
    fun `nothing saved opens at the default size, centred`() {
        val r = WindowGeometry.restore(null, listOf(laptop))
        assertEquals(RestoredWindow(null, null, 1280, 800, SavedPlacement.Floating), r)
    }

    @Test
    fun `a position on an attached screen is kept`() {
        val saved = SavedWindow(2100, 120, 1400, 900, SavedPlacement.Floating)
        val r = WindowGeometry.restore(saved, listOf(laptop, rightMonitor))
        assertEquals(RestoredWindow(2100, 120, 1400, 900, SavedPlacement.Floating), r)
    }

    @Test
    fun `a position on a monitor unplugged since goes back to centred`() {
        val saved = SavedWindow(2100, 120, 1400, 900, SavedPlacement.Floating)
        val r = WindowGeometry.restore(saved, listOf(laptop))
        assertNull(r.x)
        assertNull(r.y)
        assertEquals(1400, r.width)
        assertEquals(900, r.height)
    }

    @Test
    fun `a title bar barely on screen does not count as reachable`() {
        // Only 40 px of the window's left edge pokes onto the laptop.
        val saved = SavedWindow(-1360, 100, 1400, 900, SavedPlacement.Floating)
        val r = WindowGeometry.restore(saved, listOf(laptop))
        assertNull(r.x)
    }

    @Test
    fun `a window larger than its screen is shrunk to fit and pulled on`() {
        val saved = SavedWindow(300, 200, 2400, 1300, SavedPlacement.Floating)
        val r = WindowGeometry.restore(saved, listOf(laptop))
        assertEquals(RestoredWindow(0, 200, 1920, 1080, SavedPlacement.Floating), r)
    }

    @Test
    fun `sizes below the minimum are raised to it`() {
        val saved = SavedWindow(10, 10, 300, 200, SavedPlacement.Floating)
        val r = WindowGeometry.restore(saved, listOf(laptop))
        assertEquals(960, r.width)
        assertEquals(600, r.height)
    }

    @Test
    fun `fullscreen comes back maximised, with the floating bounds kept for later`() {
        val saved = SavedWindow(100, 100, 1300, 820, SavedPlacement.Fullscreen)
        val r = WindowGeometry.restore(saved, listOf(laptop))
        assertEquals(RestoredWindow(100, 100, 1300, 820, SavedPlacement.Maximized), r)
    }

    @Test
    fun `no screen information keeps what was saved`() {
        val saved = SavedWindow(5000, 5000, 1300, 820, SavedPlacement.Floating)
        val r = WindowGeometry.restore(saved, emptyList())
        assertNull(r.x)
        assertEquals(1300, r.width)
    }

    @Test
    fun `properties round trip, and garbage reads as nothing saved`() {
        val saved = SavedWindow(-300, 40, 1500, 950, SavedPlacement.Maximized)
        assertEquals(saved, WindowGeometry.fromProperties(WindowGeometry.toProperties(saved)))
        val centred = SavedWindow(null, null, 1500, 950, SavedPlacement.Floating)
        assertEquals(centred, WindowGeometry.fromProperties(WindowGeometry.toProperties(centred)))
        assertNull(WindowGeometry.fromProperties(java.util.Properties().apply { setProperty("width", "wide") }))
    }

    @Test
    fun `saving while maximised keeps the floating bounds seen before`() {
        val store = WindowMemoryStore(tmp.root.resolve("window.properties")) { listOf(laptop) }
        val state = WindowState(position = WindowPosition(200.dp, 150.dp), size = DpSize(1300.dp, 820.dp))
        store.observe(state)

        state.placement = WindowPlacement.Maximized
        state.size = DpSize(1920.dp, 1080.dp)
        state.position = WindowPosition(0.dp, 0.dp)
        store.save(state)

        assertEquals(SavedWindow(200, 150, 1300, 820, SavedPlacement.Maximized), store.read())

        val reopened = WindowMemoryStore(tmp.root.resolve("window.properties")) { listOf(laptop) }.load()
        assertEquals(WindowPlacement.Maximized, reopened.placement)
        assertEquals(DpSize(1300.dp, 820.dp), reopened.size)
        assertEquals(WindowPosition(200.dp, 150.dp), reopened.position)
    }
}
