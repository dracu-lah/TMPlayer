package com.tmplayer.player

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.tmplayer.i18n.L
import com.tmplayer.i18n.Translator
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.DefaultTimeBar

/**
 * Media3's bar, with YouTube's fine scrubbing under a finger: drag along it as usual, and take the
 * finger up (or down) away from it to travel at a half and then a quarter of the speed
 * ([FineScrub]), so a few seconds can be found on a long video without letting go. [onRate] is told
 * the rate as it changes, and null when the finger lifts, for the hint over the bar.
 *
 * The bar is handed every move at its own height, so Media3's own fine scrub (a third, past 50 dp
 * above it) never stacks on top of this one; and at the position the [FineScrub.Tracker] has
 * reached rather than where the finger is.
 *
 * A remote's Left and Right on the focused bar step it by [PlayerRemoteKeys.TV_JUMP_MS], and a held
 * key ramps the way the bare picture's jump does ([PlayerRemoteKeys.barStepMs]: every fourth repeat,
 * at most one in 150 ms, growing after two and five seconds, held to a share of the length), with
 * stale repeats dropped. Media3 still previews each step and commits one seek about a second after
 * the last key ([onScrubStop][androidx.media3.ui.TimeBar.OnScrubListener.onScrubStop]), so a held
 * key is never a flood of seeks. The fixed increment set from outside ([setKeyTimeIncrement]) stays
 * what TalkBack's scroll actions move by.
 */
@UnstableApi
class FineTimeBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : DefaultTimeBar(context, attrs, defStyleAttr) {

    var onRate: ((Float?) -> Unit)? = null

    private var tracker: FineScrub.Tracker? = null
    private var lastRate: Float? = null

    /** The increment set from outside, for TalkBack's scroll actions; a key's step is set around it. */
    private var accessibilityIncrementMs = 0L
    private var durationMs = 0L
    private var lastKeyStepAt = 0L

    override fun setKeyTimeIncrement(time: Long) {
        accessibilityIncrementMs = time
        super.setKeyTimeIncrement(time)
    }

    override fun setDuration(duration: Long) {
        durationMs = duration
        super.setDuration(duration)
    }

    private var positionMs = 0L

    override fun setPosition(position: Long) {
        positionMs = position
        super.setPosition(position)
    }

    /**
     * What TalkBack reads (WP-17): the app's own name for the bar and "12:03 of 1:42:10" as its
     * state, in place of Media3's bare position. Worked out when a reader asks, never on a tick, so
     * a playing video sends no stream of state changes. Below Android 11 the state rides in the name.
     */
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        val clock = Translator.messages.formatter
        val state = if (durationMs > 0) L.playerPositionOf(clock.clock(positionMs), clock.clock(durationMs)) else clock.clock(positionMs)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            info.contentDescription = L.playerSeekBar
            info.stateDescription = state
        } else {
            info.contentDescription = L.playerSeekBar + ", " + state
        }
    }

    /**
     * TalkBack's scroll forward and back move by the 10 s of the skip buttons ([Skip.BUTTON_MS]), not
     * by the 30 s stride the keys keep. Media3 reads the increment as it handles the action, so it is
     * swapped for that call alone.
     */
    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        if (action != ACTION_SCROLL_FORWARD && action != ACTION_SCROLL_BACKWARD) return super.performAccessibilityAction(action, arguments)
        super.setKeyTimeIncrement(Skip.BUTTON_MS)
        return try {
            super.performAccessibilityAction(action, arguments)
        } finally {
            if (accessibilityIncrementMs > 0L) super.setKeyTimeIncrement(accessibilityIncrementMs)
        }
    }

    /**
     * True on a television, where Left and Right step 10 s and ramp ([onKeyDown]). Elsewhere (a
     * phone's or tablet's keyboard) the keys keep Media3's stride by the fixed increment, as before.
     */
    var remoteSteps = false

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!remoteSteps || !isEnabled || (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT)) {
            return super.onKeyDown(keyCode, event)
        }
        val at = SystemClock.uptimeMillis()
        val step = PlayerRemoteKeys.barStepMs(event.repeatCount, PlayerRemoteKeys.TV_JUMP_MS, event.eventTime, at, lastKeyStepAt, durationMs)
        // A repeat between steps is still the bar's: passed on, it would walk the focus off the bar.
        if (step <= 0L) return true
        lastKeyStepAt = at
        super.setKeyTimeIncrement(step)
        return try {
            super.onKeyDown(keyCode, event)
        } finally {
            if (accessibilityIncrementMs > 0L) super.setKeyTimeIncrement(accessibilityIncrementMs)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return super.onTouchEvent(event)
        val density = resources.displayMetrics.density
        val middle = height / 2f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracker = FineScrub.Tracker(event.x)
                lastRate = null
                return super.onTouchEvent(event)
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val t = tracker ?: return super.onTouchEvent(event)
                t.move(event.x, (event.y - middle) / density)
                t.clamp(0f, width.toFloat())
                if (event.actionMasked == MotionEvent.ACTION_MOVE) report(t.rate)
                val handled = passOn(event, t.x, middle)
                if (event.actionMasked == MotionEvent.ACTION_UP) finish()
                return handled
            }
            MotionEvent.ACTION_CANCEL -> {
                val handled = super.onTouchEvent(event)
                finish()
                return handled
            }
        }
        return super.onTouchEvent(event)
    }

    private fun passOn(event: MotionEvent, x: Float, y: Float): Boolean {
        val moved = MotionEvent.obtain(event)
        moved.setLocation(x, y)
        return try {
            super.onTouchEvent(moved)
        } finally {
            moved.recycle()
        }
    }

    private fun report(rate: Float) {
        if (rate == lastRate) return
        lastRate = rate
        onRate?.invoke(rate)
    }

    private fun finish() {
        tracker = null
        if (lastRate != null) onRate?.invoke(null)
        lastRate = null
    }
}

/** Android's accessibility scroll actions (AccessibilityNodeInfo.ACTION_SCROLL_FORWARD and BACKWARD). */
private const val ACTION_SCROLL_FORWARD = 4096
private const val ACTION_SCROLL_BACKWARD = 8192
