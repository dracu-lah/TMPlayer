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
    /**
     * The size as a fine step, 50 to 250 per cent of Medium in tens (WP-29), or 0 to use the preset
     * [size]. A style stored before the fine steps has 0 here, so nothing changes size on the upgrade;
     * [fineSizePercent] is the nearest step a preset stands for.
     */
    val sizePercent: Int = 0,
    /** How far up the picture the bottom line sits, 0 to 30 per cent of its height, or -1 for the preset [position]. */
    val positionPercent: Int = -1,
    /** A colour of [CUSTOM_COLOURS] for the letters, or 0 for the preset [colour]. */
    val customColour: Int = 0,
) {
    /** The size against Medium: 1 at Medium, from the fine step when there is one. */
    val sizeScale: Float get() = if (sizePercent > 0) sizePercent / 100f else size.cueScale

    /** The share of the picture's height a line is given (Media3's `SubtitleView` text size). */
    val fraction: Float get() = SubtitleSize.Medium.fraction * sizeScale

    /** [SubtitleSize.cueScale] for the fine size too: what a styled track's own sizes are multiplied by. */
    val cueScale: Float get() = sizeScale

    /** mpv's `sub-scale`, to three places. */
    val mpvScale: String
        get() {
            if (sizePercent <= 0) return size.mpvScale
            val thousandths = sizePercent * 10
            return "${thousandths / 1000}.${(thousandths % 1000).toString().padStart(3, '0')}"
        }

    /** The nearest fine step of the size now, so the stepper starts from what is on screen. */
    val fineSizePercent: Int
        get() = if (sizePercent > 0) sizePercent else (kotlin.math.round(size.cueScale * 10f).toInt() * 10).coerceIn(SIZE_MIN, SIZE_MAX)

    /** `SubtitleView`'s bottom padding as a share of its height. */
    val bottomFraction: Float get() = if (positionPercent >= 0) positionPercent / 100f else position.bottomFraction

    /** mpv's `sub-pos`: 100 is the bottom. */
    val mpvSubPos: Int get() = if (positionPercent >= 0) 100 - positionPercent else position.mpvSubPos

    /** The height now as a whole per cent from the bottom, for the stepper. */
    val positionNowPercent: Int
        get() = if (positionPercent >= 0) positionPercent else kotlin.math.round(position.bottomFraction * 100f).toInt().coerceIn(0, POSITION_MAX)

    /** The letters' colour as an opaque ARGB. */
    val colourArgb: Int get() = if (customColour != 0) customColour else colour.argb

    /** "#FFE14D" for [colourArgb]. */
    val colourHex: String get() = "#" + (colourArgb and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')

    /** One fine step larger or smaller ([direction] 1 or -1), held between 50 and 250 per cent. */
    fun stepSize(direction: Int): SubtitleStyle =
        copy(sizePercent = (fineSizePercent + direction * SIZE_STEP).coerceIn(SIZE_MIN, SIZE_MAX))

    /** The bottom line higher ([direction] 1) or lower (-1) by two per cent of the height, held between 0 and 30. */
    fun stepPosition(direction: Int): SubtitleStyle =
        copy(positionPercent = (positionNowPercent + direction * POSITION_STEP).coerceIn(0, POSITION_MAX))

    /** Whether there is a box at all. */
    val box: Boolean get() = boxLevel != SubtitleBox.Off

    /** Nothing changed from the look subtitles have out of the box, so "Back to the default look" has nothing to do. */
    val isDefault: Boolean get() = this == SubtitleStyle()

    /** The next size up, wrapping back to the smallest: what a single remote button can drive. */
    fun nextSize(): SubtitleStyle = copy(size = SubtitleSize.entries.cycle(size), sizePercent = 0)

    fun nextPosition(): SubtitleStyle = copy(position = SubtitlePosition.entries.cycle(position), positionPercent = -1)

    fun nextColour(): SubtitleStyle = copy(colour = SubtitleColour.entries.cycle(colour), customColour = 0)

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
        if (sizePercent > 0) L.messages.formatter.scalePercent(sizePercent) else size.label,
        if (customColour != 0) colourHex else colour.label,
        if (box) L.subtitlesBoxSummary(boxLevel.percent) else edge.label,
        face.label.takeIf { face != SubtitleFace.Default },
        weightLabel.takeIf { bold },
        if (positionPercent >= 0) L.messages.formatter.scalePercent(positionPercent) else position.label,
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
        add("sub-scale" to mpvScale)
        add("sub-pos" to mpvSubPos.toString())
        add("sub-color" to colourHex)
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
            sizePercent: Long? = null,
            positionPercent: Long? = null,
            customColour: Long? = null,
        ) = SubtitleStyle(
            size = SubtitleSize.entries.firstOrNull { it.name == size } ?: SubtitleSize.Medium,
            boxLevel = SubtitleBox.entries.firstOrNull { it.name == boxLevel }
                ?: if (box == true) SubtitleBox.LEGACY_ON else SubtitleBox.Off,
            position = SubtitlePosition.entries.firstOrNull { it.name == position } ?: SubtitlePosition.Bottom,
            colour = SubtitleColour.entries.firstOrNull { it.name == colour } ?: SubtitleColour.White,
            edge = SubtitleEdge.entries.firstOrNull { it.name == edge } ?: SubtitleEdge.Outline,
            face = SubtitleFace.entries.firstOrNull { it.name == face } ?: SubtitleFace.Default,
            bold = bold ?: false,
            sizePercent = sanitiseSizePercent(sizePercent),
            positionPercent = sanitisePositionPercent(positionPercent),
            customColour = sanitiseCustomColour(customColour),
        )

        /** The fine size steps: 50 to 250 per cent of Medium in tens. */
        const val SIZE_MIN = 50
        const val SIZE_MAX = 250
        const val SIZE_STEP = 10

        /** The bottom line's height, 0 to 30 per cent, by two. */
        const val POSITION_MAX = 30
        const val POSITION_STEP = 2

        /**
         * The colours the fine pickers offer (the same 16 hues the television's sub-page draws), each
         * opaque. Index 0 is white, the default.
         */
        val CUSTOM_COLOURS: List<Int> = listOf(
            0xFFFFFFFFL, 0xFFFFE14DL, 0xFFFFB347L, 0xFFFF6B6BL, 0xFFFF9AE0L, 0xFFD08CFFL, 0xFF8C9EFFL, 0xFF5CB8FFL,
            0xFF5CE6FFL, 0xFF5CF0C8L, 0xFF7CF08AL, 0xFFB8F05CL, 0xFFE6E6E6L, 0xFFBFBFBFL, 0xFFA3C2A0L, 0xFFE8D2A6L,
        ).map { it.toInt() }

        /** A stored size off the steps (or garbage) is no fine size: the preset stays. */
        fun sanitiseSizePercent(value: Long?): Int =
            value?.takeIf { it in SIZE_MIN..SIZE_MAX && it % SIZE_STEP == 0L }?.toInt() ?: 0

        fun sanitisePositionPercent(value: Long?): Int =
            value?.takeIf { it in 0..POSITION_MAX }?.toInt() ?: -1

        /** Only a colour of [CUSTOM_COLOURS], stored as its unsigned value, counts; anything else is the preset. */
        fun sanitiseCustomColour(value: Long?): Int {
            if (value == null || value < 0L || value > 0xFFFFFFFFL) return 0
            val argb = value.toInt()
            return if (argb in CUSTOM_COLOURS) argb else 0
        }

        /** [argb] as the unsigned value the store keeps. */
        fun storedColour(argb: Int): Long = argb.toLong() and 0xFFFFFFFFL

        /** The longest stored per-video look read back (WP-29): a longer value is garbage. */
        const val ENCODED_MAX = 300

        /**
         * One video's own look as one short line ("This video only", WP-29), kept beside its offsets.
         * Names and numbers only; [decode] reads it back through the same checks as the stored look.
         */
        fun encode(style: SubtitleStyle): String = listOf(
            "size=${style.size.name}", "box=${style.boxLevel.name}", "pos=${style.position.name}",
            "col=${style.colour.name}", "edge=${style.edge.name}", "face=${style.face.name}",
            "bold=${if (style.bold) 1 else 0}", "sp=${style.sizePercent}", "pp=${style.positionPercent}",
            "cc=${storedColour(style.customColour)}",
        ).joinToString(";")

        /** Null for nothing stored, or anything cut short, wrong typed, too long or not a look at all. */
        fun decode(text: String?): SubtitleStyle? {
            if (text.isNullOrBlank() || text.length > ENCODED_MAX) return null
            val parts = HashMap<String, String>()
            for (piece in text.split(';')) {
                val at = piece.indexOf('=')
                if (at <= 0) continue
                parts[piece.substring(0, at)] = piece.substring(at + 1)
            }
            if (parts.isEmpty()) return null
            return from(
                size = parts["size"], box = null, boxLevel = parts["box"], position = parts["pos"],
                colour = parts["col"], edge = parts["edge"], face = parts["face"], bold = parts["bold"] == "1",
                sizePercent = parts["sp"]?.toLongOrNull(), positionPercent = parts["pp"]?.toLongOrNull(),
                customColour = parts["cc"]?.toLongOrNull(),
            )
        }

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
        const val MAX_MS = 60_000L

        /**
         * How far ahead of the picture a subtitle track inside the video file may be shown (PB2-11):
         * an earlier line needs the file read that far ahead, and a TV stick's buffer holds 15 to 30
         * seconds, so further than this the lines go missing. A subtitle file loaded beside the video
         * is read whole and keeps the full [MAX_MS].
         */
        const val EMBEDDED_LEAD_MS = 10_000L

        /** The subtitle delay put in force for a track inside the file (not [sideLoaded]): no earlier than [EMBEDDED_LEAD_MS]. */
        fun subtitleApplied(ms: Long, sideLoaded: Boolean): Long = if (sideLoaded) ms else ms.coerceAtLeast(-EMBEDDED_LEAD_MS)

        /** The sound stays at ten seconds: its delay is a buffer of audio held in memory, which a minute would make large. */
        const val AUDIO_MAX_MS = 10_000L

        /** A long press on Earlier or Later (WP-29): a whole second at a time, so a minute is not six hundred presses. */
        const val LONG_STEP_MS = 1_000L

        fun decode(encoded: String?): SyncDelays {
            val parts = encoded?.split(',') ?: return SyncDelays()
            if (parts.size != 2) return SyncDelays()
            return SyncDelays(
                subtitleMs = clamp(parts[0].toLongOrNull() ?: 0L),
                audioMs = clamp(parts[1].toLongOrNull() ?: 0L).coerceIn(-AUDIO_MAX_MS, AUDIO_MAX_MS),
            )
        }

        /** [current] moved one step later ([direction] 1) or earlier (-1), within [MAX_MS]. */
        fun step(current: Long, direction: Int): Long = clamp(current + direction * STEP_MS)

        /** The sound's own step: as [step], held to [AUDIO_MAX_MS]. [long] is a second, for the long press. */
        fun stepAudio(current: Long, direction: Int, long: Boolean = false): Long =
            (if (long) stepLong(current, direction) else step(current, direction)).coerceIn(-AUDIO_MAX_MS, AUDIO_MAX_MS)

        /** [current] moved a second later ([direction] 1) or earlier (-1), within [MAX_MS]. */
        fun stepLong(current: Long, direction: Int): Long = clamp(current + direction * LONG_STEP_MS)

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
