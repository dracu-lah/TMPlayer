package android.view

import android.content.Context
import android.content.res.ResourceFiles
import kotlin.math.abs
import kotlin.math.sqrt

/** Android's timings and distances for touch, ViewConfiguration's defaults. */
object ViewConfiguration {
    fun getTapTimeout(): Int = 100

    fun getLongPressTimeout(): Int = 400

    fun getDoubleTapTimeout(): Int = 300

    internal const val DOUBLE_TAP_MIN_TIME = 40

    fun get(context: Context?): Scaled = Scaled

    /** The distances in pixels for this screen, from Android's dp. */
    object Scaled {
        private fun px(dp: Float): Int = (dp * ResourceFiles.density + 0.5f).toInt()

        val scaledTouchSlop: Int get() = px(8f)
        val scaledDoubleTapSlop: Int get() = px(100f)
        val scaledMinimumFlingVelocity: Int get() = px(50f)
        val scaledMaximumFlingVelocity: Int get() = px(8000f)

        fun getScaledTouchSlop(): Int = scaledTouchSlop

        fun getScaledDoubleTapSlop(): Int = scaledDoubleTapSlop

        fun getScaledMinimumFlingVelocity(): Int = scaledMinimumFlingVelocity

        fun getScaledMaximumFlingVelocity(): Int = scaledMaximumFlingVelocity
    }
}

/**
 * Taps, double taps, long presses, scrolls and flings from a stream of touches, Android's
 * GestureDetector: the same timeouts and slops, the same callbacks in the same order.
 */
class GestureDetector(context: Context?, private val listener: OnGestureListener) {
    constructor(listener: OnGestureListener) : this(null, listener)

    constructor(context: Context?, listener: OnGestureListener, @Suppress("UNUSED_PARAMETER") handler: android.os.Handler?) : this(context, listener)

    interface OnGestureListener {
        fun onDown(e: MotionEvent): Boolean

        fun onShowPress(e: MotionEvent)

        fun onSingleTapUp(e: MotionEvent): Boolean

        fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean

        fun onLongPress(e: MotionEvent)

        fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean
    }

    interface OnDoubleTapListener {
        fun onSingleTapConfirmed(e: MotionEvent): Boolean

        fun onDoubleTap(e: MotionEvent): Boolean

        fun onDoubleTapEvent(e: MotionEvent): Boolean
    }

    interface OnContextClickListener {
        fun onContextClick(e: MotionEvent): Boolean
    }

    /** Every callback answered with "not handled", to override the ones wanted, Android's. */
    open class SimpleOnGestureListener : OnGestureListener, OnDoubleTapListener, OnContextClickListener {
        override fun onDown(e: MotionEvent): Boolean = false

        override fun onShowPress(e: MotionEvent) {}

        override fun onSingleTapUp(e: MotionEvent): Boolean = false

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean = false

        override fun onLongPress(e: MotionEvent) {}

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean = false

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean = false

        override fun onDoubleTap(e: MotionEvent): Boolean = false

        override fun onDoubleTapEvent(e: MotionEvent): Boolean = false

        override fun onContextClick(e: MotionEvent): Boolean = false
    }

    private var doubleTapListener: OnDoubleTapListener? = listener as? OnDoubleTapListener
    private val config = ViewConfiguration.get(context)
    private val touchSlopSquare = config.scaledTouchSlop.let { it * it }
    private val doubleTapSlopSquare = config.scaledDoubleTapSlop.let { it * it }
    private val doubleTapTouchSlopSquare = touchSlopSquare
    private val minimumFlingVelocity = config.scaledMinimumFlingVelocity.toFloat()
    private val maximumFlingVelocity = config.scaledMaximumFlingVelocity.toFloat()

    var isLongpressEnabled: Boolean = true

    fun setIsLongpressEnabled(enabled: Boolean) {
        isLongpressEnabled = enabled
    }

    fun setOnDoubleTapListener(listener: OnDoubleTapListener?) {
        doubleTapListener = listener
    }

