package com.tmplayer.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme as M3
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/*
 * Everything that floats above a screen (a dialog, a sheet, a menu, a card that is not part of the
 * page) and every focus edge, as one set of figures the phone's Material theme, the television's
 * tv-material screens and the desktop all read. Before these existed the television drew its own
 * panels with their own fill, scrim and border, and it looked like a different app.
 *
 * The figures are the phone's, which are Material 3's own: a dialog is surfaceContainerHigh, a
 * sheet surfaceContainerLow and a menu surfaceContainer, the window behind any of them is dimmed
 * by 60 per cent, and each carries a hairline in [Tone.outline] so its edge reads in daylight,
 * where the fill is only a shade off the page.
 */
object Floating {
    /** The hairline every floating surface carries, on every platform. */
    val Border = 1.dp

    /**
     * How far the screen behind a dialog is dimmed: Android's and the desktop's own dialog dim, so
     * a panel the app draws itself and a stock Material dialog sit over the same shade.
     */
    const val SCRIM_ALPHA = 0.6f

    /** Dialogs, sheets and the television's centred menus. */
    val DialogShape: Shape = RoundedCornerShape(Corner.ExtraLarge)

    /** A sheet pinned to the bottom of a phone: rounded where it meets the screen above it. */
    val SheetShape: Shape = RoundedCornerShape(topStart = Corner.ExtraLarge, topEnd = Corner.ExtraLarge)

    /** A menu hanging off a pointer or a button: Material's own menu corner. */
    val MenuShape: Shape = RoundedCornerShape(Corner.ExtraSmall)
}

/** The edge that says "this is where the remote, or the keyboard, is". */
object Focus {
    /** Round a tile, a card or a chat row: thick enough to find from a sofa. */
    val Edge = 3.dp

    /**
     * Round a row that also fills under focus, in daylight only (see [focusRing]): the fill is the
     * signal there, and the ring is what lifts it above the page.
     */
    val Ring = 2.dp
}

/**
 * True inside a dialog or a sheet. A control at rest takes the step above the surface it sits on,
 * and a dialog is already [Tone.dialog], so its controls take the step above that.
 */
val LocalOnFloating = staticCompositionLocalOf { false }

/** The role each floating thing is filled with, by what it is. */
object FloatingTone {
    /** A dialog: a question, a prompt, the QR codes. */
    val dialog: Color
        @Composable @ReadOnlyComposable get() = M3.colorScheme.surfaceContainerHigh

    /** A sheet of choices, and the television's menu, which is the phone's sheet in the middle of the screen. */
    val sheet: Color
        @Composable @ReadOnlyComposable get() = M3.colorScheme.surfaceContainerLow

    /** A menu hanging off a pointer or a button. */
    val menu: Color
        @Composable @ReadOnlyComposable get() = M3.colorScheme.surfaceContainer

    /** The dim behind a dialog. */
    val scrim: Color
        @Composable @ReadOnlyComposable get() = M3.colorScheme.scrim.copy(alpha = Floating.SCRIM_ALPHA)

    /**
     * A control at rest (a button, a row): one step above whatever it sits on, so it is still a
     * shape when the remote is somewhere else.
     */
    val control: Color
        @Composable @ReadOnlyComposable get() =
            if (LocalOnFloating.current) M3.colorScheme.surfaceContainerHighest else M3.colorScheme.surfaceContainerHigh
}

/** The hairline round a floating surface, in the scheme's outline. */
@Composable
@ReadOnlyComposable
fun floatingBorder(): BorderStroke = BorderStroke(Floating.Border, Tone.outline)

/**
 * A panel the app draws itself as a floating surface: clipped to [shape], filled with [fill] and
 * edged with the same hairline a stock Material dialog is given here.
 */
@Composable
fun Modifier.floatingSurface(
    fill: Color = FloatingTone.dialog,
    shape: Shape = Floating.DialogShape,
): Modifier = clip(shape).background(fill).border(floatingBorder(), shape)
