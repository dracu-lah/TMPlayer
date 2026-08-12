package com.tmplayer.ui.theme

import androidx.compose.material3.MaterialTheme as M3
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * A colour by the job it does, rather than by its hex.
 *
 * Both devices now draw from the same Material scheme, in light or dark, and on Android 12 and
 * later a phone can take that scheme from the viewer's wallpaper. So a screen that names
 * `SurfaceDark` is a screen that will be dark grey on a white background, and the whole app asks
 * for its colours by the job they do instead. This is that list of jobs.
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
     * Not [accent], which is the wrong end of the scale in daylight. Material puts the light
     * theme's primary at tone 40 because it has to carry white text at 4.5:1, and a colour that
     * can do that is by definition dark: fill a settings row the width of a television with it
     * and the light theme grows a black bar across it. The dark theme has the opposite luck, its
     * primary being tone 80, so the same code drew a bright block there and a heavy one here.
     *
     * The container roles are the pair Material means for a large area, and they sit on the near
     * side of the background in both themes: pale blue on white, deep blue on black. So focus is
     * a block of colour either way, and a light screen stays a light screen.
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

    /** A quieter [accent]: a selected row, a filled chip, a badge. */
    val accentContainer: Color
        @Composable @ReadOnlyComposable get() =
            M3.colorScheme.secondaryContainer

    val onAccentContainer: Color
        @Composable @ReadOnlyComposable get() =
            M3.colorScheme.onSecondaryContainer

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
     * [onAccent] is the better answer wherever the background is [accent], because the scheme
     * tuned that pair rather than guessing it. This is for the few places carrying a colour of
     * their own, the amber update item among them, where there is no pair to look up. The
     * threshold is where black and white come out equally readable under the WCAG contrast
     * formula, so each side is picked only while it is the better of the two.
     */
    fun readableOn(background: Color): Color =
        if (background.luminance() > 0.1791f) Color(0xFF10131A) else Color.White

    /**
     * Worth noticing, nothing has gone wrong.
     *
     * Amber on a white background is close to unreadable, so the light theme takes it several
     * tones down rather than reusing the panel's colour and calling it consistent.
     */
    val caution: Color
        @Composable @ReadOnlyComposable get() =
            if (LocalDarkTheme.current) Caution else Color(0xFF8A5300)
}