    private val handler = android.os.Handler()
    private var stillDown = false
    private var deferConfirmSingleTap = false
    private var inLongPress = false
    private var alwaysInTapRegion = false
    private var alwaysInBiggerTapRegion = false
    private var ignoreNextUpEvent = false
    private var isDoubleTapping = false
    private var currentDownEvent: MotionEvent? = null
    private var previousUpEvent: MotionEvent? = null
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var downFocusX = 0f
    private var downFocusY = 0f
    private val velocity = VelocityTracker.obtain()

    private val showPress = java.lang.Runnable { currentDownEvent?.let(listener::onShowPress) }
    private val longPress = java.lang.Runnable { dispatchLongPress() }
    private val tap = java.lang.Runnable {
        val down = currentDownEvent
        if (down != null && doubleTapListener != null) {
            if (!stillDown) doubleTapListener?.onSingleTapConfirmed(down) else deferConfirmSingleTap = true
        }
    }

    fun onTouchEvent(ev: MotionEvent): Boolean {
        val action = ev.actionMasked
        velocity.addMovement(ev)
        val pointerUp = action == MotionEvent.ACTION_POINTER_UP
        val skipIndex = if (pointerUp) ev.actionIndex else -1
        var sumX = 0f
        var sumY = 0f
        var count = 0
        for (i in 0 until ev.pointerCount) {
            if (i == skipIndex) continue
            sumX += ev.getX(i)
            sumY += ev.getY(i)
            count++
        }
        val focusX = if (count > 0) sumX / count else ev.x
        val focusY = if (count > 0) sumY / count else ev.y
        var handled = false
        when (action) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                lastFocusX = focusX
                downFocusX = focusX
                lastFocusY = focusY
                downFocusY = focusY
                cancelTaps()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                lastFocusX = focusX
                downFocusX = focusX
                lastFocusY = focusY
                downFocusY = focusY
            }
            MotionEvent.ACTION_DOWN -> {
                if (doubleTapListener != null) {
                    val hadTapMessage = handler.hasCallbacks(tap)
                    if (hadTapMessage) handler.removeCallbacks(tap)
                    val down = currentDownEvent
                    val up = previousUpEvent
                    if (down != null && up != null && hadTapMessage && isConsideredDoubleTap(down, up, ev)) {
                        isDoubleTapping = true
                        handled = handled or (doubleTapListener?.onDoubleTap(down) == true)
                        handled = handled or (doubleTapListener?.onDoubleTapEvent(ev) == true)
                    } else {
                        handler.postDelayed(tap, ViewConfiguration.getDoubleTapTimeout().toLong())
                    }
                }
                lastFocusX = focusX
                downFocusX = focusX
                lastFocusY = focusY
                downFocusY = focusY
                currentDownEvent = MotionEvent.obtain(ev)
                alwaysInTapRegion = true
                alwaysInBiggerTapRegion = true
                stillDown = true
                inLongPress = false
                deferConfirmSingleTap = false
                if (isLongpressEnabled) {
                    handler.removeCallbacks(longPress)
                    handler.postDelayed(longPress, (ViewConfiguration.getTapTimeout() + ViewConfiguration.getLongPressTimeout()).toLong())
                }
                handler.postDelayed(showPress, ViewConfiguration.getTapTimeout().toLong())
                handled = handled or listener.onDown(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                if (inLongPress) return false
                val scrollX = lastFocusX - focusX
                val scrollY = lastFocusY - focusY
                if (isDoubleTapping) {
                    handled = handled or (doubleTapListener?.onDoubleTapEvent(ev) == true)
                } else if (alwaysInTapRegion) {
                    val dx = (focusX - downFocusX).toInt()
                    val dy = (focusY - downFocusY).toInt()
                    val distance = dx * dx + dy * dy
                    if (distance > touchSlopSquare) {
                        handled = listener.onScroll(currentDownEvent, ev, scrollX, scrollY)
                        lastFocusX = focusX
                        lastFocusY = focusY
                        alwaysInTapRegion = false
                        handler.removeCallbacks(tap)
                        handler.removeCallbacks(showPress)
                        handler.removeCallbacks(longPress)
                    }
                    if (distance > doubleTapTouchSlopSquare) alwaysInBiggerTapRegion = false
                } else if (abs(scrollX) >= 1 || abs(scrollY) >= 1) {
                    handled = listener.onScroll(currentDownEvent, ev, scrollX, scrollY)
                    lastFocusX = focusX
                    lastFocusY = focusY
                }
            }
            MotionEvent.ACTION_UP -> {
                stillDown = false
                val currentUp = MotionEvent.obtain(ev)
                if (isDoubleTapping) {
                    handled = handled or (doubleTapListener?.onDoubleTapEvent(ev) == true)
                } else if (inLongPress) {
                    handler.removeCallbacks(tap)
                    inLongPress = false
                } else if (alwaysInTapRegion && !ignoreNextUpEvent) {
                    handled = listener.onSingleTapUp(ev)
                    if (deferConfirmSingleTap) doubleTapListener?.onSingleTapConfirmed(ev)
                } else if (!ignoreNextUpEvent) {
                    velocity.computeCurrentVelocity(1000, maximumFlingVelocity)
                    val vx = velocity.xVelocity
                    val vy = velocity.yVelocity
                    if (abs(vy) > minimumFlingVelocity || abs(vx) > minimumFlingVelocity) {
                        handled = listener.onFling(currentDownEvent, ev, vx, vy)
                    }
                }
                previousUpEvent = currentUp
                velocity.clear()
                isDoubleTapping = false
                deferConfirmSingleTap = false
                ignoreNextUpEvent = false
                handler.removeCallbacks(showPress)
                handler.removeCallbacks(longPress)
            }
            MotionEvent.ACTION_CANCEL -> cancel()
        }
        return handled
    }

    private fun cancel() {
        handler.removeCallbacks(showPress)
        handler.removeCallbacks(longPress)
        handler.removeCallbacks(tap)
        velocity.clear()
        isDoubleTapping = false
        stillDown = false
        alwaysInTapRegion = false
        alwaysInBiggerTapRegion = false
        deferConfirmSingleTap = false
        inLongPress = false
        ignoreNextUpEvent = false
    }

    private fun cancelTaps() {
        handler.removeCallbacks(showPress)
        handler.removeCallbacks(longPress)
        handler.removeCallbacks(tap)
        isDoubleTapping = false
        alwaysInTapRegion = false
        alwaysInBiggerTapRegion = false
        deferConfirmSingleTap = false
        inLongPress = false
        ignoreNextUpEvent = false
    }

    private fun isConsideredDoubleTap(firstDown: MotionEvent, firstUp: MotionEvent, secondDown: MotionEvent): Boolean {
        if (!alwaysInBiggerTapRegion) return false
        val deltaTime = secondDown.eventTime - firstUp.eventTime
        if (deltaTime > ViewConfiguration.getDoubleTapTimeout() || deltaTime < ViewConfiguration.DOUBLE_TAP_MIN_TIME) return false
        val dx = firstDown.x.toInt() - secondDown.x.toInt()
        val dy = firstDown.y.toInt() - secondDown.y.toInt()
        return dx * dx + dy * dy < doubleTapSlopSquare
    }

    private fun dispatchLongPress() {
        handler.removeCallbacks(tap)
        deferConfirmSingleTap = false
        inLongPress = true
        currentDownEvent?.let(listener::onLongPress)
    }
}

