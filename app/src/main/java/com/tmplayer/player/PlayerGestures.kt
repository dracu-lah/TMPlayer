package com.tmplayer.player

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Window
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The gestures a phone viewer expects from a video player, and nothing else.
 *
 * A remote has buttons for all of this, so none of it exists on the TV. On a phone: a tap raises
 * the controls (or plays and pauses, if the viewer chose that); a double tap on a side jumps, and
 * every further tap on the same side adds another jump with a running total; a double tap in the
 * middle plays and pauses; a held finger plays fast until it lifts; a sideways drag scrubs; a
 * vertical drag on the left is brightness and on the right volume; a pinch steps the fitting.
 *
 * Fed from the activity's own [android.app.Activity.dispatchTouchEvent] rather than attached to
 * the video view, because once the controller is up its buttons and scrub bar are the views under
 * the finger and the video view sees nothing. A touch that lands on the controls themselves is
 * left to them entirely ([isOnChrome]): before that rule a press on a button also counted as a tap
 * on the picture, and the row folded away a moment after the press.
 *
 * While the controls are down the activity hands every touch here and to nowhere else, which is
 * what stops Media3's own view treating a brightness drag or a hold as a reason to throw the whole
 * transport row over the picture.
 */
class PlayerGestures(
    context: Context,
    private val window: Window,
    private val onSkip: (Long) -> Unit,
    /** One more double tap in a run: the side, the run's total so far, and where the finger was. */
    private val onSeekStep: (TapZone, Long, Float, Float) -> Unit = { _, _, _, _ -> },
    /** A vertical drag's level, 0 to 1, on the left (brightness) or the right (volume). */
    private val onLevel: (left: Boolean, fraction: Float, brightness: Boolean) -> Unit = { _, _, _ -> },
    /** A sideways drag in progress: how far it has moved, where it would land, and the length. */
    private val onScrub: (deltaMs: Long, targetMs: Long, durationMs: Long) -> Unit = { _, _, _ -> },
    private val onScrubEnd: () -> Unit = {},
    private val onPinch: (expanding: Boolean) -> Unit = {},
    /** Where the video is now and how long it is, for the drag that scrubs. */
    private val positionMs: () -> Long = { 0L },
    private val durationMs: () -> Long = { 0L },
    private val onSeekTo: (Long) -> Unit = {},
    /** Held down for a fast-forward that lasts as long as the finger does. */
    private val onHold: (holding: Boolean) -> Unit = {},
    /** A single tap on the picture, which raises or drops the controls. */
    private val onTapControls: () -> Unit = {},
    /** Play or pause from the picture: a double tap in the middle, or a tap under that setting. */
    private val onTogglePlay: (fromSingleTap: Boolean) -> Unit = {},
    /** True when a touch at these window coordinates lands on the controls, not the picture. */
    private val isOnChrome: (Float, Float) -> Boolean = { _, _ -> false },
    /**
     * The left and right system gesture insets in pixels: a sideways drag that starts inside one is
     * the system's back gesture, not a scrub.
     */
    private val systemEdges: () -> Pair<Int, Int> = { 0 to 0 },
) {

    /** The viewer's settings for all of this; replaced when they are read off disk. */
    var prefs = TouchPrefs()

    /**
     * True while the transport row is up, in which case drags are left alone.
     *
     * A thumb on the scrub bar rarely travels in a straight line, and stealing that as a volume
     * change would make the one control everybody uses unreliable. Taps, double taps, holds and
     * pinches on the bare picture still work, since none of them collides.
     */
    var controlsVisible = false

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** [DRAG_NONE] between drags; set on the first scroll of a gesture and read for the rest. */
    private var dragKind = DRAG_NONE

    /** The brightness or volume the finger went down on, as a fraction of the full range. */
    private var dragFrom = 0f

    /** Where a scrubbing drag started, and where it has reached. Milliseconds. */
    private var dragFromMs = 0L
    private var seekTarget = -1L

    /** True between a long press and the finger lifting: the hold-to-speed-up gesture. */
    private var holding = false

    /** True for the whole of a touch that began on the controls; the picture ignores it. */
    private var onChrome = false

    /** The run of double taps in progress, for the "+20 seconds" total. */
    private val counter = SeekCounter()

    private var viewWidth = 0
    private var viewHeight = 0

    private val detector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {

            override fun onDown(event: MotionEvent): Boolean = true

            /**
             * Hold anywhere on the picture to run it fast, let go to drop back.
             *
             * A hold rather than a toggle on purpose: there is no state left behind to notice
             * later and wonder about.
             */
            override fun onLongPress(event: MotionEvent) {
                if (prefs.holdSpeed <= 0f || scaling || dragKind != DRAG_NONE) return
                holding = true
                onHold(true)
            }

            /**
             * The third and later taps of a run. The detector reports a double tap only for the
             * second tap of each pair, so a tap that lands while a run is live, on the same side,
             * is counted here, the moment the finger lifts.
             */
            override fun onSingleTapUp(event: MotionEvent): Boolean {
                val now = SystemClock.uptimeMillis()
                val zone = TapZone.of(event.x, viewWidth)
                if (zone != TapZone.Centre && counter.isLive(now) && counter.zone == zone) {
                    step(zone, event, now)
                }
                return true
            }

            /**
             * The single tap: confirmed rather than immediate, because the sides of the picture
             * belong to the double tap, and acting on the first of the two would flash the
             * transport row over every seek. Ignored while a run of double taps is live, so the
             * last tap of a run cannot also raise the row.
             */
            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                if (scaling || holding || dragKind != DRAG_NONE) return false
                if (counter.isLive(SystemClock.uptimeMillis())) return true
                if (prefs.tapPlaysPauses) onTogglePlay(true) else onTapControls()
                return true
            }

            override fun onDoubleTap(event: MotionEvent): Boolean {
                if (viewWidth <= 0) return false
                val now = SystemClock.uptimeMillis()
                when (val zone = TapZone.of(event.x, viewWidth)) {
                    TapZone.Centre -> {
                        counter.reset()
                        onTogglePlay(false)
                    }
                    else -> step(zone, event, now)
                }
                return true
            }

            override fun onScroll(
                start: MotionEvent?,
                event: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                val from = start ?: return false
                if (viewHeight <= 0) return false
                if (controlsVisible || scaling || holding) return false
                if (dragKind == DRAG_NONE && !begin(from, event)) return false

                // Measured from where the finger went down rather than summed step by step, so a
                // drag that wanders and comes back lands where it started.
                if (dragKind == DRAG_SEEK) {
                    applySeek(event.x - from.x)
                    return true
                }
                val travel = (from.y - event.y) / (viewHeight * DRAG_RANGE)
                if (dragKind == DRAG_BRIGHTNESS) {
                    applyBrightness(dragFrom + travel)
                } else {
                    applyVolume(dragFrom + travel)
                }
                return true
            }
        },
    )

    /** One jump of a double-tap run, and the total it has reached. */
    private fun step(zone: TapZone, event: MotionEvent, now: Long) {
        val steps = counter.tap(zone, now)
        val jump = prefs.doubleTapMs
        onSkip(if (zone == TapZone.Left) -jump else jump)
        onSeekStep(zone, steps * jump / 1000, event.x, event.y)
    }

    /** True between the first and last finger of a pinch, so no drag is read out of it. */
    private var scaling = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {

            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                scaling = true
                dragKind = DRAG_NONE
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // One step per pinch, not one per frame: the control has three stops, and
                // reporting every intermediate scale factor would run through all of them
                // before the fingers finished moving.
                if (detector.scaleFactor > 1f + PINCH_THRESHOLD) {
                    onPinch(true)
                    return true
                }
                if (detector.scaleFactor < 1f - PINCH_THRESHOLD) {
                    onPinch(false)
                    return true
                }
                return false
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                scaling = false
            }
        },
    )

    /**
     * Offers one touch event to the gestures. Whether the event goes on to the views underneath is
     * the activity's decision, not this class's.
     *
     * [width] and [height] are the video surface's, which is the frame the left half, right half
     * and drag range are all measured against.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun onTouchEvent(event: MotionEvent, width: Int, height: Int): Boolean {
        viewWidth = width
        viewHeight = height
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            onChrome = isOnChrome(event.rawX, event.rawY)
        }
        val finished = event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        if (onChrome) {
            if (finished) onChrome = false
            return false
        }
        scaleDetector.onTouchEvent(event)
        detector.onTouchEvent(event)
        if (finished) {
            if (dragKind == DRAG_SEEK) onScrubEnd()
            if (dragKind == DRAG_SEEK && seekTarget >= 0) onSeekTo(seekTarget)
            if (holding) {
                holding = false
                onHold(false)
            }
            seekTarget = -1L
            dragKind = DRAG_NONE
            scaling = false
        }
        return false
    }

    /**
     * Decides what a drag is for, once, and then stops asking.
     *
     * Measured from where the finger went down rather than from the last few pixels of travel,
     * because a thumb moving across glass wobbles and one noisy sample is enough to file a volume
     * drag as a scrub. There is a dead angle between the two: a drag has to be twice as tall as it
     * is wide to count as vertical, and anything shallower is read as sideways. A diagonal is a
     * gesture whose owner has not decided yet, and guessing at it is worse than waiting a frame.
     *
     * Returns false while the travel is still inside the dead angle or too short to read, in which
     * case the next move event asks again.
     */
    private fun begin(start: MotionEvent, event: MotionEvent): Boolean {
        val travelX = event.x - start.x
        val travelY = event.y - start.y
        val arm = viewHeight * ARM_FRACTION
        if (abs(travelY) < arm && abs(travelX) < arm) return false
        if (abs(travelY) <= abs(travelX) * VERTICAL_RATIO) {
            // Sideways: scrubbing. The double tap moves in fixed jumps and the scrub bar needs the
            // controls up first, so this is the only way to travel a few minutes through a video
            // with the picture still fully visible. Not from inside the system's edge strips,
            // where the same movement is the back gesture.
            if (!prefs.seekGesture) return false
            val (leftEdge, rightEdge) = systemEdges()
            if (start.x < leftEdge || start.x > viewWidth - rightEdge) return false
            val length = durationMs()
            if (length <= 0) return false
            dragKind = DRAG_SEEK
            dragFromMs = positionMs()
            seekTarget = dragFromMs
            return true
        }
        // A band down the middle belongs to neither, which stops a drag started in the centre of
        // the picture from changing whichever of the two it happened to land a pixel nearer.
        when {
            start.x < viewWidth * LEFT_EDGE -> {
                if (!prefs.brightnessGesture) return false
                dragKind = DRAG_BRIGHTNESS
                dragFrom = currentBrightness()
            }
            start.x > viewWidth * RIGHT_EDGE -> {
                if (!prefs.volumeGesture) return false
                dragKind = DRAG_VOLUME
                dragFrom = currentVolume()
            }
            else -> return false
        }
        return true
    }

    /**
     * Turns sideways travel into a position, and says where it would land.
     *
     * Nothing is seeked until the finger lifts. Seeking on every frame of the drag would mean a
     * network video fetching a new window for each one: a stutter, and a pile of cancelled
     * requests for a position the viewer was only passing through.
     */
    private fun applySeek(travelPx: Float) {
        val length = durationMs()
        if (viewWidth <= 0 || length <= 0) return
        val delta = (travelPx / viewWidth * SEEK_RANGE_MS).toLong()
        val target = (dragFromMs + delta).coerceIn(0L, length)
        seekTarget = target
        onScrub(target - dragFromMs, target, length)
    }

    private fun currentBrightness(): Float {
        val set = window.attributes.screenBrightness
        if (set >= 0f) return set
        // Nothing has been set on this window yet, so the drag picks up from whatever the phone
        // itself is showing. Failing to read that is not worth an error: half is a defensible
        // place to start from.
        val system = runCatching {
            Settings.System.getInt(window.context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrNull() ?: return 0.5f
        return (system / 255f).coerceIn(0f, 1f)
    }

    private fun applyBrightness(value: Float) {
        // Never all the way to zero: a black screen with no visible way to undo the gesture that
        // made it black is a phone that looks broken.
        val level = value.coerceIn(MIN_BRIGHTNESS, 1f)
        window.attributes = window.attributes.also { it.screenBrightness = level }
        onLevel(true, level, true)
    }

    private fun currentVolume(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 0f
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
    }

    private fun applyVolume(value: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        val steps = (value.coerceIn(0f, 1f) * max).roundToInt()
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, steps, 0)
        onLevel(false, steps / max.toFloat(), false)
    }

    companion object {
        private const val DRAG_NONE = 0
        private const val DRAG_BRIGHTNESS = 1
        private const val DRAG_VOLUME = 2
        private const val DRAG_SEEK = 3

        /**
         * Which side of the picture a figure belongs on: each gesture's indicator comes up where
         * the gesture is, so the reading reads as an answer rather than a coincidence.
         */
        const val SIDE_LEFT = -1
        const val SIDE_CENTRE = 0
        const val SIDE_RIGHT = 1

        /**
         * How far a finger travels before the drag is classified, as a fraction of the picture's
         * height. Short enough not to be felt as a delay, long enough that the first noisy sample
         * off the digitiser does not decide between volume and a seek.
         */
        private const val ARM_FRACTION = 0.04f

        /** How much taller than it is wide a drag has to be to count as vertical. */
        private const val VERTICAL_RATIO = 2f

        /** The two sides a vertical drag belongs to, with a dead band between them. */
        private const val LEFT_EDGE = 3f / 7f
        private const val RIGHT_EDGE = 4f / 7f

        /** A drag from one edge of the picture to the other covers three minutes of video. */
        private const val SEEK_RANGE_MS = 180_000f


        /** A drag of 70 per cent of the screen's height covers the whole range. */
        private const val DRAG_RANGE = 0.7f

        private const val MIN_BRIGHTNESS = 0.02f

        /** How far apart the fingers have to travel before it counts as a deliberate pinch. */
        private const val PINCH_THRESHOLD = 0.15f
    }
}
