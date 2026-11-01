package com.tmplayer.player

import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Free zoom over the picture, as VLC and MX Player have it: a pinch that follows the fingers from
 * 100 to 400 per cent around the point between them, a drag that moves the enlarged picture, and a
 * pinch back under [SNAP] that settles on the plain fitted picture again.
 *
 * It sits on top of the chosen [VideoScale]: the shape decides the picture's size at 100 per cent
 * ([Frame.pictureWidth] and [Frame.pictureHeight]), the zoom enlarges that. Choosing a shape starts
 * the zoom again from [NONE]; so do a new video and leaving the player.
 *
 * All in the pixels of the view the picture is drawn in. [panX] and [panY] are how far the
 * picture's centre has moved from the view's centre. They are held so the picture's edge never
 * comes inside the view's edge ([clampedTo]): on a side where the enlarged picture is still
 * narrower than the view it stays centred, bars and all, and on a side where it is wider it can
 * move until its edge meets the view's edge and no further.
 *
 * Pure, so the phone (a view transform on the picture's frame) and the desktop (mpv's
 * `video-zoom` and `video-pan-x`/`-y`, [mpvZoom] and [mpvPan]) share the arithmetic and its tests.
 * Never fed outside data: every input is a touch, a wheel or a view size, and a size of zero or not
 * a number reads as "nothing to zoom" rather than a crash.
 */
data class PictureZoom(val scale: Float = MIN, val panX: Float = 0f, val panY: Float = 0f) {

    /** The view the picture is drawn in, and the picture's own size there at 100 per cent. */
    data class Frame(val viewWidth: Float, val viewHeight: Float, val pictureWidth: Float, val pictureHeight: Float) {
        val valid: Boolean
            get() = listOf(viewWidth, viewHeight, pictureWidth, pictureHeight).all { it.isFinite() && it > 0f }
    }

    /** True once the picture is larger than the shape draws it. */
    val zoomed: Boolean get() = scale > MIN

    /** The zoom as the chip shows it: 135 for 1.35. */
    val percent: Int get() = (scale * 100f).roundToInt()

    /**
     * One step of a pinch: the scale times [factor], around the point ([focusX], [focusY]) the
     * fingers are centred on, which stays under them. Held between [MIN] and [MAX]; a factor that
     * is not a number or not above zero changes nothing.
     */
    fun pinch(factor: Float, focusX: Float, focusY: Float, frame: Frame): PictureZoom {
        if (!frame.valid || !factor.isFinite() || factor <= 0f || !focusX.isFinite() || !focusY.isFinite()) return this
        val from = scale.coerceIn(MIN, MAX)
        val next = (from * factor).coerceIn(MIN, MAX)
        val grow = next / from
        val fx = focusX - frame.viewWidth / 2f
        val fy = focusY - frame.viewHeight / 2f
        // The picture point under the focus is (f - pan) / scale; it stays under f at the new scale.
        return PictureZoom(next, fx - (fx - panX) * grow, fy - (fy - panY) * grow).clampedTo(frame)
    }

    /** A drag of ([dx], [dy]) pixels on the enlarged picture; nothing at 100 per cent. */
    fun panBy(dx: Float, dy: Float, frame: Frame): PictureZoom {
        if (!zoomed || !frame.valid || !dx.isFinite() || !dy.isFinite()) return this
        return copy(panX = panX + dx, panY = panY + dy).clampedTo(frame)
    }

    /**
     * Held to [MIN]..[MAX], with the picture's edge never inside the view's edge. Called again
     * whenever the frame changes size: a phone turned, a window resized, another shape.
     */
    fun clampedTo(frame: Frame): PictureZoom {
        if (!frame.valid) return NONE
        val held = if (scale.isFinite()) scale.coerceIn(MIN, MAX) else MIN
        val reachX = max(0f, (held * frame.pictureWidth - frame.viewWidth) / 2f)
        val reachY = max(0f, (held * frame.pictureHeight - frame.viewHeight) / 2f)
        val x = if (panX.isFinite()) panX.coerceIn(-reachX, reachX) else 0f
        val y = if (panY.isFinite()) panY.coerceIn(-reachY, reachY) else 0f
        return PictureZoom(held, x, y)
    }

    /** Where a pinch comes to rest as the fingers lift: just over 100 per cent is 100 per cent. */
    fun settled(frame: Frame): PictureZoom = if (scale < SNAP) NONE else clampedTo(frame)

    /**
     * A key's or a wheel notch's step, [steps] of [STEP] each (negative to zoom out), around
     * ([focusX], [focusY]); settled at once, since there is no lift to wait for.
     */
    fun stepped(steps: Float, focusX: Float, focusY: Float, frame: Frame): PictureZoom {
        if (!steps.isFinite()) return this
        val factor = STEP.pow(steps.coerceIn(-MAX_STEPS, MAX_STEPS))
        return pinch(factor, focusX, focusY, frame).let { if (steps < 0f) it.settled(frame) else it }
    }

    /** mpv's `video-zoom`, which is a power of two: 0 for 100 per cent, 1 for 200. */
    val mpvZoom: Double get() = log2(scale.coerceIn(MIN, MAX).toDouble())

    /**
     * mpv's `video-pan-x` and `video-pan-y`, which count in the enlarged picture's own width and
     * height (mpv's aspect.c: the offset is pan times the scaled size, after the zoom).
     */
    fun mpvPan(frame: Frame): Pair<Double, Double> {
        if (!frame.valid || !zoomed) return 0.0 to 0.0
        val held = scale.coerceIn(MIN, MAX)
        return (panX / (held * frame.pictureWidth)).toDouble() to (panY / (held * frame.pictureHeight)).toDouble()
    }

    companion object {
        val NONE = PictureZoom()

        const val MIN = 1f
        const val MAX = 4f

        /** A pinch let go under this settles on 100 per cent, so "almost back" is back. */
        const val SNAP = 1.05f

        /** One key press or wheel notch: four of them double the picture. */
        val STEP: Float = 2f.pow(0.25f)

        /** A wheel that reports a huge turn in one event still moves a sane amount. */
        private const val MAX_STEPS = 8f
    }
}
