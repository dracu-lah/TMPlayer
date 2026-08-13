package com.tmplayer.ui.theme

import androidx.compose.material3.MaterialTheme as M3
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * A colour by the job it does, rather than by its hex.
 *
 * Both devices draw from the same Material scheme, in light or dark, and on Android 12 and later a
 * phone can take that scheme from the viewer's wallpaper, so no screen may name a fixed colour.
 * This is the list of jobs to ask for instead.
 *
 * Read at the point of use. These are cheap: one composition-local lookup and a branch.
 */
object Tone {

    /** Behind everything. */
    val background: Color
        @Composable @ReadOnlyComposable get() =
            M3.colorScheme.background

    /** A card, a sheet, a row that is meant to read as sitting on the background. */
    val surface: Color
        @Composable @ReadOnlyComposable get() =
            M3.colorScheme.surfaceContainerLow

    /** The step above [surface]: a control inside a card, a track, a chip at rest. */
    val surfaceHigh: Color
        @Composable @ReadOnlyComposable get() =
            M3.colorScheme.surfaceContainerHigh

    /** Text and icons that carry the meaning. */
    val text: Color
        @Composable @ReadOnlyComposable get() =
            M3.colorScheme.onSurface

    /** Second-line text: still readable, never competing. */
    val muted: Color
        @Composable @ReadOnlyComposable get() =
            M3.colorScheme.onSurfaceVariant

    /** The app's own colour, or the wallpaper's, wherever something is selected or live. */
    val accent: Color
        @Composable @ReadOnlyComposable get() =
            M3.colorScheme.primary

    /** What to write on top of [accent]. */
    val onAccent: Color
        @Composable @ReadOnlyComposable get() =
            M3.colorScheme.onPrimary

    /**
     * A whole row, card or pill filled in to say "this is where the remote is".
     *
     * Not [accent]: the light theme's primary is tone 40, dark enough to carry white text, so a
     * row filled with it puts a black bar across a light screen. The container roles are the pair
     * Material means for a large area and sit near the background in both themes, pale blue on
     * white and deep blue on black, so focus is a block of colour either way.
     */
    val focusFill: Color
        @Composable @ReadOnlyComposable get() =
            if (LocalDarkTheme.current) M3.colorScheme.primary else M3.colorScheme.primaryContainer

    /** What to write on [focusFill]. */
    val onFocusFill: Color
        @Composable @ReadOnlyComposable get() =
            if (LocalDarkTheme.current) {
                M3.colorScheme.onPrimary
            } else {
                M3.colorScheme.onPrimaryContainer
            }

    /** Hairlines and borders. */
    val outline: Color
        @Composable @ReadOnlyComposable get() =
            M3.colorScheme.outlineVariant

    /** Something failed, or the press about to happen cannot be taken back. */
    val danger: Color
        @Composable @ReadOnlyComposable get() =
            M3.colorScheme.error

    /**
     * What to write on a background this list has no "on" role for.
     *
     * Prefer [onAccent] wherever the background is [accent], since the scheme tuned that pair.
     * This is for the few places carrying a colour of their own, the amber update item among them.
     * The threshold is where black and white come out equally readable under the WCAG contrast
     * formula.
     */
    fun readableOn(background: Color): Color =
        if (background.luminance() > 0.1791f) Color(0xFF10131A) else Color.White

    /**
     * Worth noticing, nothing has gone wrong.
     *
     * Amber on a white background is close to unreadable, so the light theme takes it several
     * tones down.
     */
    val caution: Color
        @Composable @ReadOnlyComposable get() =
            if (LocalDarkTheme.current) Caution else Color(0xFF8A5300)
}
