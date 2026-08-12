package com.tmplayer.player

/**
 * How far a jump goes, in each direction.
 *
 * The two figures are deliberately different. Going forward is skipping something, and a viewer
 * doing it wants distance covered per press; going back is almost always a line that was missed,
 * and a jump the length of the forward one overshoots it and lands before the scene, which is why
 * every player that uses one figure for both has people pressing back twice and then forward once.
 * Ten seconds ahead and five behind is what a viewer reaching for either one actually means.
 *
 * Shared by everything that jumps: the phone's double tap, the television's rewind and
 * fast-forward buttons, the media keys on an attached keyboard or remote, and the increments
 * handed to ExoPlayer so notification and headset controls agree with the buttons on screen.
 */
object Skip {
    const val FORWARD_MS = 10_000L
    const val BACK_MS = 5_000L
}
