package com.tmplayer.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How much of the foot of the screen a card floating over the shell covers right now, such as the
 * first sign in card. Content that scrolls pads its end by this much, so its last lines sit above
 * the card rather than under it, which on a phone held sideways was most of the first-video text.
 * A plain holder rather than a composition local: the card is drawn beside the shell, not around it.
 */
object BottomCardInset {
    var height: Dp by mutableStateOf(0.dp)
}
