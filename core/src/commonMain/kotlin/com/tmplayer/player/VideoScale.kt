package com.tmplayer.player

/**
 * How the picture is fitted to the screen, and the order the control steps through.
 *
 * A 2.39:1 video on a 16:9 panel is mostly black bars, so the viewer can trade some of the sides
 * away to fill the glass. Three stops is the whole useful range: the true shape, cropped to fill,
 * and stretched to fill.
 *
 * The integers are Media3's own `AspectRatioFrameLayout.RESIZE_MODE_*` values, kept here as
 * literals so this stays pure and testable rather than dragging a view class into a unit test.
 */
enum class VideoScale(val resizeMode: Int, val label: String) {
    /** The video's own aspect, letterboxed. What everything starts on. */
    Fit(0, "Fit"),

    /** Cropped to fill the screen, keeping the aspect: the bars go, the edges go with them. */
    Crop(4, "Crop"),

    /** Stretched to fill the screen. Wrong shape, but it is the viewer's screen. */
    Stretch(3, "Stretch"),
    ;

    fun next(): VideoScale = entries[(ordinal + 1) % entries.size]

    companion object {
        /** By name, so a stop added or reordered later cannot move everybody's saved choice. */
        fun from(stored: String?): VideoScale =
            entries.firstOrNull { it.name == stored } ?: Fit
    }
}
