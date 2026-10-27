package com.tmplayer.data

import com.tmplayer.i18n.L
import kotlin.math.abs

/**
 * Which files are large enough to be a video and small enough to be worth streaming.
 *
 * A video chat is full of things that are not videos (trailers, clips, a two-minute sample), and
 * at the other end a 6 GB remux on an 8 GB stick is more trouble than it is worth. Bounding the
 * list by size removes both without the viewer having to read every entry.
 */
object SizeFilter {

    const val MB = 1024L * 1024
    const val GB = 1024L * MB

    /** The slider runs the whole width of what a TV stick could plausibly hold. */
    const val FLOOR = 0L
    const val CEILING = 8 * GB

    /** One D-pad press. 82 presses cross the whole range, which a held key covers quickly. */
    const val STEP = 100 * MB

    /**
     * High enough to keep trailers, samples and clips out of a chat full of full length videos.
     *
     * Was 400 MB with a 2.5 GB top until 1.21, then 50 MB: viewers saw a series with episodes 9
     * and 30 missing, the ones encoded a little smaller than the rest, and nothing said why. Back
     * to 400 MB since 1.28.2, now that a chat says how many it hides ("n hidden") and changes the
     * minimum from there ([QUICK_MINIMUMS]).
     */
    const val DEFAULT_MIN = 400 * MB

    /**
     * The old default, kept as a stop on the slider: off the step grid, and the minimum a viewer
     * who wants short episodes but no clips is most likely to pick.
     */
    const val CLIP_MIN = 50 * MB

    /** The choices a chat's "n hidden" chip offers, smallest first; the current one is added when off the list. */
    val QUICK_MINIMUMS: List<Long> = listOf(FLOOR, CLIP_MIN, 100 * MB, 200 * MB, DEFAULT_MIN, GB)

    /** [QUICK_MINIMUMS] with [current] in its place, so the picker always has the one that is on. */
    fun quickMinimums(current: Long): List<Long> =
        if (current in QUICK_MINIMUMS) QUICK_MINIMUMS else (QUICK_MINIMUMS + current).sorted()

    /** No upper bound: the top of the slider, which [matches] reads as "anything". */
    const val DEFAULT_MAX = CEILING

    /**
     * A file passes when it sits inside the bounds.
     *
     * Files of unknown size always pass: Telegram sometimes reports zero for a document, and
     * hiding something because it could not be measured would silently lose real videos.
     */
    fun matches(sizeBytes: Long, minBytes: Long, maxBytes: Long): Boolean {
        if (sizeBytes <= 0) return true
        if (sizeBytes < minBytes) return false
        // The top of the slider means "no upper bound" rather than "exactly 8 GB".
        if (maxBytes >= CEILING) return true
        return sizeBytes <= maxBytes
    }

    /**
     * Moves [value] one press in [direction], snapped onto the step grid first.
     *
     * Snapping matters because a value can sit off the grid (50 MB, or the 2560 MB an
     * older build defaulted to), and without this the slider would forever read 150, 250, 350
     * once nudged.
     */
    fun step(value: Long, direction: Int): Long {
        val moved = when {
            // Already on the grid: a plain step.
            value % STEP == 0L -> value + direction * STEP
            // Off the grid: round towards travel, so the first press always moves and lands
            // somewhere tidy. Rounding to nearest would leave a downward press from 2560 stuck.
            direction > 0 -> (value / STEP + 1) * STEP
            else -> value / STEP * STEP
        }
        return moved.coerceIn(FLOOR, CEILING)
    }

