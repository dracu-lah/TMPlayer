package com.tmplayer.player

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.Window
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The three gestures a phone viewer expects from a video player, and nothing else.
 *
 * A remote has buttons for all of this, so none of it exists on the TV. A phone has a bare picture
 * and a thumb, and the conventions are old enough by now that a player without them reads as
 * broken: double tap the sides to jump, drag the left half for brightness, drag the right half for
 * volume. Everything is worked out from the view it is attached to, so nothing is carried between
 * one drag and the next beyond the drag in progress.
 *
 * Attached as a touch listener that never consumes the event, so Media3's own controller still
 * gets the single tap that raises and hides it.
 */
class PlayerGestures(
    context: Context,
    private val window: Window,
    private val onSkip: (Long) -> Unit,
    private val onFeedback: (String) -> Unit,
) {

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** [DRAG_NONE] between drags; set on the first scroll of a gesture and read for the rest. */
    private var dragKind = DRAG_NONE

    /** The brightness or volume the finger went down on, as a fraction of the full range. */
    private var dragFrom = 0f

    private var viewWidth = 0
    private var viewHeight = 0

    private val detector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {

            override fun onDown(event: MotionEvent): Boolean = true

            override fun onDoubleTap(event: MotionEvent): Boolean {
                val third = viewWidth / 3f
                if (third <= 0f) return false
                when {
                    event.x < third -> {
                        onSkip(-SKIP_MS)
                        onFeedback("- ${SKIP_MS / 1000} s")
                    }
                    event.x > viewWidth - third -> {
                        onSkip(SKIP_MS)
                        onFeedback("+ ${SKIP_MS / 1000} s")
                    }
                    // The middle third belongs to the controller: a double tap there is two
                    // ordinary taps, which raise the transport row and hide it again.
                    else -> return false
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
                if (dragKind == DRAG_NONE && !begin(from, distanceX, distanceY)) return false

                // Measured from where the finger went down rather than summed step by step, so a
                // drag that wanders and comes back lands where it started.
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

    @SuppressLint("ClickableViewAccessibility")
    fun attach(view: View) {
        view.setOnTouchListener { touched, event ->
            viewWidth = touched.width
            viewHeight = touched.height
            detector.onTouchEvent(event)
            val finished = event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            if (finished) dragKind = DRAG_NONE
            // Never consumed: the controller still owns the tap that raises it.
            false
        }
    }

    /**
     * Decides what a drag is for, once, from its first few pixels.
     *
     * A gesture that starts out mostly sideways is not a volume drag that went wrong, it is
     * someone reaching for something else, so it is left alone for the rest of its life.
     */
    private fun begin(start: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
        if (abs(distanceY) <= abs(distanceX)) return false
        if (start.x < viewWidth / 2f) {
            dragKind = DRAG_BRIGHTNESS
            dragFrom = currentBrightness()
        } else {
            dragKind = DRAG_VOLUME
            dragFrom = currentVolume()
        }
        return true
    }

    private fun currentBrightness(): Float {
        val set = window.attributes.screenBrightness
        if (set >= 0f) return set
        // Nothing has been set on this window yet, so the drag has to pick up from whatever the
        // phone itself is showing. Failing to read that is not worth an error: half is a
        // defensible place to start from.
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
        onFeedback("Brightness ${(level * 100).roundToInt()}%")
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
        onFeedback("Volume ${(steps * 100) / max}%")
    }

    private companion object {
        const val DRAG_NONE = 0
        const val DRAG_BRIGHTNESS = 1
        const val DRAG_VOLUME = 2

        /** The same jump the transport row's skip buttons make, so the two agree. */
        const val SKIP_MS = TvPlayerGlue.SKIP_MS

        /** A drag of 70 per cent of the screen's height covers the whole range. */
        const val DRAG_RANGE = 0.7f

        const val MIN_BRIGHTNESS = 0.02f
    }
}
