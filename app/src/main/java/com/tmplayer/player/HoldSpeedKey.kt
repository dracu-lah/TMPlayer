package com.tmplayer.player

import android.view.KeyEvent

/**
 * Hold the fast-forward key to play faster until it is let go (U3): the remote's answer to the
 * finger held on the picture on a phone ([PlayerGestures]). Decided apart from the screen so a
 * test can check which presses start it.
 *
 * A plain press still jumps, as it always did. The hold starts from the system's long press (the
 * first repeat of a held key), once, at the speed in Settings; "Off" there (a hold speed of zero)
 * leaves a held key doing what it did before, repeating the jump. Fire TV's remote scans in growing
 * steps when held ([RemoteQuirks.holdStepMs]), which is that platform's own habit, so the hold
 * leaves it alone.
 */
object HoldSpeedKey {

    fun startsHold(
        keyCode: Int,
        repeatCount: Int,
        isLongPress: Boolean,
        alreadyHolding: Boolean,
        holdSpeed: Float,
        remoteScansWhenHeld: Boolean,
    ): Boolean =
        keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD &&
            !alreadyHolding &&
            isLongPress && repeatCount >= 1 &&
            holdSpeed > 0f &&
            !remoteScansWhenHeld
}
