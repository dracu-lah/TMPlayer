package androidx.media3.ui

import android.content.Context
import android.content.res.ResourceFiles
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.util.app
import android.view.MotionEvent
import android.view.View

// Media3's scrub bar on iOS, drawn and touched as Media3 draws it, so the player's own bar
// (FineTimeBar, which extends it) looks and moves as on Android: the bar centred in its touch
// target, the played run and the scrubber in the played colour, the buffered run ahead of it,
// chapter marks across it, the scrubber growing while it is dragged.

/** Media3's scrub bar. */
interface TimeBar {
    /** What a scrub tells, Media3's. */
    interface OnScrubListener {
        fun onScrubStart(timeBar: TimeBar, position: Long)

        fun onScrubMove(timeBar: TimeBar, position: Long)

        fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean)
    }

    fun addListener(listener: OnScrubListener)

    fun removeListener(listener: OnScrubListener)

    fun setKeyTimeIncrement(time: Long)

    fun setKeyCountIncrement(count: Int)

    fun setPosition(position: Long)

    fun setBufferedPosition(bufferedPosition: Long)

    fun setDuration(duration: Long)

    fun getPreferredUpdateDelay(): Long

    fun setAdGroupTimesMs(adGroupTimesMs: LongArray?, playedAdGroups: BooleanArray?, adGroupCount: Int)
}

