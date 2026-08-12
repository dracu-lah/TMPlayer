package com.tmplayer.ui.theme

import androidx.compose.material3.MaterialTheme as M3
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.tmplayer.data.FormFactor

/**
 * A colour by the job it does, rather than by its hex.
 *
 * The phone now has a light theme and, on Android 12 and later, a palette taken from the viewer's
 * wallpaper, so a screen that names `SurfaceDark` is a screen that will be dark grey on a white
 * background. The television now has a light mode of its own as well, drawn from [TvPalette]
 * rather than from a Material scheme, because TV Material takes its colours from call sites. Every
 * colour a screen draws with therefore has to be asked for by role and answered per device, which
 * is what this is.
 *
 * Read at the point of use. These are cheap: one composition-local lookup and a branch.
 */
object Tone {

    /** Behind everything. */
    val background: Color
        @Composable @ReadOnlyComposable get() =
            if (touch()) M3.colorScheme.background else tv().background

    /** A card, a sheet, a row that is meant to read as sitting on the background. */
    val surface: Color
        @Composable @ReadOnlyComposable get() =
            if (touch()) M3.colorScheme.surfaceContainerLow else tv().surface

    /** The step above [surface]: a control inside a card, a track, a chip at rest. */
    val surfaceHigh: Color
        @Composable @ReadOnlyComposable get() =
            if (touch()) M3.colorScheme.surfaceContainerHigh else tv().surfaceHigh

    /** Text and icons that carry the meaning. */
    val text: Color
        @Composable @ReadOnlyComposable get() =
            if (touch()) M3.colorScheme.onSurface else tv().text

    /** Second-line text: still readable, never competing. */
    val muted: Color
        @Composable @ReadOnlyComposable get() =
            if (touch()) M3.colorScheme.onSurfaceVariant else tv().muted

    /** The app's own colour, or the wallpaper's, wherever something is selected or live. */
    val accent: Color
        @Composable @ReadOnlyComposable get() =
            if (touch()) M3.colorScheme.primary else tv().accent

    /** What to write on top of [accent]. */
    val onAccent: Color
        @Composable @ReadOnlyComposable get() =
            if (touch()) M3.colorScheme.onPrimary else tv().onAccent

    /** A quieter [accent]: a selected row, a filled chip, a badge. */
    val accentContainer: Color
        @Composable @ReadOnlyComposable get() =
            if (touch()) M3.colorScheme.secondaryContainer else tv().surfaceHigh

    val onAccentContainer: Color
        @Composable @ReadOnlyComposable get() =
            if (touch()) M3.colorScheme.onSecondaryContainer else tv().text

    /** Hairlines and borders. */
    val outline: Color
        @Composable @ReadOnlyComposable get() =
            if (touch()) M3.colorScheme.outlineVariant else tv().outline

    /** Something failed, or the press about to happen cannot be taken back. */
    val danger: Color
        @Composable @ReadOnlyComposable get() =
            if (touch()) M3.colorScheme.error else tv().danger

    /**
     * Worth noticing, nothing has gone wrong.
     *
     * Amber on a white background is close to unreadable, so the light theme takes it several
     * tones down rather than reusing the panel's colour and calling it consistent.
     */
    val caution: Color
        @Composable @ReadOnlyComposable get() = when {
            !touch() -> tv().caution
            LocalDarkTheme.current -> Caution
            else -> Color(0xFF8A5300)
        }
}

@Composable
@ReadOnlyComposable
private fun touch(): Boolean = !FormFactor.isTv(LocalContext.current)

/** The television's colours as they currently stand, light or dark. */
@Composable
@ReadOnlyComposable
private fun tv(): TvPalette = LocalTvPalette.current
