package com.tmplayer.player

import com.tmplayer.i18n.L

/**
 * The speeds the transport row offers, and the arithmetic for stepping between them.
 *
 * The speed button steps through a fixed list on both devices, which is what a D-pad can drive; a
 * phone's overflow menu also lists every stop to pick from directly. The choice is remembered for
 * the next video either way.
 *
 * Pure, so the stepping is tested rather than discovered on a sofa.
 */
object PlaybackSpeed {

    val CHOICES = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

    const val DEFAULT = 1f

    /** The next speed up, wrapping back to the slowest past the top of the list. */
    fun next(current: Float): Float {
        val at = nearestIndex(current)
        return CHOICES[(at + 1) % CHOICES.size]
    }

    /** Snaps whatever was stored on disk onto a speed the list actually offers. */
    fun sanitise(value: Float): Float =
        if (value.isNaN() || value <= 0f) DEFAULT else CHOICES[nearestIndex(value)]

    /** "1x", "1.25x": trailing zeroes dropped, because "1.00x" reads as a measurement. */
    fun label(value: Float): String {
        // The locale's digits, at most two decimals, trailing zeroes dropped: "1x", "1.25x", "1,25x".
        return L.formatSpeedFactor(L.messages.formatter.number(sanitise(value).toBigDecimal().stripTrailingZeros()))
    }

    private fun nearestIndex(value: Float): Int {
        var best = 0
        for (i in CHOICES.indices) {
            if (kotlin.math.abs(CHOICES[i] - value) < kotlin.math.abs(CHOICES[best] - value)) best = i
        }
        return best
    }
}
