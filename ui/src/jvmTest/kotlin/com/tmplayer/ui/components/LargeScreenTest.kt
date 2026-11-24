package com.tmplayer.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** U17: a tablet's column is centred at a readable width; a phone's, either way up, is untouched. */
class LargeScreenTest {

    @Test
    fun `phones either way up are not large`() {
        assertFalse(LargeScreen.isLarge(411.dp, 891.dp))
        assertFalse(LargeScreen.isLarge(891.dp, 411.dp))
        assertFalse(LargeScreen.isLarge(914.dp, 411.dp))
        assertEquals(0.dp, LargeScreen.extraGutter(914.dp, 411.dp, 16.dp))
        assertEquals(0.dp, LargeScreen.extraGutter(411.dp, 891.dp, 16.dp))
    }

    @Test
    fun `tablets and an open foldable are large`() {
        assertTrue(LargeScreen.isLarge(1280.dp, 800.dp))
        assertTrue(LargeScreen.isLarge(800.dp, 1280.dp))
        assertTrue(LargeScreen.isLarge(673.dp, 841.dp))
    }

    @Test
    fun `a tablet's column is at most the column width and centred`() {
        val extra = LargeScreen.extraGutter(1280.dp, 800.dp, 16.dp)
        assertEquals(1280.dp - (16.dp + extra) * 2, LargeScreen.ColumnMax)
        assertEquals(24.dp, LargeScreen.extraGutter(800.dp, 1280.dp, 16.dp))
    }

    @Test
    fun `a pane beside the rail is centred in itself, and the window says whether it is large`() {
        // A tablet on its side with an 80 dp rail: the pane is 1200 wide, its column 720 in the middle of it.
        assertEquals(240.dp, LargeScreen.paneGutter(1200.dp, 1280.dp, 800.dp))
        // A phone on its side is never large, however wide its pane.
        assertEquals(0.dp, LargeScreen.paneGutter(830.dp, 914.dp, 411.dp))
        // Half of a split screen, or an open foldable: large, but narrow enough already.
        assertEquals(0.dp, LargeScreen.paneGutter(640.dp, 640.dp, 800.dp))
        assertEquals(0.dp, LargeScreen.paneGutter(673.dp, 673.dp, 841.dp))
        // A zero or nonsense width never gives a negative gutter.
        assertEquals(0.dp, LargeScreen.paneGutter(0.dp, 1280.dp, 800.dp))
        assertEquals(LargeScreen.extraGutter(1280.dp, 800.dp, 16.dp), LargeScreen.paneGutter(1280.dp, 1280.dp, 800.dp, 16.dp))
    }

    @Test
    fun `a large screen already narrow enough keeps its margin`() {
        assertEquals(0.dp, LargeScreen.extraGutter(673.dp, 841.dp, 16.dp))
        assertEquals(0.dp, LargeScreen.extraGutter(752.dp, 900.dp, 16.dp))
    }

    @Test
    fun `a poster grid and its heading share one edge inside the grid maximum on a tablet (WP-44)`() {
        // Phones: nothing added, either way up.
        assertEquals(0.dp, LargeScreen.gridGutter(411.dp, 891.dp, 16.dp))
        assertEquals(0.dp, LargeScreen.gridGutter(891.dp, 411.dp, 16.dp))
        // A 1280 dp window: the grid fills GridMax, centred.
        val extra = LargeScreen.gridGutter(1280.dp, 800.dp, 16.dp)
        assertEquals(1280.dp, extra * 2 + 16.dp * 2 + LargeScreen.GridMax)
        // A large window narrower than the maximum keeps its margin.
        assertEquals(0.dp, LargeScreen.gridGutter(900.dp, 700.dp, 16.dp))
        // The grid is wider than a column of rows, never narrower.
        assertTrue(LargeScreen.GridMax > LargeScreen.ColumnMax)
    }
}
