package com.tmplayer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How far a touch list in a pane [paneWidth] wide moves in from each side so its rows stand in a
 * centred column of [LargeScreen.ColumnMax] on a tablet or an open foldable (U17): the chat list,
 * search and Downloads, which otherwise ran a row's title at one edge of a 1280 dp screen and its
 * size or button at the other. The window (Android's own configuration, which the keyboard does
 * not change) says whether the screen is large; the pane, beside the rail or in half of a split
 * screen, says how far. Zero on a phone either way up and on a television, whose layouts stay
 * as they were; the list keeps the whole width, so it still scrolls from the margins.
 */
@Composable
internal fun largePaneGutter(paneWidth: Dp, touch: Boolean = isTouch()): Dp {
    if (!touch) return 0.dp
    val window = LocalConfiguration.current
    return LargeScreen.paneGutter(paneWidth, window.screenWidthDp.dp, window.screenHeightDp.dp)
}
