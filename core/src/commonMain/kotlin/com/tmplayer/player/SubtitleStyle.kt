package com.tmplayer.player

/**
 * How big subtitles are drawn, with the figure each player needs for it.
 *
 * [fraction] is the share of the picture's height Media3's `SubtitleView` gives a line; [mpvFontSize]
 * is mpv's `sub-font-size`, which is in pixels of a 720 line high frame. Medium is what both players
 * used before the setting existed, so nobody's subtitles change size on the upgrade.
 */
enum class SubtitleSize(val label: String, val fraction: Float, val mpvFontSize: Int) {
    Small("Small", 0.052f, 38),
    Medium("Medium", 0.065f, 46),
    Large("Large", 0.08f, 56),
    ExtraLarge("Extra large", 0.095f, 66),
}

/**
 * How far up the picture subtitles sit.
 *
 * [bottomFraction] is `SubtitleView`'s bottom padding as a share of its height (Media3's own
 * default is 0.08); [mpvSubPos] is mpv's `sub-pos`, where 100 is the bottom. Raised clears the
 * player's own controls and a television's overscan; High clears burnt in text at the bottom.
 */
enum class SubtitlePosition(val label: String, val bottomFraction: Float, val mpvSubPos: Int) {
    Bottom("Bottom", 0.08f, 100),
    Raised("Raised", 0.16f, 92),
    High("High", 0.26f, 82),
}

/**
 * The look of subtitles, shared by the phone, the television and the desktop.
 *
 * A file's own styled subtitles (ASS) keep their styling; this applies to plain text tracks, which
 * is what most videos carry.
 */
data class SubtitleStyle(
    val size: SubtitleSize = SubtitleSize.Medium,
    /** A dark box behind the text, for subtitles over a bright or busy picture. */
    val box: Boolean = false,
    val position: SubtitlePosition = SubtitlePosition.Bottom,
) {
    /** The next size up, wrapping back to the smallest: what a single remote button can drive. */
    fun nextSize(): SubtitleStyle = copy(size = SubtitleSize.entries.cycle(size))

    fun nextPosition(): SubtitleStyle = copy(position = SubtitlePosition.entries.cycle(position))

    companion object {
        /** Reads stored names back, falling back to the default for anything unknown or missing. */
        fun from(size: String?, box: Boolean?, position: String?) = SubtitleStyle(
            size = SubtitleSize.entries.firstOrNull { it.name == size } ?: SubtitleSize.Medium,
            box = box ?: false,
            position = SubtitlePosition.entries.firstOrNull { it.name == position } ?: SubtitlePosition.Bottom,
        )

        private fun <T> List<T>.cycle(current: T): T = this[(indexOf(current) + 1) % size]
    }
}

/**
 * How far a file's subtitles and sound are moved against the picture, remembered per file.
 *
 * Positive is later: subtitles that show up before the line is spoken, or sound that runs ahead of
 * the lips, are fixed with a positive figure. Per file rather than per series, because a bad offset
 * belongs to one release, and the next episode is as likely to be fine.
 */
data class SyncDelays(val subtitleMs: Long = 0, val audioMs: Long = 0) {
    val isZero: Boolean get() = subtitleMs == 0L && audioMs == 0L

    /** "120,-300": milliseconds, subtitles first. Null for no offset, so nothing is stored. */
    fun encode(): String? = if (isZero) null else "$subtitleMs,$audioMs"

    companion object {
        /** One press of Earlier or Later. */
        const val STEP_MS = 100L

        /**
         * As far as either can go. A subtitle file for another cut of the film can be minutes out,
         * but that is a different file, not something to fix a tenth of a second at a time.
         */
        const val MAX_MS = 10_000L

        fun decode(encoded: String?): SyncDelays {
            val parts = encoded?.split(',') ?: return SyncDelays()
            if (parts.size != 2) return SyncDelays()
            return SyncDelays(
                subtitleMs = clamp(parts[0].toLongOrNull() ?: 0L),
                audioMs = clamp(parts[1].toLongOrNull() ?: 0L),
            )
        }

        /** [current] moved one step later ([direction] 1) or earlier (-1), within [MAX_MS]. */
        fun step(current: Long, direction: Int): Long = clamp(current + direction * STEP_MS)

        fun clamp(ms: Long): Long {
            // Snapped onto the step, so a stored figure never shows as "+0.25 s".
            val snapped = Math.round(ms / STEP_MS.toDouble()) * STEP_MS
            return snapped.coerceIn(-MAX_MS, MAX_MS)
        }

        /** "+0.3 s", "-1.2 s", "0 s". A plain hyphen for minus: it is what every keyboard has. */
        fun label(ms: Long): String {
            if (ms == 0L) return "0 s"
            val sign = if (ms > 0) "+" else "-"
            val abs = kotlin.math.abs(ms)
            return "$sign${abs / 1000}.${(abs % 1000) / 100} s"
        }
    }
}
