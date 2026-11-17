package com.tmplayer.player

import com.tmplayer.i18n.L

/**
 * How big subtitles are drawn, with the figure each player needs for it.
 *
 * [fraction] is the share of the picture's height Media3's `SubtitleView` gives a line; [mpvFontSize]
 * is the size in pixels of a 720 line high frame mpv draws a plain line at. Medium is what both
 * players used before the setting existed, so nobody's subtitles change size on the upgrade.
 *
 * A styled track (ASS or SSA) brings its own size, which neither player's own text size reaches:
 * [cueScale] and [mpvScale] are this size against Medium, which both players multiply the track's
 * own size by, so the setting moves every text track (issues 32 and 36).
 */
enum class SubtitleSize(private val labelText: () -> String, val fraction: Float, val mpvFontSize: Int) {
    Small({ L.subtitlesSizeSmall }, 0.052f, 38),
    Medium({ L.subtitlesSizeMedium }, 0.065f, 46),
    Large({ L.subtitlesSizeLarge }, 0.08f, 56),
    ExtraLarge({ L.subtitlesSizeExtraLarge }, 0.095f, 66),
    ;

    val label: String get() = labelText()

    /** How much a cue that carries its own size is scaled on the phone and the television: 1 at Medium. */
    val cueScale: Float get() = fraction / Medium.fraction

    /**
     * mpv's `sub-scale`, "0.826" for Small: this size over Medium's to three places. mpv applies it
     * to plain lines (drawn at Medium's `sub-font-size`, so they land on [mpvFontSize]) and to an
     * ASS track's own sizes alike.
     */
    val mpvScale: String
        get() {
            val medium = Medium.mpvFontSize
            val thousandths = (mpvFontSize * 1000 + medium / 2) / medium
            return "${thousandths / 1000}.${(thousandths % 1000).toString().padStart(3, '0')}"
        }
}

/**
 * How far up the picture subtitles sit.
 *
 * [bottomFraction] is `SubtitleView`'s bottom padding as a share of its height (Media3's own
 * default is 0.08); [mpvSubPos] is mpv's `sub-pos`, where 100 is the bottom. Raised clears the
 * player's own controls and a television's overscan; High clears burnt in text at the bottom.
 */
enum class SubtitlePosition(private val labelText: () -> String, val bottomFraction: Float, val mpvSubPos: Int) {
    Bottom({ L.subtitlesPositionBottom }, 0.08f, 100),
    Raised({ L.subtitlesPositionRaised }, 0.16f, 92),
    High({ L.subtitlesPositionHigh }, 0.26f, 82),
    ;

    val label: String get() = labelText()
}

/**
 * The colour of the letters. [argb] is opaque; the edge and the box are what keep a pale colour
 * readable on a bright frame. Yellow is the old broadcast caption colour, the others are for a
 * viewer who reads one of them more easily.
 */
enum class SubtitleColour(private val labelText: () -> String, val argb: Int) {
    White({ L.subtitlesColourWhite }, 0xFFFFFFFF.toInt()),
    Yellow({ L.subtitlesColourYellow }, 0xFFFFE14D.toInt()),
    Cyan({ L.subtitlesColourCyan }, 0xFF5CE6FF.toInt()),
    Green({ L.subtitlesColourGreen }, 0xFF7CF08A.toInt()),
    Pink({ L.subtitlesColourPink }, 0xFFFF9AE0.toInt()),
    ;

    val label: String get() = labelText()

    /** "#FFE14D", the form mpv's `sub-color` and a CSS colour both take. */
    val hex: String get() = "#" + (argb and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')
}

/**
 * What keeps the letters apart from the picture behind them. Outline is what both players drew
 * before the setting existed, so nobody's subtitles change on the upgrade.
 */
enum class SubtitleEdge(private val labelText: () -> String) {
    Outline({ L.subtitlesEdgeOutline }),
    Shadow({ L.subtitlesEdgeShadow }),
    None({ L.subtitlesEdgeNone }),
    ;

