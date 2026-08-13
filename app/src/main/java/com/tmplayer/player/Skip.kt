package com.tmplayer.player

/**
 * How far a jump goes, in each direction.
 *
 * The two figures are deliberately different. Going forward is skipping something, so a press wants
 * distance covered; going back is almost always a line that was missed, and a jump the length of
 * the forward one overshoots it.
 *
 * Shared by everything that jumps: the phone's double tap, the television's rewind and
 * fast-forward buttons, the media keys on an attached keyboard or remote, and the increments
 * handed to ExoPlayer so notification and headset controls agree with the buttons on screen.
 */
object Skip {
    const val FORWARD_MS = 10_000L
    const val BACK_MS = 5_000L
}
