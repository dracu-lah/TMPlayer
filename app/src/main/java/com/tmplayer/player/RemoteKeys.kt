package com.tmplayer.player

import android.view.KeyEvent
import com.tmplayer.data.RemoteQuirks

/**
 * What a remote's keys mean in the player, decided apart from the screen so a test can check them
 * (Android's TV app quality criteria: OK pauses, Left and Right seek, Play/Pause toggles, Back
 * leaves at once).
 */
object RemoteKeys {

    /**
     * The least time between two seeks from a held arrow. A seek is a TDLib round trip, so a held
     * key lands about five a second and never one per repeat.
     */
    const val MIN_REPEAT_JUMP_MS = 150L

    /**
     * How far a jump from the key moves now, or 0 to let the press pass. The first press is always
     * the plain [baseMs]; a repeat moves in [RemoteQuirks.holdStepMs]'s growing steps and only when
     * [MIN_REPEAT_JUMP_MS] has gone by since the last jump at [lastJumpMs].
     */
    fun jumpStepMs(repeatCount: Int, baseMs: Long, nowMs: Long, lastJumpMs: Long): Long {
        val step = RemoteQuirks.holdStepMs(repeatCount, baseMs)
        if (step <= 0L) return 0L
        if (repeatCount > 0 && nowMs - lastJumpMs < MIN_REPEAT_JUMP_MS) return 0L
        return step
    }

    /** Next episode (true), previous episode (false), or null for a key that is neither. */
    fun episodeStep(keyCode: Int): Boolean? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD, KeyEvent.KEYCODE_CHANNEL_UP -> true
        KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD, KeyEvent.KEYCODE_CHANNEL_DOWN -> false
        else -> null
    }

    /** The Stop key: the position is saved and the player is left, as Back does. */
    fun isStop(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_MEDIA_STOP

    /**
     * A press of Info let go before the long press: the details panel. The long press stays
     * "Report stutter" ([PlaybackMarks.isMarkKey]), so the panel waits for the key to come up.
     */
    fun isInfoTap(keyCode: Int, downMs: Long, upMs: Long, canceled: Boolean, longPressMs: Long): Boolean =
        keyCode == KeyEvent.KEYCODE_INFO && !canceled && upMs - downMs < longPressMs

    /** What a key does while the next-up card is up over the bare picture on a television. */
    enum class NextUpKey {
        /** Not the card's: the key does what it does over the bare picture (OK pauses, Left seeks). */
        Ignore,

        /** Focus moves onto the card. */
        Engage,

        /** The card's own button gets the key (OK plays the next episode). */
        Pass,

        /** Focus leaves the card and the key does what it does over the bare picture. */
        Release,
    }

    /**
     * The card appears without taking the remote: OK stays pause and resume, Left seeks back. Right
     * or Down walks onto it, and only then is OK its "Play now". Left, Up or Down walks off again.
     */
    fun nextUpKey(keyCode: Int, engaged: Boolean): NextUpKey = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_RIGHT ->
            if (engaged) NextUpKey.Pass else NextUpKey.Engage
        KeyEvent.KEYCODE_DPAD_DOWN ->
            if (engaged) NextUpKey.Release else NextUpKey.Engage
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
            if (engaged) NextUpKey.Pass else NextUpKey.Ignore
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP ->
            if (engaged) NextUpKey.Release else NextUpKey.Ignore
        else -> NextUpKey.Ignore
    }
}
