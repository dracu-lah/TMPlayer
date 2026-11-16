package com.tmplayer.data

import com.tmplayer.i18n.L

/**
 * Which files are listed, by size.
 *
 * Every video of a chat is listed by default ([DEFAULT_MIN] is 0). A chat's Sort sheet has one
 * switch, "Hide small clips" ([CLIP_MIN]), and Home and the notifications keep a floor of their
 * own ([HOME_MIN], [NOTIFY_MIN]) so trailers do not fill Recently added or ping. There are no
 * sliders: the stored minimum and maximum stay readable ([effectiveMin], [effectiveMax]) for a
 * viewer who set them before.
 */
object SizeFilter {

    const val MB = 1024L * 1024
    const val GB = 1024L * MB

    /** No minimum: the bottom of the old slider, and now the default. */
    const val FLOOR = 0L
    const val CEILING = 8 * GB

    /**
     * Every video of a chat is listed. Was 400 MB until 2026-11: it hid every phone clip and
     * forwarded video, and the only cue was an "n hidden" chip (R1, design/redesign).
     */
    const val DEFAULT_MIN = FLOOR

    /** What "Hide small clips" hides below: trailers, samples, short clips. */
    const val CLIP_MIN = 50 * MB

    /**
     * The default until 2026-11. It was never a choice, so a stored value equal to it reads as
     * "not set" ([effectiveMin]).
     */
    const val OLD_DEFAULT_MIN = 400 * MB

    /** Home's rows leave out what is under this, so trailers and samples do not fill Recently added. */
    const val HOME_MIN = CLIP_MIN

    /** New video and episode alerts leave out what is under this, so a clip does not ping. */
    const val NOTIFY_MIN = CLIP_MIN

    /** No upper bound: the top of the old slider, which [matches] reads as "anything". */
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
        // The top means "no upper bound" rather than "exactly 8 GB".
        if (maxBytes >= CEILING) return true
        return sizeBytes <= maxBytes
    }

    /**
     * The minimum a stored `min_size_bytes` means: nothing stored, zero or the old default of
     * 400 MB is "not set" (0); a value chosen on purpose keeps working.
     */
    fun effectiveMin(stored: Long?): Long = when {
        stored == null || stored <= FLOOR || stored == OLD_DEFAULT_MIN -> FLOOR
        else -> stored
    }

    /** The maximum a stored `max_size_bytes` means: nothing stored or a nonsense value is "no limit". */
    fun effectiveMax(stored: Long?): Long = when {
        stored == null || stored <= FLOOR || stored > CEILING -> CEILING
        else -> stored
    }

    /**
     * Whether the "Hide small clips" switch reads on: any floor is hiding small videos, the switch's
     * own 50 MB or a custom one chosen before (which the switch then keeps, see [clipSwitchFloor]).
     */
    fun hidesSmallClips(minBytes: Long): Boolean = minBytes > FLOOR

    /**
     * The floor to store when "Hide small clips" is pressed, or null to store none. On keeps a custom
     * floor already chosen (a 300 MB floor is not overwritten by 50 MB; Reset still removes it) and
     * writes [CLIP_MIN] otherwise; off removes the key, as before.
     */
    fun clipSwitchFloor(storedMin: Long?, on: Boolean): Long? {
        if (!on) return null
        val current = effectiveMin(storedMin)
        return if (isCustomMin(current)) current else CLIP_MIN
    }

    /** A floor that is neither off nor the switch's: one chosen on purpose before, kept and shown with a Reset. */
    fun isCustomMin(minBytes: Long): Boolean = minBytes > FLOOR && minBytes != CLIP_MIN

    /** Whether the Sort sheet has a custom floor or ceiling to report, which Reset removes. */
    fun hasCustomLimits(minBytes: Long, maxBytes: Long): Boolean = isCustomMin(minBytes) || maxBytes < CEILING

    /** Home's floor: its own [HOME_MIN], or the viewer's custom floor when that is higher. */
    fun homeMin(minBytes: Long): Long = maxOf(HOME_MIN, minBytes)

    /** The alerts' floor: its own [NOTIFY_MIN], or the viewer's custom floor when that is higher. */
    fun notifyMin(minBytes: Long): Long = maxOf(NOTIFY_MIN, minBytes)

    /** Whether the bounds are the ones a fresh install starts with, which is what Reset restores. */
    fun isDefault(minBytes: Long, maxBytes: Long): Boolean =
        minBytes == DEFAULT_MIN && maxBytes >= DEFAULT_MAX

    fun label(bytes: Long): String = L.messages.formatter.sizeLimit(bytes)

    /**
     * The current bounds as a sentence, for the custom limits line in the Sort sheet.
     *
     * Four cases rather than one template, because the open ends are not values a viewer can be
     * shown: "between No minimum and 2.5 GB" is not English.
     */
    fun describe(minBytes: Long, maxBytes: Long): String = L.messages.formatter.sizeRange(minBytes, maxBytes)

    /** What the size limits let through, and how many they turned away. */
    data class Split<T>(val kept: List<T>, val hidden: Int)

    /**
     * [items] through [matches], counting what was left out, in one pass: the count is the hidden
     * set the app itself filtered, so a listing can say "12 small videos hidden" exactly once the
     * whole chat has been read.
     */
    fun <T> split(items: List<T>, minBytes: Long, maxBytes: Long, sizeOf: (T) -> Long): Split<T> {
        val kept = items.filter { matches(sizeOf(it), minBytes, maxBytes) }
        return Split(kept, items.size - kept.size)
    }
}
