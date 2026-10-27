package com.tmplayer.player

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.tmplayer.R

/**
 * When the scrub card waits, gives up and starts over. Plain numbers, so they are tested without
 * a view (see `ScrubPreviewRulesTest`).
 */
internal object ScrubPreviewRules {

    /** How long a position may go unanswered before the shimmer stops for the still fallback. */
    const val WAIT_MS = 1_500L

    /** The least jump that drops the picture on screen for the shimmer, whatever the length. */
    const val FAR_MIN_MS = 90_000L

    /**
     * Whether the picture for [shownMs] is too far from [wantedMs] to stand in for it while the
     * right one is on its way: a twentieth of the video, and never less than [FAR_MIN_MS]. With
     * no length known, only the fixed distance counts.
     */
    fun farJump(shownMs: Long, wantedMs: Long, durationMs: Long): Boolean {
        if (shownMs < 0) return true
        val limit = if (durationMs > 0) maxOf(FAR_MIN_MS, durationMs / 20) else FAR_MIN_MS
        return kotlin.math.abs(wantedMs - shownMs) > limit
    }

    /**
     * Where along the bar, 0 to 1 from its start, [positionMs] sits. A length not known yet (or
     * zero) puts the card over the middle of the bar rather than at its start, where a scrub of
     * a video that cannot say how long it is would otherwise pin it.
     */
    fun fraction(positionMs: Long, durationMs: Long): Float =
        if (durationMs <= 0) 0.5f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /**
     * The card's left edge for a scrub point at [centre], kept [edge] inside both sides of a
     * window [parentWidth] wide plus the safe area's [insetStart] and [insetEnd] (the cutout and
     * the system bars). A window narrower than the card puts it at the left inset.
     */
    fun left(centre: Float, width: Int, parentWidth: Int, edge: Float, insetStart: Int, insetEnd: Int): Float {
        val min = edge + insetStart
        val max = (parentWidth - width - edge - insetEnd).coerceAtLeast(min)
        return (centre - width / 2f).coerceIn(min, max)
    }
}

/**
 * The card over the scrub bar (HX3, CP80): a fixed 16:9 box, about 176 by 99 dp on a phone and
 * 256 by 144 on a television, with the scrub time in a chip at its foot.
 *
 * Three faces, in the order a scrub meets them:
 * - a skeleton, the app's placeholder block, while the sources look for a picture (its sweep runs
 *   on a phone only, and stands still under Reduce motion and on a television, as
 *   `rememberShimmer` does);
 * - the frame, fitted inside the box with black round it when its shape is not 16:9, faded in;
 *   moving on keeps it until the next one is in, unless the scrub jumped far
 *   ([ScrubPreviewRules.farJump]);
 * - after [ScrubPreviewRules.WAIT_MS] with nothing, or a source saying there is nothing: the
 *   video's own thumbnail dimmed (or a plain cover when it has none) with the time large across
 *   it. Never a shimmer that goes on forever.
 *
 * Nothing here is focusable, so a remote's focus stays on the bar, and nothing runs while the card
 * is down: the sweep and the wait are stopped with it.
 */
