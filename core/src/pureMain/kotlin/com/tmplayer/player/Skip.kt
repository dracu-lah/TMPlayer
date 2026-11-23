package com.tmplayer.player

/**
 * How far a jump goes, in each direction.
 *
 * The two figures are deliberately different. Going forward is skipping something, so a press wants
 * distance covered; going back is almost always a line that was missed, and a jump the length of
 * the forward one overshoots it.
 *
 * Shared by everything that jumps on a phone or tablet: the double tap, the rewind and fast-forward
 * buttons, the media keys on an attached keyboard or remote, and the increments handed to ExoPlayer so notification and headset controls agree with the
 * buttons on screen.
 *
 * A television remote takes ten seconds both ways instead, on the bare picture and on the focused
 * bar, and ramps when held (PlayerRemoteKeys.remoteBaseMs in the app); [BACK_MS] stays five for
 * everything else. [BAR_MS] is what TalkBack's scroll actions move the bar by.
 */
object Skip {
    const val FORWARD_MS = 10_000L
    const val BACK_MS = 5_000L
    const val BAR_MS = 30_000L

    /**
     * The back and forward buttons beside play: ten seconds each way, the figure on the button. A
     * button names its number, and the one back from a missed line is pressed again rather than
     * overshooting, so it is not the arrows' five.
     */
    const val BUTTON_MS = 10_000L
}
