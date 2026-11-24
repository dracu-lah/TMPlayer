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

    /** From this repeat of a held arrow the scan option moves the scrub position instead of seeking (WP-79). */
    const val SCAN_FROM_REPEAT = 3

    /** Without a repeat for this long a scan lands on its own: the key was let go without its release being seen. */
    const val SCAN_IDLE_MS = 400L

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
     * How late a repeat of a held arrow over the bare picture may be handled and still seek: one
     * older than this waited behind a slow seek on a busy stick, and the key may be up already.
     * Looser than the bar's and the lists' [STALE_REPEAT_MS], since each step here is a real seek
     * that keeps the main thread busy, and the 150 ms gate already paces the run (PB-04).
     */
    const val STALE_JUMP_MS = 250L

    /**
     * A step of a held arrow, rewind or fast forward over the bare picture: [jumpStepMs]'s ramp and
     * gate, and a repeat made at [eventMs] that waited past [STALE_JUMP_MS] is dropped, so the video
     * stops moving when the key is let go. 0 lets the repeat pass without a seek.
     */
    fun pictureStepMs(repeatCount: Int, baseMs: Long, eventMs: Long, nowMs: Long, lastJumpMs: Long, durationMs: Long): Long {
        if (repeatCount > 0 && nowMs - eventMs > STALE_JUMP_MS) return 0L
        return jumpStepMs(repeatCount, baseMs, nowMs, lastJumpMs, durationMs)
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

    /** A second CC press within this opens the picker; the first only toggles (WP-19). */
    const val CAPTIONS_AGAIN_MS = 3_000L

    /** How long OK is held over the bare picture before the "open in another app" chooser (WP-19). */
    const val HANDOVER_HOLD_MS = 1_500L

    /** What a press of the CC key does. */
    enum class CaptionsPress { Toggle, OpenPicker }

    /**
     * The first press toggles subtitles; a second within [CAPTIONS_AGAIN_MS] or a long press opens the
     * picker. [lastToggleAtMs] is 0 when the key has not toggled yet.
     */
    fun captionsPress(longPress: Boolean, lastToggleAtMs: Long, nowMs: Long): CaptionsPress =
        if (longPress || (lastToggleAtMs > 0L && nowMs - lastToggleAtMs in 0L..CAPTIONS_AGAIN_MS)) {
            CaptionsPress.OpenPicker
        } else {
            CaptionsPress.Toggle
        }

    /**
     * One press of the CC key, from key down to release. A hold only opens the picker: the toggle
     * waits for the release and is dropped when the press became a hold (WP-19), so a long press
     * never also changes the subtitles.
     */
    class CaptionsKeyPress {
        private var down = false
        private var opened = false

        /** A key down or repeat; true once, on the long press, when the picker opens now. */
        fun onDown(repeatCount: Int, longPress: Boolean): Boolean {
            if (repeatCount == 0) {
                down = true
                opened = false
                return false
            }
            if (!down || opened || !longPress) return false
            opened = true
            return true
        }

        /** True while a press waits for its release to be judged. */
        val pending: Boolean get() = down && !opened

        /** The release: true when it ends a short press (the toggle, or the second press's picker). */
        fun onUp(canceled: Boolean): Boolean {
            val short = pending && !canceled
            down = false
            return short
        }
    }

    /** What the CC toggle does to the file's [count] subtitle tracks. */
    sealed interface CaptionsToggle {
        /** The file has no subtitle track. */
        data object None : CaptionsToggle

        /** A track shows: turn subtitles off. */
        data object Off : CaptionsToggle

        /** Nothing shows: turn track [index] on. */
        data class On(val index: Int) : CaptionsToggle
    }

    /**
     * Subtitles on to the track the viewer last used ([last], an index among the text tracks) or else
     * the one the file marks as its default ([defaultIndex]) or the first; off when one shows
     * ([selected]).
     */
    fun captionsToggle(count: Int, selected: Int?, last: Int?, defaultIndex: Int?): CaptionsToggle = when {
        count <= 0 -> CaptionsToggle.None
        selected != null -> CaptionsToggle.Off
        else -> CaptionsToggle.On((last ?: defaultIndex ?: 0).takeIf { it in 0 until count } ?: 0)
    }

    /** The playback keys that keep working under the More panel and a track picker. */
    enum class PanelMedia { Toggle, Play, Pause, Stop }

    /** Which playback key [keyCode] is for the panels, or null: only Play/Pause, Play, Pause and Stop pass through. */
    fun panelMedia(keyCode: Int): PanelMedia? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> PanelMedia.Toggle
        KeyEvent.KEYCODE_MEDIA_PLAY -> PanelMedia.Play
        KeyEvent.KEYCODE_MEDIA_PAUSE -> PanelMedia.Pause
        KeyEvent.KEYCODE_MEDIA_STOP -> PanelMedia.Stop
        else -> null
    }

    /**
     * A held OK on a television hands the video over once, after [HANDOVER_HOLD_MS], and only when the
     * press began over the bare picture (row down). With the row up a held OK is a plain OK, so a slow
     * press on a button never leaves the app. [repeatCount] is above 0 on a repeat, [heldMs] the time
     * since the key went down.
     */
    fun isHandoverHold(startedOverPicture: Boolean, alreadyHandedOver: Boolean, repeatCount: Int, heldMs: Long): Boolean =
        startedOverPicture && !alreadyHandedOver && repeatCount > 0 && heldMs >= HANDOVER_HOLD_MS

    /**
     * One press of OK on a television, from its key down to its release. Over the bare picture the
     * pause waits for the release, so a press that becomes the 1.5 s handover never also pauses
     * (WP-19): [onUp] says whether the release is the pause. Anywhere else the press is not this
     * object's: OK did its own thing on the way down.
     */
    class OkPress {
        private var overPicture = false
        private var handedOver = false

        /**
         * A key down or repeat. [overPicture] is read on the first event only. True once, on the
         * repeat that has been held [HANDOVER_HOLD_MS]: that is the handover.
         */
        fun onDown(repeatCount: Int, heldMs: Long, overPicture: Boolean): Boolean {
            if (repeatCount == 0) {
                this.overPicture = overPicture
                handedOver = false
                return false
            }
            if (!isHandoverHold(this.overPicture, handedOver, repeatCount, heldMs)) return false
            handedOver = true
            return true
        }

        /** Whether the first press of this OK waits for its release to pause. */
        val pausesOnRelease: Boolean get() = overPicture

        /** The release: true when it is the pause (a plain press over the picture, not a cancel or a handover). */
        fun onUp(canceled: Boolean): Boolean {
            val pause = overPicture && !handedOver && !canceled
            overPicture = false
            return pause
        }
    }

    /**
     * The keys that keep their meaning while the pause mark stands in for the row (WP-21): the
     * media keys and CC, which name what they do, and the volume keys, which are the system's.
     * Every other key only brings the row back ([PauseMarkKeys]).
     */
    fun actsThroughPauseMark(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CAPTIONS,
        KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
        -> true
        else -> false
    }

    /**
     * A television paused long enough that the row made way for the small "Paused" mark (WP-21):
     * any key but the media ones only brings the row back, with the play disc holding the remote
     * (PB2-03). Without this Back left the video, Left and Right seeked while paused and OK resumed,
     * all from a screen that showed no row. The press that raises the row is taken whole: its
     * repeats and its release too, so a held arrow does not seek once the row is up and OK's or
     * Info's release does nothing more.
     */
    class PauseMarkKeys {
        private var taken = 0

        /**
         * One key event. [markShown] is whether the mark stands in for the row now; [raise] brings
         * the row back and is called once, on the press. True when the event is taken here.
         */
        fun onKey(keyCode: Int, action: Int, markShown: Boolean, raise: () -> Unit): Boolean {
            if (taken != 0) {
                if (keyCode == taken) {
                    if (action == KeyEvent.ACTION_UP) taken = 0
                    return true
                }
                // Another key went down: the release of the first was lost (focus moved, a remote dropped it).
                if (action == KeyEvent.ACTION_DOWN) taken = 0
            }
            if (action != KeyEvent.ACTION_DOWN || !markShown || actsThroughPauseMark(keyCode)) return false
            taken = keyCode
            raise()
            return true
        }
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

/**
 * The position a held arrow scans to (WP-79): the virtual target moves with [PlayerRemoteKeys]'s own
 * steps and pace (the 150 ms gate, x1, x3, x6, capped to a share of the length, a stale repeat
 * dropped) and nothing is sought until [commit], once, on the key's release or after
 * [PlayerRemoteKeys.SCAN_IDLE_MS] without a repeat. Two keys at once: the last one's direction wins,
 * from the same target. No duration (a live stream) moves nothing, and the caller seeks live.
 */
class RemoteScan {
    /** Where a release would land, or null with no scan in progress. */
    var target: Long? = null
        private set

    private var lastStepAt = 0L

    /**
     * One repeat of a held arrow: the new target, or null when nothing moved (no duration, a repeat
     * inside the gate, or one that waited too long behind a slow main thread).
     */
    fun step(positionMs: Long, forward: Boolean, repeatCount: Int, eventMs: Long, nowMs: Long, durationMs: Long, endGuardMs: Long): Long? {
        if (durationMs <= 0L) return null
        val base = PlayerRemoteKeys.remoteBaseMs(forward, true)
        val step = PlayerRemoteKeys.pictureStepMs(repeatCount, base, eventMs, nowMs, lastStepAt, durationMs)
        if (step <= 0L) return null
        lastStepAt = nowMs
        val from = target ?: positionMs
        val next = (from + if (forward) step else -step).coerceIn(0L, (durationMs - endGuardMs).coerceAtLeast(0L))
        target = next
        return next
    }

    /** The one seek: where the scan stopped, and the scan is over. Null when none was in progress. */
    fun commit(): Long? = target.also { cancel() }

    /** Drops the scan without a seek (the screen stopped): nothing happened to the video. */
    fun cancel() {
        target = null
        lastStepAt = 0L
    }
}
