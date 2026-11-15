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
    fun `a large screen already narrow enough keeps its margin`() {
        assertEquals(0.dp, LargeScreen.extraGutter(673.dp, 841.dp, 16.dp))
        assertEquals(0.dp, LargeScreen.extraGutter(752.dp, 900.dp, 16.dp))
    }
}