/** How fast a touch moves, Android's VelocityTracker, from the last moments of its movement. */
class VelocityTracker private constructor() {
    private val samples = ArrayDeque<Triple<Long, Float, Float>>()

    var xVelocity: Float = 0f
        private set
    var yVelocity: Float = 0f
        private set

    fun addMovement(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) samples.clear()
        samples.addLast(Triple(event.eventTime, event.x, event.y))
        while (samples.size > 1 && event.eventTime - samples.first().first > HORIZON_MS) samples.removeFirst()
    }

    fun computeCurrentVelocity(units: Int) = computeCurrentVelocity(units, Float.MAX_VALUE)

    fun computeCurrentVelocity(units: Int, maxVelocity: Float) {
        val first = samples.firstOrNull()
        val last = samples.lastOrNull()
        if (first == null || last == null || last.first == first.first) {
            xVelocity = 0f
            yVelocity = 0f
            return
        }
        val seconds = (last.first - first.first) / 1000f
        xVelocity = ((last.second - first.second) / seconds * units / 1000f).coerceIn(-maxVelocity, maxVelocity)
        yVelocity = ((last.third - first.third) / seconds * units / 1000f).coerceIn(-maxVelocity, maxVelocity)
    }

    fun getXVelocity(): Float = xVelocity

    fun getYVelocity(): Float = yVelocity

    fun clear() {
        samples.clear()
        xVelocity = 0f
        yVelocity = 0f
    }

    fun recycle() = clear()

    companion object {
        private const val HORIZON_MS = 100L

        fun obtain(): VelocityTracker = VelocityTracker()
    }
}

