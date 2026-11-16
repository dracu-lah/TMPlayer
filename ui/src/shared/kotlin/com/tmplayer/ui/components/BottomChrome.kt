package com.tmplayer.ui.components

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How much of the bottom of the window a floating control bar keeps for itself, measured up from
 * the window's bottom edge: the phone's floating Home and Search buttons, their gap above the
 * edge, and the navigation bar under them. Zero wherever there is no such bar (the television, the
 * desktop, a chat's grid, the player).
 *
 * A list under the bar pads its last row by it, less what its own container already keeps for the
 * navigation bar, so the last row can be scrolled clear of the buttons. Anything else that floats
 * at the bottom ([ScrollToTop], a snackbar) sits above it.
 */
val LocalBottomChrome = compositionLocalOf { 0.dp }

/**
 * How much room the connection banner takes above the floating bar while it is up (R4, 9.1): its
 * height and the gap under it. [LocalBottomChrome] already includes it, for the lists that pad by
 * that; Home pads by its own insets and adds this one.
 */
val LocalBannerRoom = compositionLocalOf { 0.dp }
