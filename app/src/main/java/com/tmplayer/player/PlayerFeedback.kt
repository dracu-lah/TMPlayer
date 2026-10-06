package com.tmplayer.player

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.tmplayer.R
import com.tmplayer.i18n.L
import com.tmplayer.i18n.Translator
import kotlin.math.roundToInt

/**
 * Everything a touch on the phone's picture puts on screen while it is happening.
 *
 * Each gesture answers where the finger is and for as long as the gesture lasts: play and pause
 * flash a big glyph in the middle, a double tap on a side draws YouTube's half-moon with chevrons
 * and a running total, a held finger keeps a speed pill up until it lifts, a vertical drag fills a
 * level bar on its own side, a sideways drag shows where it would land. The older single text chip
 * ([PlayerActivity.showGestureFeedback]) stays for the television and for labels that are not a
 * gesture's answer.
 *
 * Built in code and laid over the controls, under the track pickers. Phone only.
 */
class PlayerFeedback(private val root: FrameLayout, insertBelow: View?) {

    private val context: Context = root.context
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Float) = (value * density).roundToInt()

    // Declared before the views below, whose initialisers read it.
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    /** Set from the viewer's preference; the system's own haptics switch is honoured regardless. */
    var hapticsEnabled = true

    private val flash = CenterFlash(context)
    private val ripple = SeekRipple(context)
    private val hold = pill().apply {
        layoutParams = FrameLayout.LayoutParams(wrap, wrap, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            .apply { topMargin = dp(84f) }
    }
    private val level = LevelPill(context)
    private val scrub = ScrubCard(context)
    private val spinner = ProgressBar(context).apply {
        isIndeterminate = true
        indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
    }

    init {
        val at = insertBelow?.let { root.indexOfChild(it) }?.takeIf { it >= 0 } ?: root.childCount
        // Inserted in reverse so they land in this order, bottom to top.
        listOf(spinner, scrub, level, hold, ripple, flash).forEach { view ->
            if (view.layoutParams == null) {
                view.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
            view.visibility = View.GONE
            root.addView(view, at)
        }
        flash.layoutParams = FrameLayout.LayoutParams(dp(88f), dp(88f), Gravity.CENTER)
        level.layoutParams = FrameLayout.LayoutParams(dp(36f), dp(196f), Gravity.CENTER_VERTICAL)
        scrub.layoutParams = FrameLayout.LayoutParams(wrap, wrap, Gravity.CENTER)
        spinner.layoutParams = FrameLayout.LayoutParams(dp(56f), dp(56f), Gravity.CENTER)
    }

    // ---- waiting -----------------------------------------------------------------------------

    private var waiting = false
    private var controlsUp = false
    private val showSpinner = Runnable { spinner.visibility = View.VISIBLE }

    /** A stall mid-play. Shown a beat late, so the brief wait after each jump does not flicker. */
    fun buffering(on: Boolean) {
        waiting = on
        renderSpinner()
    }

    /** While the row is up its play disc spins instead, in the same spot. */
    fun controlsShown(up: Boolean) {
        controlsUp = up
        renderSpinner()
    }

    private fun renderSpinner() {
        spinner.removeCallbacks(showSpinner)
        if (!waiting || controlsUp) {
            spinner.visibility = View.GONE
        } else if (spinner.visibility != View.VISIBLE) {
            spinner.postDelayed(showSpinner, SPINNER_DELAY_MS)
        }
    }

    // ---- play and pause --------------------------------------------------------------------

    /** The glyph for what just happened: [playing] true means the video has just started. */
    fun flashPlayPause(playing: Boolean) {
        haptic(confirm = true)
        flash.show(playing)
    }

    // ---- double tap --------------------------------------------------------------------------

    /**
     * One more step in a run of double taps. [totalSeconds] is the run's whole distance so far, so
     * the label counts up 10, 20, 30 as the taps keep coming.
     */
    fun seekStep(zone: TapZone, totalSeconds: Long, x: Float, y: Float) {
        haptic(confirm = false)
        ripple.step(zone, totalSeconds, x, y)
    }

    // ---- hold --------------------------------------------------------------------------------

    fun holdStarted(speed: Float) {
        haptic(confirm = false)
        hold.text = "${TouchPrefs.holdLabel(speed)}   ▶▶"
        hold.animate().cancel()
        hold.alpha = 1f
        hold.visibility = View.VISIBLE
    }

    fun holdEnded() {
        if (hold.visibility != View.VISIBLE) return
        hold.animate().alpha(0f).setDuration(FADE_MS).withEndAction { hold.visibility = View.GONE }.start()
    }

    // ---- brightness and volume ---------------------------------------------------------------

    /** [fraction] 0 to 1; the pill sits on [left]'s side, the side the finger is dragging. */
    fun level(left: Boolean, fraction: Float, brightness: Boolean) {
        (level.layoutParams as FrameLayout.LayoutParams).let { params ->
            val gravity = Gravity.CENTER_VERTICAL or if (left) Gravity.START else Gravity.END
            if (params.gravity != gravity) {
                params.gravity = gravity
                params.marginStart = dp(SIDE_MARGIN_DP)
                params.marginEnd = dp(SIDE_MARGIN_DP)
                level.layoutParams = params
            }
        }
        level.show(fraction, brightness)
    }

    // ---- sideways scrub ----------------------------------------------------------------------

    fun scrub(deltaMs: Long, targetMs: Long, durationMs: Long) = scrub.show(deltaMs, targetMs, durationMs)

    fun scrubEnded() = scrub.hideSoon()

    /** Drops everything at once, for picture in picture and the lock. */
    fun clear() {
        waiting = false
        spinner.removeCallbacks(showSpinner)
        listOf(flash, ripple, hold, level, scrub, spinner).forEach {
            it.animate().cancel()
            it.visibility = View.GONE
        }
        ripple.stop()
    }

    private fun haptic(confirm: Boolean) {
        if (!hapticsEnabled) return
        val constant = when {
            confirm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> HapticFeedbackConstants.CONFIRM
            confirm -> HapticFeedbackConstants.VIRTUAL_KEY
            else -> HapticFeedbackConstants.CLOCK_TICK
        }
        root.performHapticFeedback(constant)
    }

    private fun pill(): TextView = TextView(context).apply {
        background = ContextCompat.getDrawable(context, R.drawable.bg_player_chip)
        setPadding(dp(20f), dp(10f), dp(20f), dp(10f))
        setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
    }

    // =========================================================================================

    /** The big disc in the middle with the glyph for what just happened, grown in and faded out. */
    private inner class CenterFlash(context: Context) : FrameLayout(context) {
        private val icon = PlayPauseIcon(context)

        init {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x73000000)
            }
            addView(icon, LayoutParams(dp(44f), dp(44f), Gravity.CENTER))
        }

        fun show(playing: Boolean) {
            // The glyph names the new state the way YouTube's does: a triangle as it starts.
            icon.setShowsPlay(playing, animate = false)
            animate().cancel()
            visibility = VISIBLE
            alpha = 1f
            scaleX = 0.8f
            scaleY = 0.8f
            animate().scaleX(1f).scaleY(1f).alpha(0f).setDuration(FLASH_MS)
                .withEndAction { visibility = GONE }
                .start()
        }
    }

    /**
     * YouTube's double tap answer, re-implemented: a pale half-moon over that third of the
     * picture, a ripple from the finger, three chevrons lighting in turn, and the run's total.
     */
    private inner class SeekRipple(context: Context) : View(context) {
        private val moon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x38FFFFFF }
        private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val chevron = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 14f * density
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(4f * density, 0f, 0f, 0x80000000.toInt())
        }
        private val oval = RectF()
        private val path = Path()

        private var zone = TapZone.Right
        private var seconds = 0L
        private var tapX = 0f
        private var tapY = 0f
        private var tappedAt = 0L
        private var ticker: ValueAnimator? = null
        private val hide = Runnable {
            animate().alpha(0f).setDuration(FADE_MS).withEndAction {
                visibility = GONE
                stop()
            }.start()
        }

        fun step(side: TapZone, totalSeconds: Long, x: Float, y: Float) {
            zone = side
            seconds = totalSeconds
            tapX = x
            tapY = y
            tappedAt = SystemClock.uptimeMillis()
            removeCallbacks(hide)
            animate().cancel()
            alpha = 1f
            visibility = VISIBLE
            if (ticker == null) {
                ticker = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = CHEVRON_CYCLE_MS
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener { invalidate() }
                    start()
                }
            }
            postDelayed(hide, SeekCounter.WINDOW_MS)
            invalidate()
        }

        fun stop() {
            ticker?.cancel()
            ticker = null
            removeCallbacks(hide)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val left = zone == TapZone.Left
            val side = w * TapZone.SIDE_FRACTION
            val save = canvas.save()
            // The half-moon: an oval much taller than the screen, so only its gentle inner curve
            // shows, the edge of the frame cutting off the rest.
            if (left) {
                oval.set(-side * 0.7f, -h * 0.3f, side, h * 1.3f)
                canvas.clipRect(0f, 0f, side, h)
            } else {
                oval.set(w - side, -h * 0.3f, w + side * 0.7f, h * 1.3f)
                canvas.clipRect(w - side, 0f, w, h)
            }
            canvas.drawOval(oval, moon)
            canvas.clipPath(Path().apply { addOval(oval, Path.Direction.CW) })

            // The ripple from the finger, growing out and fading over its first stretch.
            val age = (SystemClock.uptimeMillis() - tappedAt).coerceAtLeast(0L)
            if (age < WAVE_MS) {
                val t = age / WAVE_MS.toFloat()
                wave.alpha = ((1f - t) * 0x33).roundToInt()
                canvas.drawCircle(tapX, tapY, side * (0.2f + 0.9f * t), wave)
            }
            canvas.restoreToCount(save)

            // Three chevrons, each lit in turn, pointing the way the jump went.
            val cx = if (left) side * 0.5f else w - side * 0.5f
            val cy = h / 2f - 14f * density
            val size = 9f * density
            val phase = ticker?.animatedValue as? Float ?: 0f
            for (i in 0 until 3) {
                val order = if (left) 2 - i else i
                val lit = ((phase * 3f) - order).let { if (it in 0f..1f) 1f - it else 0f }
                chevron.alpha = (0x55 + 0xAA * lit).roundToInt().coerceIn(0, 255)
                val x = cx + (i - 1) * size * 1.6f
                path.reset()
                if (left) {
                    path.moveTo(x + size / 2, cy - size / 2)
                    path.lineTo(x - size / 2, cy)
                    path.lineTo(x + size / 2, cy + size / 2)
                } else {
                    path.moveTo(x - size / 2, cy - size / 2)
                    path.lineTo(x + size / 2, cy)
                    path.lineTo(x - size / 2, cy + size / 2)
                }
                path.close()
                canvas.drawPath(path, chevron)
            }
            canvas.drawText(L.playerSeekSeconds(seconds), cx, cy + size + 22f * density, label)
        }
    }

    /** A slim vertical gauge: percentage on top, the fill, the icon at the foot. */
    private inner class LevelPill(context: Context) : View(context) {
        private val back = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC101014.toInt() }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 12f * density
            typeface = Typeface.DEFAULT_BOLD
        }
        private val rect = RectF()
        private var fraction = 0f
        private var brightness = true
        private val hide = Runnable {
            animate().alpha(0f).setDuration(FADE_MS).withEndAction { visibility = GONE }.start()
        }

        fun show(value: Float, isBrightness: Boolean) {
            fraction = value.coerceIn(0f, 1f)
            brightness = isBrightness
            removeCallbacks(hide)
            animate().cancel()
            alpha = 1f
            visibility = VISIBLE
            postDelayed(hide, LINGER_MS)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, w / 2, w / 2, back)
            val top = 30f * density
            val bottom = h - 34f * density
            val barHalf = 3f * density
            rect.set(w / 2 - barHalf, top, w / 2 + barHalf, bottom)
            canvas.drawRoundRect(rect, barHalf, barHalf, track)
            rect.top = bottom - (bottom - top) * fraction
            canvas.drawRoundRect(rect, barHalf, barHalf, fill)
            canvas.drawText(Translator.messages.formatter.number((fraction * 100).roundToInt()), w / 2, 20f * density, text)
            val iconRes = when {
                brightness -> R.drawable.ic_brightness
                fraction <= 0f -> R.drawable.ic_volume_off
                else -> R.drawable.ic_volume
            }
            ContextCompat.getDrawable(context, iconRes)?.let { icon ->
                val s = (18f * density).roundToInt()
                val left = ((w - s) / 2).roundToInt()
                val iconTop = (h - 8f * density - s).roundToInt()
                icon.setBounds(left, iconTop, left + s, iconTop + s)
                icon.setTint(Color.WHITE)
                icon.draw(canvas)
            }
        }
    }

    /** Where a sideways drag would land: the jump, then target over total, then a bar. */
    private inner class ScrubCard(context: Context) : LinearLayout(context) {
        private val delta = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        private val target = TextView(context).apply {
            setTextColor(0xE6FFFFFF.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
        }
        private val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.accent),
            )
        }
        private val hide = Runnable {
            animate().alpha(0f).setDuration(FADE_MS).withEndAction { visibility = GONE }.start()
        }

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_player_chip)
            setPadding(dp(24f), dp(14f), dp(24f), dp(16f))
            addView(delta)
            addView(target)
            addView(bar, LayoutParams(dp(180f), dp(8f)).apply { topMargin = dp(10f) })
        }

        fun show(deltaMs: Long, targetMs: Long, durationMs: Long) {
            val sign = if (deltaMs >= 0) "+" else "-"
            delta.text = sign + Translator.messages.formatter.clock(kotlin.math.abs(deltaMs))
            target.text = "${Translator.messages.formatter.clock(targetMs)} / ${Translator.messages.formatter.clock(durationMs)}"
            bar.progress = if (durationMs > 0) (targetMs * 1000 / durationMs).toInt() else 0
            removeCallbacks(hide)
            animate().cancel()
            alpha = 1f
            visibility = VISIBLE
        }

        fun hideSoon() {
            removeCallbacks(hide)
            postDelayed(hide, SCRUB_LINGER_MS)
        }
    }

    private companion object {
        const val FADE_MS = 300L
        const val SPINNER_DELAY_MS = 400L
        const val FLASH_MS = 450L
        const val WAVE_MS = 650L
        const val CHEVRON_CYCLE_MS = 750L
        const val LINGER_MS = 1_000L
        const val SCRUB_LINGER_MS = 400L
        const val SIDE_MARGIN_DP = 40f
    }
}
