package com.tmplayer.player

import com.tmplayer.i18n.L

/**
 * How the picture is fitted to the screen, and the order the control steps through.
 *
 * A 2.39:1 video on a 16:9 panel is mostly black bars, so the viewer can trade some of the sides
 * away to fill the glass: the true shape (Original), cropped to fill (Fill) and stretched to fill.
 * The fixed shapes after them draw the picture at that width over height, fitted inside the
 * screen, as VLC's and mpv's aspect override do: for a file whose shape is stored wrong, such as a
 * squeezed side by side 3D video watched flat (HX16).
 *
 * The integers are Media3's own `AspectRatioFrameLayout.RESIZE_MODE_*` values, kept here as
 * literals so this stays pure and testable rather than dragging a view class into a unit test. A
 * fixed shape fits ([resizeMode] 0) a frame given [ratio] as its aspect.
 */
enum class VideoScale(val resizeMode: Int, private val labelText: () -> String, val ratio: Double? = null) {
    /** The video's own aspect, letterboxed. What everything starts on. Named "Original". */
    Fit(0, { L.playerScaleFit }),

    /** Cropped to fill the screen, keeping the aspect: the bars go, the edges go with them. Named "Fill". */
    Crop(4, { L.playerScaleCrop }),

    /** Stretched to fill the screen. Wrong shape, but it is the viewer's screen. */
    Stretch(3, { L.playerScaleStretch }),

    Ratio16x9(0, { "16:9" }, 16.0 / 9.0),
    Ratio4x3(0, { "4:3" }, 4.0 / 3.0),
    Ratio21x9(0, { "21:9" }, 21.0 / 9.0),
    Ratio235(0, { "2.35:1" }, 2.35),
    Square(0, { "1:1" }, 1.0),
    ;

    val label: String get() = labelText()

    /** A fixed shape, kept for this player only; the first three are kept across videos. */
    val fixed: Boolean get() = ratio != null

    /** The step a pinch moves along: the first three only, a pinch is about filling the screen. */
    fun pinch(wider: Boolean): VideoScale {
        val at = if (fixed) Fit.ordinal else ordinal
        return entries[if (wider) (at + 1).coerceAtMost(Stretch.ordinal) else (at - 1).coerceAtLeast(0)]
    }

    fun next(): VideoScale = entries[(ordinal + 1) % entries.size]

    companion object {
        /** By name, so a stop added or reordered later cannot move everybody's saved choice. */
        fun from(stored: String?): VideoScale =
            entries.firstOrNull { it.name == stored } ?: Fit

        /** The shape a new player starts on: a fixed shape is never carried to another video. */
        fun remembered(stored: String?): VideoScale = from(stored).takeUnless { it.fixed } ?: Fit
    }
}