open class DefaultTimeBar(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : View(context, attrs, defStyleAttr), TimeBar {
    constructor(context: Context?) : this(context, null, 0)

    constructor(context: Context?, attrs: AttributeSet?) : this(context, attrs, 0)

    private fun dp(value: Float): Int = (value * ResourceFiles.density + 0.5f).toInt()

    private fun size(name: String, default: Float): Int = attrs?.app(name)?.let { ResourceFiles.dimension(it)?.toInt() } ?: dp(default)

    private fun color(name: String, default: Int): Int = attrs?.app(name)?.let { ResourceFiles.color(it) } ?: default

    private val attrs = attrs
    private val barHeight = size("bar_height", 4f)
    private val touchTargetHeight = size("touch_target_height", 26f)
    private val adMarkerWidth = size("ad_marker_width", 4f)
    private val scrubberEnabledSize = size("scrubber_enabled_size", 12f)
    private val scrubberDisabledSize = size("scrubber_disabled_size", 0f)
    private val scrubberDraggedSize = size("scrubber_dragged_size", 16f)
    private val scrubberPadding = (maxOf(scrubberDisabledSize, maxOf(scrubberEnabledSize, scrubberDraggedSize)) + 1) / 2

    private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color("played_color", DEFAULT_PLAYED_COLOR) }
    private val scrubberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color("scrubber_color", DEFAULT_SCRUBBER_COLOR) }
    private val bufferedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color("buffered_color", DEFAULT_BUFFERED_COLOR) }
    private val unplayedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color("unplayed_color", DEFAULT_UNPLAYED_COLOR) }
    private val adMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color("ad_marker_color", DEFAULT_AD_MARKER_COLOR) }
    private val playedAdMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color("played_ad_marker_color", DEFAULT_PLAYED_AD_MARKER_COLOR) }

    private val seekBounds = Rect()
    private val progressBar = Rect()
    private val bufferedBar = Rect()
    private val scrubberBar = Rect()
    private val listeners = LinkedHashSet<TimeBar.OnScrubListener>()

    private var duration = 0L
    private var position = 0L
    private var bufferedPosition = 0L
    private var scrubPosition = 0L
    private var scrubbing = false
    private var keyTimeIncrement = 0L
    private var keyCountIncrement = DEFAULT_INCREMENT_COUNT
    private var adGroupTimesMs: LongArray? = null
    private var playedAdGroups: BooleanArray? = null
    private var adGroupCount = 0
    private var scrubberHidden = false

    override fun addListener(listener: TimeBar.OnScrubListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: TimeBar.OnScrubListener) {
        listeners.remove(listener)
    }

    override fun setKeyTimeIncrement(time: Long) {
        keyTimeIncrement = time
        keyCountIncrement = -1
    }

    override fun setKeyCountIncrement(count: Int) {
        keyCountIncrement = count
        keyTimeIncrement = -1
    }

    override fun setPosition(position: Long) {
        if (this.position == position) return
        this.position = position
        update()
    }

    override fun setBufferedPosition(bufferedPosition: Long) {
        if (this.bufferedPosition == bufferedPosition) return
        this.bufferedPosition = bufferedPosition
        update()
    }

    override fun setDuration(duration: Long) {
        if (this.duration == duration) return
        this.duration = duration
        if (scrubbing && duration == androidx.media3.common.C.TIME_UNSET) stopScrubbing(true)
        update()
    }

    override fun getPreferredUpdateDelay(): Long {
        val barWidth = progressBar.width() / ResourceFiles.density
        return if (barWidth == 0f || duration == 0L || duration == androidx.media3.common.C.TIME_UNSET) Long.MAX_VALUE else (duration / barWidth).toLong()
    }

    override fun setAdGroupTimesMs(adGroupTimesMs: LongArray?, playedAdGroups: BooleanArray?, adGroupCount: Int) {
        this.adGroupCount = adGroupCount
        this.adGroupTimesMs = adGroupTimesMs
        this.playedAdGroups = playedAdGroups
        update()
    }

    fun setPlayedColor(color: Int) {
        playedPaint.color = color
        invalidate()
    }

    fun setScrubberColor(color: Int) {
        scrubberPaint.color = color
        invalidate()
    }

    fun setBufferedColor(color: Int) {
        bufferedPaint.color = color
        invalidate()
    }

    fun setUnplayedColor(color: Int) {
        unplayedPaint.color = color
        invalidate()
    }

    fun setAdMarkerColor(color: Int) {
        adMarkerPaint.color = color
        invalidate()
    }

    fun setPlayedAdMarkerColor(color: Int) {
        playedAdMarkerPaint.color = color
        invalidate()
    }

    fun showScrubber() {
        scrubberHidden = false
        invalidate()
    }

    fun hideScrubber(disableScrubberPadding: Boolean) {
        scrubberHidden = true
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val height = when (heightMode) {
            MeasureSpec.UNSPECIFIED -> touchTargetHeight
            MeasureSpec.EXACTLY -> heightSize
            else -> minOf(touchTargetHeight, heightSize)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        val barY = (height - touchTargetHeight) / 2
        val seekLeft = paddingLeft
        val seekRight = width - paddingRight
        val progressY = barY + (touchTargetHeight - barHeight) / 2
        seekBounds.set(seekLeft, barY, seekRight, barY + touchTargetHeight)
        progressBar.set(seekBounds.left + scrubberPadding, progressY, seekBounds.right - scrubberPadding, progressY + barHeight)
        update()
    }

    override fun onDraw(canvas: Canvas) {
        val save = canvas.save()
        drawTimeBar(canvas)
        drawPlayhead(canvas)
        canvas.restoreToCount(save)
    }

    private fun drawTimeBar(canvas: Canvas) {
        val barHeight = progressBar.height()
        val barTop = progressBar.centerY() - barHeight / 2
        val barBottom = barTop + barHeight
        if (duration <= 0) {
            canvas.drawRect(progressBar.left.toFloat(), barTop.toFloat(), progressBar.right.toFloat(), barBottom.toFloat(), unplayedPaint)
            return
        }
        var bufferedLeft = bufferedBar.left
        val bufferedRight = bufferedBar.right
        val progressLeft = maxOf(maxOf(progressBar.left, bufferedRight), scrubberBar.right)
        if (progressLeft < progressBar.right) {
            canvas.drawRect(progressLeft.toFloat(), barTop.toFloat(), progressBar.right.toFloat(), barBottom.toFloat(), unplayedPaint)
        }
        bufferedLeft = maxOf(bufferedLeft, scrubberBar.right)
        if (bufferedRight > bufferedLeft) {
            canvas.drawRect(bufferedLeft.toFloat(), barTop.toFloat(), bufferedRight.toFloat(), barBottom.toFloat(), bufferedPaint)
        }
        if (scrubberBar.width() > 0) {
            canvas.drawRect(scrubberBar.left.toFloat(), barTop.toFloat(), scrubberBar.right.toFloat(), barBottom.toFloat(), playedPaint)
        }
        val times = adGroupTimesMs ?: return
        val adMarkerOffset = adMarkerWidth / 2
        for (i in 0 until adGroupCount) {
            val time = times[i].coerceIn(0, duration)
            val markerPosition = (progressBar.width() * time / duration).toInt() - adMarkerOffset
            val markerLeft = progressBar.left + minOf(progressBar.width() - adMarkerWidth, maxOf(0, markerPosition))
            val paint = if (playedAdGroups?.getOrNull(i) == true) playedAdMarkerPaint else adMarkerPaint
            canvas.drawRect(markerLeft.toFloat(), barTop.toFloat(), (markerLeft + adMarkerWidth).toFloat(), barBottom.toFloat(), paint)
        }
    }

    private fun drawPlayhead(canvas: Canvas) {
        if (duration <= 0 || scrubberHidden) return
        val playheadX = scrubberBar.right.coerceIn(scrubberBar.left, progressBar.right)
        val playheadY = scrubberBar.centerY()
        val size = if (scrubbing || isFocused) scrubberDraggedSize else if (isEnabled) scrubberEnabledSize else scrubberDisabledSize
        canvas.drawCircle(playheadX.toFloat(), playheadY.toFloat(), size / 2f, scrubberPaint)
    }

    private fun update() {
        bufferedBar.set(progressBar)
        scrubberBar.set(progressBar)
        val newScrubberTime = if (scrubbing) scrubPosition else position
        if (duration > 0) {
            val bufferedPixelWidth = (progressBar.width() * bufferedPosition / duration).toInt()
            bufferedBar.right = minOf(progressBar.left + bufferedPixelWidth, progressBar.right)
            val scrubberPixelPosition = (progressBar.width() * newScrubberTime / duration).toInt()
            scrubberBar.right = minOf(progressBar.left + scrubberPixelPosition, progressBar.right)
        } else {
            bufferedBar.right = progressBar.left
            scrubberBar.right = progressBar.left
        }
        invalidate()
    }

    private fun positionAt(x: Float): Long {
        if (progressBar.width() <= 0) return 0
        val clamped = x.toInt().coerceIn(progressBar.left, progressBar.right)
        return (clamped - progressBar.left).toLong() * duration / progressBar.width()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || duration <= 0) return false
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (seekBounds.contains(x.toInt(), y.toInt())) {
                startScrubbing(positionAt(x))
                return true
            }
            MotionEvent.ACTION_MOVE -> if (scrubbing) {
                scrubPosition = positionAt(x)
                listeners.toList().forEach { it.onScrubMove(this, scrubPosition) }
                update()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (scrubbing) {
                stopScrubbing(event.actionMasked == MotionEvent.ACTION_CANCEL)
                return true
            }
        }
        return false
    }

    private fun startScrubbing(at: Long) {
        scrubPosition = at
        scrubbing = true
        isPressed = true
        parent?.requestDisallowInterceptTouchEvent(true)
        listeners.toList().forEach { it.onScrubStart(this, at) }
        update()
    }

    private fun stopScrubbing(canceled: Boolean) {
        scrubbing = false
        isPressed = false
        parent?.requestDisallowInterceptTouchEvent(false)
        update()
        listeners.toList().forEach { it.onScrubStop(this, scrubPosition, canceled) }
    }

    companion object {
        const val DEFAULT_INCREMENT_COUNT = 20
        const val DEFAULT_PLAYED_COLOR = 0xFFFFFFFF.toInt()
        const val DEFAULT_UNPLAYED_COLOR = 0x33FFFFFF
        const val DEFAULT_BUFFERED_COLOR = 0xCCFFFFFF.toInt()
        const val DEFAULT_SCRUBBER_COLOR = 0xFFFFFFFF.toInt()
        const val DEFAULT_AD_MARKER_COLOR = 0xB2FFFF00.toInt()
        const val DEFAULT_PLAYED_AD_MARKER_COLOR = 0x33FFFF00
    }
}
