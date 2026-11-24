package com.tmplayer.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Large screens (U17): how far a column of rows may grow on a tablet or an unfolded foldable
 * before it is centred, so a switch does not stand a whole screen away from its label and a line
 * of text stays a length that reads.
 *
 * A phone, upright or on its side, is never large: its shorter side is under [Shortest]. So a
 * phone's layout is the same as before this existed, and only a tablet's columns change.
 */
object LargeScreen {
    /** Android's sw600dp: both sides at least this. A phone on its side, about 411 dp high, is not. */
    val Shortest: Dp = 600.dp

    /** The widest a column of settings rows or reading text grows on a large screen. */
    val ColumnMax: Dp = 720.dp

    /**
     * The widest a poster grid and the header over it grow on a large screen (WP-44, PT-19): wider than a
     * column of rows, since tiles want the room, but one edge for the heading, the topic tabs, the selection
     * bar and the tiles, so a 1280 dp window does not show a heading at one margin and tiles at another.
     */
    val GridMax: Dp = 1100.dp

    /** What a grid in a window [width] by [height] adds to each side beyond its margin [side]: zero on a phone. */
    fun gridGutter(width: Dp, height: Dp, side: Dp): Dp {
        if (!isLarge(width, height)) return 0.dp
        val extra = (width - side * 2 - GridMax) / 2
        return if (extra > 0.dp) extra else 0.dp
    }

    /** True for a window [width] by [height] that is a tablet's or an unfolded foldable's. */
    fun isLarge(width: Dp, height: Dp): Boolean = minOf(width, height) >= Shortest

    /**
     * What a column in a window [width] by [height] adds to each side beyond its usual margin
     * [side], so it is at most [ColumnMax] wide and centred: zero on a phone, and zero on a large
     * screen narrow enough already.
     */
    fun extraGutter(width: Dp, height: Dp, side: Dp): Dp = paneGutter(width, width, height, side)

    /**
     * The same for a list in a pane [paneWidth] wide inside a window [windowWidth] by
     * [windowHeight]: beside the tablet's rail, or in half of a split screen. Whether the screen is
     * large is the window's to say, not the pane's, so the keyboard coming up (which shortens the
     * pane, never the window) does not make the column jump; how far it is moved in is the pane's.
     */
    fun paneGutter(paneWidth: Dp, windowWidth: Dp, windowHeight: Dp, side: Dp = 0.dp): Dp {
        if (!isLarge(windowWidth, windowHeight)) return 0.dp
        val extra = (paneWidth - side * 2 - ColumnMax) / 2
        return if (extra > 0.dp) extra else 0.dp
    }
}