internal class ScrubPreviewCard(
    parent: ViewGroup,
    private val isTv: Boolean,
    /** True where motion is to stand still: the app's Reduce motion, or the system's animations off. */
    private val lessMotion: () -> Boolean,
) {
    private enum class Face { Skeleton, Frame, Fallback }

    private val density = parent.resources.displayMetrics.density
    private fun px(dp: Float) = (dp * density).toInt()

    val widthPx = px(if (isTv) TV_WIDTH_DP else PHONE_WIDTH_DP)
    val heightPx = widthPx * 9 / 16
    private val corner = (if (isTv) 12f else 10f) * density

    private val shimmer = ShimmerDrawable(corner)

    /** The cover drawn where the video has no thumbnail of its own. */
    private val cover = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(blend(ContextCompat.getColor(parent.context, R.color.accent), 0.35f), Color.rgb(0x12, 0x12, 0x16)),
    )

    private val fallbackArt = ImageView(parent.context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        background = cover
        visibility = View.GONE
    }

    private val fallbackTime = TextView(parent.context).apply {
        setTextColor(Color.WHITE)
        textSize = if (isTv) 30f else 22f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        maxLines = 1
        gravity = Gravity.CENTER
        setShadowLayer(6f * density, 0f, 1f * density, Color.argb(0xB0, 0, 0, 0))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = View.GONE
    }

    private val skeleton = View(parent.context).apply {
        background = shimmer
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private val frame = ImageView(parent.context).apply {
        // Fitted, not cropped: an upright or square frame shows whole, with black round it.
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(Color.BLACK)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = View.GONE
    }

    private val chip = TextView(parent.context).apply {
        background = ContextCompat.getDrawable(context, R.drawable.bg_player_chip)
        setPadding(px(10f), px(3f), px(10f), px(3f))
        setTextColor(Color.WHITE)
        textSize = if (isTv) 16f else 13f
        maxLines = 1
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** The hairline over everything, so a frame's own edge never covers it. */
    private val outline = View(parent.context).apply {
        background = GradientDrawable().apply {
            cornerRadius = corner
            setColor(Color.TRANSPARENT)
            setStroke(maxOf(1, px(1f)), Color.argb(0x8C, 0xFF, 0xFF, 0xFF))
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    val view: FrameLayout = FrameLayout(parent.context).apply {
        visibility = View.GONE
        isFocusable = false
        isClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        background = GradientDrawable().apply {
            cornerRadius = corner
            setColor(Color.BLACK)
        }
        clipToOutline = true
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        val fill = { FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        addView(fallbackArt, fill())
        addView(fallbackTime, fill())
        addView(skeleton, fill())
        addView(frame, fill())
        addView(
            chip,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = px(6f)
            },
        )
        addView(outline, fill())
        parent.addView(this, FrameLayout.LayoutParams(widthPx, heightPx))
    }

    init {
        // The player closed mid-scrub: the sweep's animator and the wait go with the window.
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) = hide()
        })
    }

    /** The video's own thumbnail, for the fallback; null draws the plain cover. */
    var art: Bitmap? = null
        set(value) {
            field = value
            fallbackArt.setImageBitmap(value)
            // Dimmed, so the time across it reads on a white sky as well as a dark room.
            if (value != null) fallbackArt.setColorFilter(Color.argb(0x8C, 0, 0, 0), PorterDuff.Mode.SRC_ATOP) else fallbackArt.clearColorFilter()
        }

    private var face: Face? = null

    /** The position the frame on screen is of, or -1 with none. */
    private var shownAt = -1L

    /** The newest position asked for: a late answer for an older one is dropped. */
    private var wanted = -1L

    private val giveUp = Runnable { if (face == Face.Skeleton) toFallback() }

    val isShown: Boolean get() = view.visibility == View.VISIBLE

    /**
     * Shows the card for [positionMs] and asks [source] for its picture; [live] says whether the
     * scrub is still on when an answer lands. A null [source] (no storyboard, no frames on this
     * device) goes straight to the fallback: there is nothing to wait for.
     */
    fun show(positionMs: Long, durationMs: Long, label: String, source: ScrubThumbnails?, live: () -> Boolean) {
        wanted = positionMs
        chip.text = label
        fallbackTime.text = label
        view.visibility = View.VISIBLE
        when {
            source == null -> toFallback()
            face == null -> toSkeleton()
            face == Face.Frame && ScrubPreviewRules.farJump(shownAt, positionMs, durationMs) -> toSkeleton()
        }
        source ?: return
        source.request(positionMs) { picture ->
            if (wanted != positionMs || !live() || !isShown) return@request
            when {
                picture != null -> toFrame(picture, positionMs)
                // Nothing for this position. The picture on screen stays while it is close enough
                // to say where this is; otherwise the fallback says it plainly.
                face == Face.Frame && !ScrubPreviewRules.farJump(shownAt, positionMs, durationMs) -> Unit
                else -> toFallback()
            }
        }
    }

    /** Takes the card down and lets go of its picture; the next scrub starts from the skeleton. */
    fun hide() {
        view.removeCallbacks(giveUp)
        shimmer.stop()
        view.visibility = View.GONE
        frame.animate().cancel()
        frame.setImageDrawable(null)
        frame.visibility = View.GONE
        face = null
        shownAt = -1L
        wanted = -1L
    }

    private fun toSkeleton() {
        face = Face.Skeleton
        shownAt = -1L
        frame.animate().cancel()
        frame.setImageDrawable(null)
        frame.visibility = View.GONE
        fallbackArt.visibility = View.GONE
        fallbackTime.visibility = View.GONE
        skeleton.visibility = View.VISIBLE
        chip.visibility = View.VISIBLE
        if (isTv || lessMotion()) shimmer.still() else shimmer.start()
        view.removeCallbacks(giveUp)
        view.postDelayed(giveUp, ScrubPreviewRules.WAIT_MS)
    }

    private fun toFallback() {
        view.removeCallbacks(giveUp)
        shimmer.stop()
        face = Face.Fallback
        shownAt = -1L
        frame.animate().cancel()
        frame.setImageDrawable(null)
        frame.visibility = View.GONE
        skeleton.visibility = View.GONE
        fallbackArt.visibility = View.VISIBLE
        fallbackTime.visibility = View.VISIBLE
        // The time is already large in the middle.
        chip.visibility = View.GONE
    }

    private fun toFrame(picture: Bitmap, positionMs: Long) {
        view.removeCallbacks(giveUp)
        val fresh = face != Face.Frame
        face = Face.Frame
        shownAt = positionMs
        frame.setImageBitmap(picture)
        frame.visibility = View.VISIBLE
        chip.visibility = View.VISIBLE
        fallbackTime.visibility = View.GONE
        if (fresh && !lessMotion()) {
            frame.animate().cancel()
            frame.alpha = 0f
            frame.animate().alpha(1f).setDuration(FADE_MS).withEndAction { afterFrameIn() }.start()
        } else {
            frame.alpha = 1f
            afterFrameIn()
        }
    }

    /** Once the frame covers the box, what is under it stops drawing. */
    private fun afterFrameIn() {
        if (face != Face.Frame) return
        shimmer.stop()
        skeleton.visibility = View.GONE
        fallbackArt.visibility = View.GONE
    }

    private companion object {
        const val PHONE_WIDTH_DP = 176f
        const val TV_WIDTH_DP = 256f
        const val FADE_MS = 160L

        fun blend(color: Int, amount: Float): Int = Color.rgb(
            (Color.red(color) * amount).toInt(),
            (Color.green(color) * amount).toInt(),
            (Color.blue(color) * amount).toInt(),
        )
    }
}

