package android.view.animation

import android.animation.TimeInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow

// Android's interpolators, with Android's curves.

/** Android's interpolator. */
interface Interpolator : TimeInterpolator

class LinearInterpolator : Interpolator {
    override fun getInterpolation(input: Float): Float = input
}

class DecelerateInterpolator(private val factor: Float = 1f) : Interpolator {
    override fun getInterpolation(input: Float): Float =
        if (factor == 1f) 1f - (1f - input) * (1f - input) else (1f - (1f - input).toDouble().pow(2.0 * factor)).toFloat()
}

class AccelerateInterpolator(private val factor: Float = 1f) : Interpolator {
    override fun getInterpolation(input: Float): Float =
        if (factor == 1f) input * input else input.toDouble().pow(2.0 * factor).toFloat()
}

class AccelerateDecelerateInterpolator : Interpolator {
    override fun getInterpolation(input: Float): Float = (cos((input + 1) * PI) / 2.0 + 0.5).toFloat()
}

class OvershootInterpolator(private val tension: Float = 2f) : Interpolator {
    override fun getInterpolation(input: Float): Float {
        val t = input - 1f
        return t * t * ((tension + 1) * t + tension) + 1f
    }
}

/**
 * A cubic Bezier from (0, 0) to (1, 1) through two control points, Android's: progress is the
 * curve's y where its x is the time, found by bisection, as close as a frame can show.
 */
class PathInterpolator(private val x1: Float, private val y1: Float, private val x2: Float, private val y2: Float) : Interpolator {
    private fun bezier(t: Float, a: Float, b: Float): Float {
        val u = 1f - t
        return 3f * u * u * t * a + 3f * u * t * t * b + t * t * t
    }

    override fun getInterpolation(input: Float): Float {
        if (input <= 0f) return 0f
        if (input >= 1f) return 1f
        var low = 0f
        var high = 1f
        var t = input
        repeat(24) {
            t = (low + high) / 2f
            if (bezier(t, x1, x2) < input) low = t else high = t
        }
        return bezier(t, y1, y2)
    }
}
