package com.tmplayer.desktop.ui

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 10 cells, 4 wide: rows [0..3], [4..7], [8, 9]. */
class GridNavTest {

    private fun move(from: Int, key: NavKey, pageRows: Int = 1) = GridNav.move(from, key, columns = 4, count = 10, pageRows = pageRows)

    @Test
    fun `arrows move one cell and stop at the edges`() {
        assertEquals(6, move(5, NavKey.Right))
        assertEquals(4, move(5, NavKey.Left))
        assertEquals(1, move(5, NavKey.Up))
        assertEquals(9, move(5, NavKey.Down))
        // No wrap from the end of one row to the start of the next.
        assertEquals(3, move(3, NavKey.Right))
        assertEquals(4, move(4, NavKey.Left))
        assertEquals(2, move(2, NavKey.Up))
        assertEquals(8, move(8, NavKey.Down))
        assertEquals(9, move(9, NavKey.Right))
    }

    @Test
    fun `down into a short last row lands on the nearest cell`() {
        assertEquals(9, move(7, NavKey.Down))
        assertEquals(8, move(4, NavKey.Down))
    }

    @Test
    fun `home and end are the row, with ctrl the grid`() {
        assertEquals(4, move(6, NavKey.RowStart))
        assertEquals(7, move(6, NavKey.RowEnd))
        assertEquals(9, move(8, NavKey.RowEnd))
        assertEquals(0, move(6, NavKey.First))
        assertEquals(9, move(6, NavKey.Last))
    }

    @Test
    fun `page keys move a screen of rows and keep the column`() {
        assertEquals(9, move(1, NavKey.PageDown, pageRows = 2))
        assertEquals(1, move(9, NavKey.PageUp, pageRows = 2))
        assertEquals(6, move(2, NavKey.PageDown, pageRows = 1))
        assertEquals(2, move(2, NavKey.PageUp, pageRows = 5))
        assertEquals(9, move(3, NavKey.PageDown, pageRows = 9))
    }

    @Test
    fun `a list is a grid one column wide`() {
        assertEquals(4, GridNav.move(3, NavKey.Down, columns = 1, count = 5))
        assertEquals(4, GridNav.move(4, NavKey.Down, columns = 1, count = 5))
        assertEquals(3, GridNav.move(3, NavKey.Right, columns = 1, count = 5))
        assertEquals(-1, GridNav.move(0, NavKey.Down, columns = 1, count = 0))
    }

    @Test
    fun `keys map to grid moves`() {
        assertEquals(NavKey.Left, GridNav.keyFor(Key.DirectionLeft, ctrl = false, shift = false, alt = false, meta = false))
        assertEquals(NavKey.First, GridNav.keyFor(Key.MoveHome, ctrl = true, shift = false, alt = false, meta = false))
        assertEquals(NavKey.Last, GridNav.keyFor(Key.MoveEnd, ctrl = false, shift = false, alt = false, meta = true))
        assertEquals(NavKey.PageDown, GridNav.keyFor(Key.PageDown, ctrl = false, shift = false, alt = false, meta = false))
        // Alt+Left is back, Shift+arrows are left to text selection.
        assertNull(GridNav.keyFor(Key.DirectionLeft, ctrl = false, shift = false, alt = true, meta = false))
        assertNull(GridNav.keyFor(Key.DirectionDown, ctrl = false, shift = true, alt = false, meta = false))
        assertNull(GridNav.keyFor(Key.A, ctrl = false, shift = false, alt = false, meta = false))
    }
}
