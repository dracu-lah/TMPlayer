package com.tmplayer.player

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * A play glyph that turns into a pause glyph and back, rather than swapping one picture for the
 * other.
 *
 * The change of shape is the feedback: a thumb on the big centre button sees the two bars fold
 * into a triangle, which says "that worked" without a word. Drawn as two four-cornered shapes,
 * each pause bar morphing into one half of the play triangle, so every frame of the animation is a
 * straight interpolation between two sets of eight points and no path library is needed. Material
 * Symbols' play and pause are static, and Media3's own glyphs are swapped, not animated.
 */
class PlayPauseIcon @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val path = Path()

    /** 0 is pause (two bars), 1 is play (the triangle). */
    private var progress = 0f
    private var animator: ValueAnimator? = null

    /** What the glyph currently shows: true for the play triangle. */
    var showsPlay: Boolean = false
        private set

    /**
     * Shows play or pause. [animate] false snaps, for the first draw and for anything that is
     * not the viewer's own doing, like the row coming up over a paused video.
     */
    fun setShowsPlay(play: Boolean, animate: Boolean) {
        if (play == showsPlay && animator == null) return
        showsPlay = play
        val target = if (play) 1f else 0f
        animator?.cancel()
        animator = null
        if (!animate || !isAttachedToWindow) {
            progress = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = MORPH_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            doOnEndCompat { animator = null }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val scale = minOf(width, height) / VIEWPORT
        val dx = (width - VIEWPORT * scale) / 2f
        val dy = (height - VIEWPORT * scale) / 2f
        path.reset()
        shape(PAUSE_LEFT, PLAY_LEFT, scale, dx, dy)
        shape(PAUSE_RIGHT, PLAY_RIGHT, scale, dx, dy)
        canvas.drawPath(path, paint)
    }

    private fun shape(from: FloatArray, to: FloatArray, scale: Float, dx: Float, dy: Float) {
        for (i in 0 until 4) {
            val x = lerp(from[i * 2], to[i * 2]) * scale + dx
            val y = lerp(from[i * 2 + 1], to[i * 2 + 1]) * scale + dy
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }

    private fun lerp(a: Float, b: Float) = a + (b - a) * progress

    private inline fun ValueAnimator.doOnEndCompat(crossinline block: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) = block()
        })
    }

    private companion object {
        const val MORPH_MS = 250L
        const val VIEWPORT = 24f

        // Corners clockwise from the top left, in a 24 by 24 box.
        val PAUSE_LEFT = floatArrayOf(6f, 5f, 10f, 5f, 10f, 19f, 6f, 19f)
        val PAUSE_RIGHT = floatArrayOf(14f, 5f, 18f, 5f, 18f, 19f, 14f, 19f)

        // The triangle 7,4 to 20,12 to 7,20, cut down the middle at x = 13.5.
        val PLAY_LEFT = floatArrayOf(7f, 4f, 13.5f, 8f, 13.5f, 16f, 7f, 20f)
        val PLAY_RIGHT = floatArrayOf(13.5f, 8f, 20f, 12f, 20f, 12f, 13.5f, 16f)
    }
}
