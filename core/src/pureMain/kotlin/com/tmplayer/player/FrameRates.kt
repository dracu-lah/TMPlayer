package com.tmplayer.player

import kotlin.math.abs

/**
 * What the player does with a video's frame rate and bit rate when the container does not give
 * them (HX11, HX37): a Matroska without a default duration leaves Media3's `Format.frameRate` at
 * `NO_VALUE` (-1), and the format's bit rate is `NO_VALUE` for the same files, so the report read
 * `fps 0.0` and `bitrate 0`. Pure, so every odd figure is tested alone.
 *
 * An unknown rate is 0 here, everywhere. 0 means "not known": [DecodeJudge] then assumes
 * [com.tmplayer.data.DecodeJudge.ASSUMED_FPS], the frame step falls back to its own default, the
 * display is asked for no rate (a request of 0 clears the app's request), and the details show
 * no frame rate. A figure that is not a number, negative, or absurd is treated as unknown, never
 * passed on.
 */
object FrameRates {

    /** Below this or above [MAX_FPS] a figure is a mistake, not a frame rate. */
    const val MIN_FPS = 5f
    const val MAX_FPS = 240f

    /** The rates films and broadcasts are made at, which a measured rate snaps to when it is near. */
    private val STANDARD = floatArrayOf(
        24000f / 1001f, 24f, 25f, 30000f / 1001f, 30f, 48f, 50f, 60000f / 1001f, 60f, 120000f / 1001f, 120f,
    )

    /** How near, as a share, a measured rate must be to a standard one to become it. */
    private const val SNAP_SHARE = 0.015f

    /** The rate if it can be one, 0 when it is unknown (-1), not a number, infinite or out of range. */
    fun plausible(fps: Float): Float = if (fps.isFinite() && fps in MIN_FPS..MAX_FPS) fps else 0f

    /** The container's rate when it gave one, else the one measured from the frames, else 0. */
    fun pick(container: Float, derived: Float): Float = plausible(container).takeIf { it > 0f } ?: plausible(derived)

    /** The nearest standard rate [fps] is within [SNAP_SHARE] of, or null (24.1 is 24, not 23.976). */
    fun snap(fps: Float): Float? {
        val rate = plausible(fps).takeIf { it > 0f } ?: return null
        val nearest = STANDARD.minByOrNull { abs(rate - it) / it } ?: return null
        return nearest.takeIf { abs(rate - it) / it <= SNAP_SHARE }
    }

    /** The rate to ask the display for: a plausible figure, a near standard one made exact; 0 for none. */
    fun forDisplay(fps: Float): Float {
        val rate = plausible(fps)
        return if (rate > 0f) snap(rate) ?: rate else 0f
    }

    /** Where the rate in a report came from: `container`, `derived` or `unknown`. */
    fun source(container: Float, derived: Float): String = when {
        plausible(container) > 0f -> "container"
        plausible(derived) > 0f -> "derived"
        else -> "unknown"
    }

    /**
     * The bit rate in kilobits a second for the report: the format's own when it has one, else the
     * file's size over its length (sound included, so a little high for the picture alone), else 0.
     * Negative or absurd input (past 2 Gbit/s) is 0.
     */
    fun bitrateKbps(formatBps: Int, sizeBytes: Long, durationSec: Int): Int {
        if (formatBps > 0) return (formatBps / 1000).coerceAtMost(MAX_KBPS)
        if (sizeBytes <= 0L || durationSec <= 0) return 0
        // Divide first: a size near Long.MAX_VALUE must not overflow on the way to bits.
        val kbps = sizeBytes / durationSec / 125L
        return if (kbps in 1..MAX_KBPS.toLong()) kbps.toInt() else 0
    }

    private const val MAX_KBPS = 2_000_000
}

/**
 * Reads a video's frame rate from the presentation times of the frames as they reach the screen,
 * for a container that does not state one. Feed it each rendered frame's time in microseconds,
 * in the order shown, on one thread; it answers once, when a window of frames agrees.
 *
 * Matroska times are whole milliseconds, so a 23.976 fps film steps 41, 42, 42, 41 ms: the mean
 * over a window is used, not one step. A window whose steps do not agree (a variable frame rate,
 * a seek in the middle) is thrown away and tried again, up to [MAX_ATTEMPTS] times, after which
 * the rate stays unknown ([gaveUp]) and the caller stops feeding it.
 */
class FrameRateEstimator {

    private val steps = LongArray(WINDOW)
    private var count = 0
    private var last = -1L
    private var attempts = 0

    /** The rate found, or 0 while unknown. */
    var fps = 0f
        private set

    /** Whether to stop feeding: found, or too many windows disagreed. */
    val finished: Boolean get() = fps > 0f || attempts >= MAX_ATTEMPTS

    val gaveUp: Boolean get() = fps <= 0f && attempts >= MAX_ATTEMPTS

    /** One frame's presentation time; returns the rate on the call that settles it, else null. */
    fun add(presentationTimeUs: Long): Float? {
        if (finished) return null
        val previous = last
        last = presentationTimeUs
        if (previous < 0 || presentationTimeUs < 0) return null
        val step = presentationTimeUs - previous
        // The same time twice, or backwards: not a new frame. A long gap: a seek or a pause in the stream.
        if (step <= 0) return null
        if (step > MAX_STEP_US) {
            count = 0
            return null
        }
        steps[count++] = step
        if (count < WINDOW) return null
        count = 0
        return settle()?.also { fps = it } ?: run {
            attempts++
            null
        }
    }

    /** Starts over, for a new video or a new track. */
    fun reset() {
        count = 0
        last = -1L
        attempts = 0
        fps = 0f
    }

    private fun settle(): Float? {
        val sorted = steps.sortedArray()
        val median = sorted[WINDOW / 2]
        var sum = 0L
        var near = 0
        for (step in steps) {
            if (step * 2 >= median && step * 2 <= median * 3) {
                sum += step
                near++
            }
        }
        // Most steps must sit near the typical one, or the rate is not steady enough to name.
        if (near * 10 < WINDOW * 9 || sum <= 0L) return null
        val rate = near * 1_000_000f / sum
        val plausible = FrameRates.plausible(rate)
        if (plausible <= 0f) return null
        return FrameRates.snap(plausible) ?: plausible
    }

    private companion object {
        /** Frames to measure over: two seconds of film, enough for the milliseconds to average out. */
        const val WINDOW = 48
        const val MAX_ATTEMPTS = 3

        /** A step longer than this (a fifth of a second) is a jump, not a frame interval. */
        const val MAX_STEP_US = 200_000L
    }
}