/**
 * The app's placeholder block for a view: the same greys, and the same slow band crossing it in
 * [SWEEP_MS] as `rememberShimmer` (ui), which is Compose and cannot be drawn in the player's views.
 * [still] draws the flat block alone, for a television and Reduce motion. The animator runs only
 * between [start] and [stop].
 */
internal class ShimmerDrawable(private val corner: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = android.graphics.RectF()
    private var progress = 0f
    private var animator: ValueAnimator? = null

    fun start() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SWEEP_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidateSelf()
            }
            start()
        }
    }

    fun still() {
        stop()
        progress = -1f
        invalidateSelf()
    }

    fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        rect.set(b)
        val width = b.width().toFloat()
        if (animator == null || progress < 0f) {
            paint.shader = null
            paint.color = BASE
        } else {
            val x = b.left + (progress * 2f - 1f) * width
            paint.shader = LinearGradient(x, 0f, x + width, 0f, intArrayOf(BASE, BAND, BASE), null, Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(rect, corner, corner, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val SWEEP_MS = 1_400L

        /** The dark scheme's surface and its raised tone, the player being dark whatever the theme. */
        val BASE = Color.rgb(0x26, 0x26, 0x2B)
        val BAND = Color.rgb(0x3A, 0x3A, 0x41)
    }
}