    /**
     * Rounds a dragged value onto the step grid.
     *
     * A dragged slider reports a value to the byte, which would make the readout unrepeatable.
     * Both ends snap to themselves, since "No minimum" and "No limit" are the two values a viewer
     * is most likely to want exactly.
     *
     * [CLIP_MIN] is a snap point of its own, off the grid as it is. Without it the slider rounded
     * a stored 50 MB down to "No minimum", the line above it said "from 50 MB upwards", the chats
     * went on hiding clips, and the first drag of either thumb quietly wrote 0.
     */
    fun snap(value: Long): Long {
        if (value <= FLOOR) return FLOOR
        if (value >= CEILING - STEP / 2) return CEILING
        val grid = ((value + STEP / 2) / STEP) * STEP
        // On a tie the grid wins, so 50 MB only claims the values nearer to it.
        return if (abs(CLIP_MIN - value) < abs(grid - value)) CLIP_MIN else grid
    }

    /** Whether the bounds are the ones a fresh install starts with, which is what Reset restores. */
    fun isDefault(minBytes: Long, maxBytes: Long): Boolean =
        minBytes == DEFAULT_MIN && maxBytes >= DEFAULT_MAX

    /** Keeps the two bounds from crossing over each other. */
    fun clampMin(candidate: Long, currentMax: Long): Long =
        candidate.coerceIn(FLOOR, (currentMax - STEP).coerceAtLeast(FLOOR))

    fun clampMax(candidate: Long, currentMin: Long): Long =
        candidate.coerceIn((currentMin + STEP).coerceAtMost(CEILING), CEILING)

    fun label(bytes: Long): String = L.messages.formatter.sizeLimit(bytes)

    /**
     * The track's scale, as (bytes, position) corners joined by straight lines.
     *
     * Not linear: on a straight 0 to 8 GB track 50 MB sat 0.6 per cent in, a few
     * pixels from "No minimum", and the two looked the same. Here No minimum, 50 MB and 100 MB
     * are evenly spaced at the start, the first gigabyte takes a little under half the track,
     * where the choices that matter are, and the rest stretches out to No limit.
     */
    private val SCALE = listOf(
        FLOOR to 0f,
        CLIP_MIN to 0.05f,
        100 * MB to 0.10f,
        GB to 0.45f,
        CEILING to 1f,
    )

    /** Where the thumb sits, 0..1. */
    fun fraction(bytes: Long): Float {
        if (bytes <= FLOOR) return 0f
        if (bytes >= CEILING) return 1f
        val upper = SCALE.indexOfFirst { it.first >= bytes }
        val (b0, p0) = SCALE[upper - 1]
        val (b1, p1) = SCALE[upper]
        return p0 + (p1 - p0) * ((bytes - b0).toFloat() / (b1 - b0))
    }

    /** The other way: the size at a point on the track, to the byte, for [snap] to tidy. */
    fun bytesAt(fraction: Float): Long {
        if (fraction <= 0f) return FLOOR
        if (fraction >= 1f) return CEILING
        val upper = SCALE.indexOfFirst { it.second >= fraction }
        val (b0, p0) = SCALE[upper - 1]
        val (b1, p1) = SCALE[upper]
        return b0 + ((b1 - b0) * ((fraction - p0) / (p1 - p0)).toDouble()).toLong()
    }

    /**
     * The current bounds as a sentence, for the line under the slider.
     *
     * Four cases rather than one template, because the open ends are not values a viewer can be
     * shown: "between No minimum and 2.5 GB" is not English. [label] is still the right thing for
     * the track ends and the thumbs, where a bare phrase is what is wanted.
     */
    fun describe(minBytes: Long, maxBytes: Long): String = L.messages.formatter.sizeRange(minBytes, maxBytes)

    /** What the size limits let through, and how many they turned away. */
    data class Split<T>(val kept: List<T>, val hidden: Int)

    /**
     * [items] through [matches], counting what was left out, so a listing can say "12 videos
     * hidden by the size limits" rather than leaving an episode missing without a word.
     */
    fun <T> split(items: List<T>, minBytes: Long, maxBytes: Long, sizeOf: (T) -> Long): Split<T> {
        val kept = items.filter { matches(sizeOf(it), minBytes, maxBytes) }
        return Split(kept, items.size - kept.size)
    }
}
