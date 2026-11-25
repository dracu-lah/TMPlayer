package com.tmplayer.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How much of the top end of the window the window's own buttons cover (the desktop draws them over
 * whatever has no header of its own), so a screen with something at its top end, such as the setup's
 * and the tour's Skip all, keeps clear of them. Zero everywhere else: a phone and a television have none.
 */
val LocalWindowControlsInset = staticCompositionLocalOf { 0.dp }

/** The room [buttons] window buttons take at the top end: 40 dp each and 8 dp round the set. */
fun windowControlsInset(buttons: Int): Dp = if (buttons <= 0) 0.dp else 40.dp * buttons + 16.dp
