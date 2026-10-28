package com.tmplayer.player

import com.tmplayer.i18n.L
import kotlin.math.roundToInt

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

    /**
     * The picture's size, in pixels, drawn this way in a [boxWidth] by [boxHeight] box, for a
     * picture [aspect] wide over high in square pixels. Centred in the box; Fill comes out larger
     * than the box on one side, the part cut away. An aspect not known yet (0, or not a number)
     * reads as the box's own, so nothing jumps before the first frame says what it is.
     *
     * Where the player draws the shape itself (3D's effects pipeline, Android), this is what it
     * draws, and the subtitles go over this box rather than over the whole screen.
     */
    fun pictureSize(boxWidth: Int, boxHeight: Int, aspect: Double): Pair<Int, Int> {
        if (boxWidth <= 0 || boxHeight <= 0) return 0 to 0
        if (this == Stretch) return boxWidth to boxHeight
        val box = boxWidth.toDouble() / boxHeight
        val shape = ratio ?: aspect.takeIf { it.isFinite() && it > 0.0 } ?: box
        // Fit is held by the box's tighter side; Fill by its looser one.
        val wide = if (this == Crop) shape < box else shape > box
        return if (wide) {
            boxWidth to (boxWidth / shape).roundToInt().coerceAtLeast(1)
        } else {
            (boxHeight * shape).roundToInt().coerceAtLeast(1) to boxHeight
        }
    }

    /**
     * [pictureSize] as a share of the box across and down: 1 fills that side, under 1 leaves bars,
     * over 1 is cut. What a shader needs to place the picture in each eye's half of a VR pair.
     */
    fun boxShare(boxWidth: Int, boxHeight: Int, aspect: Double): Pair<Double, Double> {
        if (boxWidth <= 0 || boxHeight <= 0) return 1.0 to 1.0
        val (width, height) = pictureSize(boxWidth, boxHeight, aspect)
        return width.toDouble() / boxWidth to height.toDouble() / boxHeight
    }

    companion object {
        /** By name, so a stop added or reordered later cannot move everybody's saved choice. */
        fun from(stored: String?): VideoScale =
            entries.firstOrNull { it.name == stored } ?: Fit

        /** The shape a new player starts on: a fixed shape is never carried to another video. */
        fun remembered(stored: String?): VideoScale = from(stored).takeUnless { it.fixed } ?: Fit
    }
}
