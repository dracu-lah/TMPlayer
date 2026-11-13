package com.tmplayer.player

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The two (or more) finger pinch, read straight from the touch events.
 *
 * Stands in for [android.view.ScaleGestureDetector], which refuses to run below a minimum span of
 * 27 mm (510 px at 480 dpi) and wants the span to move a further 2 touch slops before it begins. On
 * a pinch out that costs nothing, the fingers spread without limit. On a pinch in it means a
 * pinch that starts closer than about 37 mm does nothing at all and any pinch stops the moment the
 * fingers come within 27 mm of each other, so an enlarged picture needed pinch after pinch to come
 * back to 100 per cent, and from a normal thumb and finger distance never did (2026-11-14).
 *
 * The same numbers as the detector otherwise: the span is twice the fingers' mean distance from
 * their centre, a step is the span over the last step's span, and a step the listener does not take
 * (returns false) is not consumed, so its change adds up into the next one. One slop of travel in
 * the span begins a pinch, so two fingers dragging a picture along do not wobble it.
 */
class PinchTracker(
    context: Context,
    /** The pinch began, its fingers centred on this point (window pixels). */
    private val onBegin: (focusX: Float, focusY: Float) -> Unit,
    /**
     * One step: the span over the last step's span, the fingers' centre, and how far the centre
     * moved since the last event. True when the step was taken, so the next measures from here.
     */
    private val onStep: (factor: Float, focusX: Float, focusY: Float, dx: Float, dy: Float) -> Boolean,
    /** The pinch is over: a finger lifted, the touch ended or was cancelled. */
    private val onEnd: () -> Unit,
) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    /** Below this the fingers are as good as touching, and a span says nothing about the hand. */
    private val floor = FLOOR_DP * context.resources.displayMetrics.density

    private var active = false
    private var initialSpan = 0f
    private var prevSpan = 0f
    private var prevFocusX = 0f
    private var prevFocusY = 0f

    /** True from [onBegin] to [onEnd]. */
    val inProgress: Boolean get() = active

    /** Ends a pinch in progress without waiting for a lift, e.g. when a sheet takes the touch. */
    fun cancel() {
        finish()
        initialSpan = 0f
    }

    fun onTouchEvent(event: MotionEvent) {
        val action = event.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                finish()
                initialSpan = 0f
                return
            }
        }
        val skip = if (action == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
        var count = 0
        var sumX = 0f
        var sumY = 0f
        for (i in 0 until event.pointerCount) {
            if (i == skip) continue
            count++
            sumX += event.getX(i)
            sumY += event.getY(i)
        }
        if (count < 2) {
            finish()
            initialSpan = 0f
            return
        }
        val focusX = sumX / count
        val focusY = sumY / count
        var devX = 0f
        var devY = 0f
        for (i in 0 until event.pointerCount) {
            if (i == skip) continue
            devX += abs(event.getX(i) - focusX)
            devY += abs(event.getY(i) - focusY)
        }
        val span = hypot(2f * devX / count, 2f * devY / count)

        when (action) {
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                // The set of fingers changed, so the centre and the span jump: measure from here.
                prevSpan = span
                initialSpan = span
                prevFocusX = focusX
                prevFocusY = focusY
            }
            MotionEvent.ACTION_MOVE -> {
                if (!active) {
                    if (span >= floor && abs(span - initialSpan) > slop) {
                        active = true
                        prevSpan = span
                        prevFocusX = focusX
                        prevFocusY = focusY
                        onBegin(focusX, focusY)
                    }
                    return
                }
                if (prevSpan <= 0f || span <= 0f) return
                val taken = onStep(span / prevSpan, focusX, focusY, focusX - prevFocusX, focusY - prevFocusY)
                if (taken) prevSpan = span
                prevFocusX = focusX
                prevFocusY = focusY
            }
        }
    }

    private fun finish() {
        if (!active) return
        active = false
        onEnd()
    }

    private companion object {
        /** Fingers closer than this are not a pinch: about 6 mm. */
        const val FLOOR_DP = 10f
    }
}
