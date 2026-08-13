package com.tmplayer.data

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

    const val DEFAULT_MIN = 400 * MB
    const val DEFAULT_MAX = 2560 * MB

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
     * Snapping matters because the defaults do not sit on the grid: 2.5 GB is 2560 MB, and
     * without this the slider would forever read 2660, 2760, 2860 once nudged.
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
     */
    fun snap(value: Long): Long {
        if (value <= FLOOR + STEP / 2) return FLOOR
        if (value >= CEILING - STEP / 2) return CEILING
        return ((value + STEP / 2) / STEP) * STEP
    }

    /** Keeps the two bounds from crossing over each other. */
    fun clampMin(candidate: Long, currentMax: Long): Long =
        candidate.coerceIn(FLOOR, (currentMax - STEP).coerceAtLeast(FLOOR))

    fun clampMax(candidate: Long, currentMin: Long): Long =
        candidate.coerceIn((currentMin + STEP).coerceAtMost(CEILING), CEILING)

    fun label(bytes: Long): String = when {
        // "No minimum" rather than "Any size", so the bottom of the range pairs with the
        // "No limit" at the top instead of reading as a description of the whole range.
        bytes <= FLOOR -> "No minimum"
        bytes >= CEILING -> "No limit"
        bytes < GB -> "${bytes / MB} MB"
        bytes % GB == 0L -> "${bytes / GB} GB"
        else -> String.format("%.1f GB", bytes.toDouble() / GB)
    }

    /** Where the thumb sits, 0..1. */
    fun fraction(bytes: Long): Float =
        (bytes.toFloat() / CEILING).coerceIn(0f, 1f)

    /**
     * The current bounds as a sentence, for the line under the slider.
     *
     * Four cases rather than one template, because the open ends are not values a viewer can be
     * shown: "between No minimum and 2.5 GB" is not English. [label] is still the right thing for
     * the track ends and the thumbs, where a bare phrase is what is wanted.
     */
    fun describe(minBytes: Long, maxBytes: Long): String {
        val hasMin = minBytes > FLOOR
        val hasMax = maxBytes < CEILING
        return when {
            hasMin && hasMax -> "You'll see videos between ${label(minBytes)} and ${label(maxBytes)}."
            hasMin -> "You'll see videos from ${label(minBytes)} upwards."
            hasMax -> "You'll see videos up to ${label(maxBytes)}."
            else -> "You'll see every video in the chat, whatever its size."
        }
    }
}