/**
 * Pinches from two or more fingers, Android's ScaleGestureDetector: the span between the fingers
 * around their focus point, a scale from one event to the next, begin and end as the span grows
 * past the minimum and the fingers lift.
 */
class ScaleGestureDetector(context: Context?, private val listener: OnScaleGestureListener) {
    interface OnScaleGestureListener {
        fun onScale(detector: ScaleGestureDetector): Boolean

        fun onScaleBegin(detector: ScaleGestureDetector): Boolean

        fun onScaleEnd(detector: ScaleGestureDetector)
    }

    open class SimpleOnScaleGestureListener : OnScaleGestureListener {
        override fun onScale(detector: ScaleGestureDetector): Boolean = false

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = true

        override fun onScaleEnd(detector: ScaleGestureDetector) {}
    }

    private val spanSlop = ViewConfiguration.get(context).scaledTouchSlop * 2
    private val minSpan = (27f * ResourceFiles.density).toInt()

    var focusX: Float = 0f
        private set
    var focusY: Float = 0f
        private set
    var currentSpan: Float = 0f
        private set
    var previousSpan: Float = 0f
        private set
    var isInProgress: Boolean = false
        private set
    var timeDelta: Long = 0
        private set

    /** Android's double-tap-and-drag to zoom; the player turns it off. */
    var isQuickScaleEnabled: Boolean = false

    var isStylusScaleEnabled: Boolean = false

    private var initialSpan = 0f
    private var currentTime = 0L
    private var previousTime = 0L

    val scaleFactor: Float get() = if (previousSpan > 0f) currentSpan / previousSpan else 1f

    fun onTouchEvent(event: MotionEvent): Boolean {
        currentTime = event.eventTime
        val action = event.actionMasked
        val streamComplete = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
        if (action == MotionEvent.ACTION_DOWN || streamComplete) {
            if (isInProgress) {
                listener.onScaleEnd(this)
                isInProgress = false
                initialSpan = 0f
            }
            if (streamComplete) return true
        }
        val configChanged = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_POINTER_DOWN
        val pointerUp = action == MotionEvent.ACTION_POINTER_UP
        val skipIndex = if (pointerUp) event.actionIndex else -1
        var sumX = 0f
        var sumY = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i == skipIndex) continue
            sumX += event.getX(i)
            sumY += event.getY(i)
            count++
        }
        if (count == 0) return true
        val fx = sumX / count
        val fy = sumY / count
        var devX = 0f
        var devY = 0f
        for (i in 0 until event.pointerCount) {
            if (i == skipIndex) continue
            devX += abs(event.getX(i) - fx)
            devY += abs(event.getY(i) - fy)
        }
        val spanX = devX / count * 2
        val spanY = devY / count * 2
        val span = sqrt(spanX * spanX + spanY * spanY)
        val wasInProgress = isInProgress
        focusX = fx
        focusY = fy
        if (isInProgress && (span < minSpan || configChanged)) {
            listener.onScaleEnd(this)
            isInProgress = false
            initialSpan = span
        }
        if (configChanged) {
            previousSpan = span
            currentSpan = span
            initialSpan = span
        }
        if (!isInProgress && span >= minSpan && (wasInProgress || abs(span - initialSpan) > spanSlop)) {
            previousSpan = span
            currentSpan = span
            previousTime = currentTime
            isInProgress = listener.onScaleBegin(this)
        }
        if (action == MotionEvent.ACTION_MOVE) {
            currentSpan = span
            var updatePrevious = true
            if (isInProgress) updatePrevious = listener.onScale(this)
            if (updatePrevious) {
                previousSpan = currentSpan
                timeDelta = currentTime - previousTime
                previousTime = currentTime
            }
        }
        return true
    }
}
