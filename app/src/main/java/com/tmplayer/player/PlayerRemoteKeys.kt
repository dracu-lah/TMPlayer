package com.tmplayer.player

import android.view.KeyEvent
import com.tmplayer.data.RemoteQuirks
import com.tmplayer.ui.components.STALE_REPEAT_MS
import kotlin.math.roundToInt

/**
 * What a remote's keys mean in the player, decided apart from the screen so a test can check them
 * (Android's TV app quality criteria: OK pauses, Left and Right seek, Play/Pause toggles, Back
 * leaves at once).
 */
object PlayerRemoteKeys {

    /**
     * The least time between two seeks from a held arrow. A seek is a TDLib round trip, so a held
     * key lands about five a second and never one per repeat.
     */
    const val MIN_REPEAT_JUMP_MS = 150L

    /**
     * A television remote's Left and Right, and its rewind and fast forward keys: ten seconds each
     * way, on the bare picture and on the focused bar alike, the step YouTube's TV app takes.
     */
    const val TV_JUMP_MS = 10_000L

    /** Page Up and Page Down in a video without chapters: a minute back or forward. */
    const val PAGE_JUMP_MS = 60_000L

    /**
     * The first step of a remote's jump. On a television it is [TV_JUMP_MS] both ways; elsewhere (a
     * phone's keyboard or paired remote) it stays [Skip]'s five back and ten forward. Only the
     * remote's base changes here: [Skip.BACK_MS] also feeds Media3's seek back increment and the
     * notification's buttons, which keep their five seconds.
     */
    fun remoteBaseMs(forward: Boolean, tv: Boolean): Long = when {
        tv -> TV_JUMP_MS
        forward -> Skip.FORWARD_MS
        else -> Skip.BACK_MS
    }

    /**
     * How far a jump from the key moves now, or 0 to let the press pass. The first press is always
     * the plain [baseMs]; a repeat moves in [RemoteQuirks.holdStepMs]'s growing steps (held to a
     * share of [durationMs] when it is known) and only when [MIN_REPEAT_JUMP_MS] has gone by since
     * the last jump at [lastJumpMs].
     */
    fun jumpStepMs(repeatCount: Int, baseMs: Long, nowMs: Long, lastJumpMs: Long, durationMs: Long = 0L): Long {
        val step = RemoteQuirks.holdStepMs(repeatCount, baseMs, durationMs)
        if (step <= 0L) return 0L
        if (repeatCount > 0 && nowMs - lastJumpMs < MIN_REPEAT_JUMP_MS) return 0L
        return step
    }

    /**
     * A step on the focused seek bar: [jumpStepMs]'s ramp and gate, and a repeat made at [eventMs]
     * that waited past [STALE_REPEAT_MS] to be handled is dropped (the lists' rule), so a slow stick
     * does not keep scrubbing after the key is let go. 0 lets the repeat pass without a move.
     */
    fun barStepMs(repeatCount: Int, baseMs: Long, eventMs: Long, nowMs: Long, lastStepMs: Long, durationMs: Long): Long {
        if (repeatCount > 0 && nowMs - eventMs > STALE_REPEAT_MS) return 0L
        return jumpStepMs(repeatCount, baseMs, nowMs, lastStepMs, durationMs)
    }

    /**
     * How many plain steps [stepMs] is, for the pace beside the running total, to one decimal: 1
     * for a tap or the first stretch of a hold, then 3 and 6 as the hold grows, and the real figure
     * where the length caps it (3.6 for a 36 s step on a two hour video).
     */
    fun pace(stepMs: Long, baseMs: Long): Float {
        if (baseMs <= 0L || stepMs <= baseMs) return 1f
        return (stepMs * 10.0 / baseMs).roundToInt() / 10f
    }

    /**
     * Page Down (true) or Page Up (false) on a television, or null for any other key and on every
     * other form factor, whose keyboards get their own rules later. Once per press in the player.
     */
    fun pageStep(keyCode: Int, tv: Boolean): Boolean? = if (!tv) null else when (keyCode) {
        KeyEvent.KEYCODE_PAGE_DOWN -> true
        KeyEvent.KEYCODE_PAGE_UP -> false
        else -> null
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