    val label: String get() = labelText()
}

/**
 * The family the letters are drawn in: the system's own sans serif, a serif, or a typewriter
 * (monospace) face. Generic families rather than bundled fonts, so nothing is added to the APK
 * and every script the system can draw still draws.
 */
enum class SubtitleFace(private val labelText: () -> String) {
    Default({ L.subtitlesFaceDefault }),
    Serif({ L.subtitlesFaceSerif }),
    Monospace({ L.subtitlesFaceMonospace }),
    ;

    val label: String get() = labelText()
}

/**
 * The dark box behind the text and how dark it is, from off to solid black. [percent] is the
 * box's opacity.
 */
enum class SubtitleBox(val percent: Int) {
    Off(0),
    Light(35),
    Medium(60),
    Dark(80),
    Solid(100),
    ;

    /** "Off", or "60 %". */
    val label: String get() = if (this == Off) L.commonOff else L.subtitlesBoxPercent(percent)

    /** Black at [percent], as an ARGB colour; transparent for [Off]. */
    val argb: Int get() = ((percent * 255 + 50) / 100) shl 24

    companion object {
        /**
         * What a stored "box on" from before the darkness steps meant. The phone drew the box
         * at 75 % and the desktop at 70 %; the nearest step is Dark.
         */
        val LEGACY_ON = Dark
    }
}

/**
 * The look of subtitles, shared by the phone, the television and the desktop.
 *
 * A file's own styled subtitles (ASS) keep their colours, fonts and placed signs; the size and the
 * height of the bottom lines follow this look on them too ([SubtitleSize.cueScale],
 * [SubtitleSize.mpvScale], [AssMargins]), since a track converted to ASS is what many videos carry
 * and a setting that skipped it read as broken (issues 32 and 36). The rest applies to plain text
 * tracks.
 */
data class SubtitleStyle(
    val size: SubtitleSize = SubtitleSize.Medium,
    /** A dark box behind the text, for subtitles over a bright or busy picture, and how dark. */
    val boxLevel: SubtitleBox = SubtitleBox.Off,
    val position: SubtitlePosition = SubtitlePosition.Bottom,
    val colour: SubtitleColour = SubtitleColour.White,
    val edge: SubtitleEdge = SubtitleEdge.Outline,
    val face: SubtitleFace = SubtitleFace.Default,
    val bold: Boolean = false,
) {
    /** Whether there is a box at all. */
    val box: Boolean get() = boxLevel != SubtitleBox.Off

    /** Nothing changed from the look subtitles have out of the box, so "Back to the default look" has nothing to do. */
    val isDefault: Boolean get() = this == SubtitleStyle()

    /** The next size up, wrapping back to the smallest: what a single remote button can drive. */
    fun nextSize(): SubtitleStyle = copy(size = SubtitleSize.entries.cycle(size))

    fun nextPosition(): SubtitleStyle = copy(position = SubtitlePosition.entries.cycle(position))

    fun nextColour(): SubtitleStyle = copy(colour = SubtitleColour.entries.cycle(colour))

    fun nextEdge(): SubtitleStyle = copy(edge = SubtitleEdge.entries.cycle(edge))

    fun nextFace(): SubtitleStyle = copy(face = SubtitleFace.entries.cycle(face))

    fun nextBox(): SubtitleStyle = copy(boxLevel = SubtitleBox.entries.cycle(boxLevel))

    /** "Regular" or "Bold". */
    val weightLabel: String get() = if (bold) L.subtitlesWeightBold else L.subtitlesWeightRegular

    /**
     * The look in a few words, for the row that opens it: "Medium · White · Outline · Bottom",
     * with the box, the face and the weight named only when they are not the default.
     */
    fun summary(): String = listOfNotNull(
        size.label,
        colour.label,
        if (box) L.subtitlesBoxSummary(boxLevel.percent) else edge.label,
        face.label.takeIf { face != SubtitleFace.Default },
        weightLabel.takeIf { bold },
        position.label,
    ).joinToString("  ·  ")

    /**
     * The mpv properties that draw this look, in the order they are set: each on its own, so one
     * a libmpv does not know leaves the others applied. [font] is the family name the desktop's
     * font provider resolves for [face] (see the desktop's engine), so this stays free of any one
     * operating system's font names.
     *
     * With a box, the edge is left off: mpv's `background-box` draws one box behind the whole
     * subtitle in `sub-back-color` and an outline round letters inside a box only thickens them.
     * mpv's colours are #AARRGGBB.
     */
    fun mpvProperties(font: String): List<Pair<String, String>> = buildList {
        // The size is the scale, not the font size: `sub-font-size` never reaches an ASS track
        // (mpv's `sub-ass-override` stays at `scale`), `sub-scale` reaches both.
        add("sub-font-size" to SubtitleSize.Medium.mpvFontSize.toString())
        add("sub-scale" to this@SubtitleStyle.size.mpvScale)
        add("sub-pos" to position.mpvSubPos.toString())
        add("sub-color" to colour.hex)
        add("sub-font" to font)
        add("sub-bold" to if (bold) "yes" else "no")
        if (box) {
            add("sub-back-color" to mpvColour(boxLevel.argb))
            add("sub-border-size" to "0")
            add("sub-shadow-offset" to "0")
            add("sub-border-style" to "background-box")
            return@buildList
        }
        add("sub-border-style" to "outline-and-shadow")
        when (edge) {
            SubtitleEdge.Outline -> {
                add("sub-outline-color" to "#FF000000")
                add("sub-border-size" to MPV_OUTLINE)
                add("sub-shadow-offset" to "0")
            }
            SubtitleEdge.Shadow -> {
                add("sub-border-size" to "0")
                add("sub-shadow-color" to MPV_SHADOW_COLOUR)
                add("sub-shadow-offset" to MPV_SHADOW_OFFSET)
            }
            SubtitleEdge.None -> {
                add("sub-border-size" to "0")
                add("sub-shadow-offset" to "0")
            }
        }
    }

    companion object {
        /** What both players drew before the setting, in mpv's units of a 720 line frame. */
        const val MPV_OUTLINE = "2.5"
        const val MPV_SHADOW_OFFSET = "2"
        const val MPV_SHADOW_COLOUR = "#C0000000"

        /** "#B3000000" for black at 70 %. */
        fun mpvColour(argb: Int): String = "#" + (argb.toLong() and 0xFFFFFFFFL).toString(16).uppercase().padStart(8, '0')

        /** Reads stored names back, falling back to the default for anything unknown or missing. */
        fun from(size: String?, box: Boolean?, position: String?) = from(size, box, null, position, null, null, null, null)

        /**
         * Reads stored names back. [boxLevel] is the darkness step; a store from before the steps
         * has only [box], which reads as [SubtitleBox.LEGACY_ON] when it was on.
         */
        fun from(
            size: String?,
            box: Boolean?,
            boxLevel: String?,
            position: String?,
            colour: String?,
            edge: String?,
            face: String?,
            bold: Boolean?,
        ) = SubtitleStyle(
            size = SubtitleSize.entries.firstOrNull { it.name == size } ?: SubtitleSize.Medium,
            boxLevel = SubtitleBox.entries.firstOrNull { it.name == boxLevel }
                ?: if (box == true) SubtitleBox.LEGACY_ON else SubtitleBox.Off,
            position = SubtitlePosition.entries.firstOrNull { it.name == position } ?: SubtitlePosition.Bottom,
            colour = SubtitleColour.entries.firstOrNull { it.name == colour } ?: SubtitleColour.White,
            edge = SubtitleEdge.entries.firstOrNull { it.name == edge } ?: SubtitleEdge.Outline,
            face = SubtitleFace.entries.firstOrNull { it.name == face } ?: SubtitleFace.Default,
            bold = bold ?: false,
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
            val snapped = kotlin.math.round(ms / STEP_MS.toDouble()).toLong() * STEP_MS
            return snapped.coerceIn(-MAX_MS, MAX_MS)
        }

        /** "+0.3 s", "-1.2 s", "0 s". A plain hyphen for minus: it is what every keyboard has. */
        fun label(ms: Long): String {
            if (ms == 0L) return L.formatSecondsShort(L.messages.formatter.number(0))
            val sign = if (ms > 0) "+" else "-"
            val tenths = kotlin.math.abs(ms) / 100
            return L.formatSecondsShort(sign + L.messages.formatter.decimal(tenths / 10.0, 1))
        }
    }
}
