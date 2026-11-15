package com.tmplayer.ui.theme

import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.min

/**
 * How focus is drawn on this device: the one definition every ring, edge and scale reads (T8,
 * EG-F22: a "barely visible white box" from the sofa).
 *
 * The phone and the desktop keep [Standard], which is what they always had. The television
 * provides [tv] at the root of its theme: a thicker ring and edge, a bigger scale, and with the
 * "High contrast focus" switch a still thicker ring with a line round it in the ink that stands
 * out from the page (white on a dark one, black on a light one) and a thin keyline of the opposite
 * ink inside it, so the ring shows on pale artwork as much as on a black ground ([FocusContrast]
 * holds the figures). Nothing here animates or adds a layer: it is a stroke width, a colour and a
 * scale target the existing draw and graphics layer already read.
 *
 * @property ring the stroke round a row, a pill or a button that also fills under focus.
 * @property edge the stroke round a tile, a card or a chat row that has no fill of its own.
 * @property scale how far a tile grows under the remote or the keyboard.
 * @property highContrast whether [outer] and [inner] are drawn with the ring.
 * @property outer the contrasting line outside the ring, 0 when off.
 * @property inner the keyline of the opposite ink inside the ring, 0 when off.
 * @property tv whether this is the television's style ([focusRingOnTv] draws only then).
 */
@Immutable
class FocusStyle(
    val ring: Dp,
    val edge: Dp,
    val scale: Float,
    val highContrast: Boolean = false,
    val outer: Dp = 0.dp,
    val inner: Dp = 0.dp,
    val tv: Boolean = false,
) {
    companion object {
        /** The phone's and the desktop's focus, unchanged. */
        val Standard = FocusStyle(ring = Focus.Ring, edge = Focus.Edge, scale = Motion.FocusScale)

        /** A television's ring: a pixel thicker than [Standard] at 1080p and clearly more than that at 720p. */
        val TvRing = 3.dp

        /** A television's edge round a tile. */
        val TvEdge = 4.dp

        /**
         * A television's scale. 1.07 of a 208 dp tile is 7.3 dp a side, inside the
         * [Tv.FocusClearance] a row leaves round its tiles.
         */
        const val TvScale = 1.07f

        /** The same, with the contrast switch on (see [FocusStyle]). */
        private val TvHighContrast = FocusStyle(
            ring = 4.dp, edge = 5.dp, scale = TvScale, highContrast = true, outer = 2.dp, inner = 1.dp, tv = true,
        )

        private val TvStandard = FocusStyle(ring = TvRing, edge = TvEdge, scale = TvScale, tv = true)

        /** The television's style, with the High contrast focus switch as [highContrast]. */
        fun tv(highContrast: Boolean): FocusStyle = if (highContrast) TvHighContrast else TvStandard
    }
}

/** The focus style of this tree; the television's theme provides its own. */
val LocalFocusStyle = staticCompositionLocalOf { FocusStyle.Standard }

/** How far a tile grows when the remote or the keyboard lands on it, on this device. */
val focusScale: Float
    @Composable @ReadOnlyComposable get() = LocalFocusStyle.current.scale

/**
 * The inks of the high contrast focus, as pure functions of the theme so a test can hold them to
 * 3:1 without drawing anything: [outer] stands out from the page (white on a dark one, black on a
 * light one), [inner] is its opposite, for the pixel next to the artwork.
 */
object FocusInk {
    fun outer(dark: Boolean): Color = if (dark) Color.White else Color.Black

    fun inner(dark: Boolean): Color = if (dark) Color.Black else Color.White
}

/**
 * WCAG contrast of what the focus draws, as functions of colours alone (T8). A ring is a control
 * edge, so [Minimum] is the 3:1 a control must stand out from its ground by.
 */
object FocusContrast {
    const val Minimum = 3f

    /** [line] against the page it sits on. */
    fun againstSurface(line: Color, surface: Color): Float = contrastRatio(line, surface)

    /**
     * What stands out from one pixel of artwork under the ring: the better of the keyline drawn
     * next to it and the ring itself.
     */
    fun againstArt(art: Color, ring: Color, dark: Boolean): Float =
        maxOf(contrastRatio(FocusInk.inner(dark), art), contrastRatio(ring, art))

    /** [againstArt] for the worst pixel artwork can have: white or black. */
    fun worstAgainstArt(ring: Color, dark: Boolean): Float =
        minOf(againstArt(Color.White, ring, dark), againstArt(Color.Black, ring, dark))
}

/**
 * A ring around whatever the remote is on, for when the fill alone is not enough.
 *
 * In daylight [Tone.focusFill] is a pale blue, a 1.24:1 step on near-white and well under the 3:1
 * a control is meant to stand out by, so an outline in the accent carries the signal the fill
 * cannot.
 *
 * In the dark theme [Tone.focusFill] is already a bright block against a near-black screen, but
 * that block alone cannot tell "focused" apart from a control that is merely chosen or otherwise
 * lit the same way (HX42: a Home banner pill with no fill change at all had no focus mark once
 * Reduce motion turned its scale off). The ring there is [Tone.onFocusFill], Material's own
 * contrast-guaranteed ink for that fill, rather than the accent again: a ring in the same hue as
 * the block it sits on would still read as invisible.
 *
 * [color] is for the few rows with no fill under focus (they pass the accent). The width is [FocusStyle.ring]; with the television's High contrast focus the contrasting line
 * and keyline are drawn with it.
 */
@Composable
fun Modifier.focusRing(focused: Boolean, shape: Shape, color: Color = focusRingColor()): Modifier =
    if (focused && FocusVisibility.visible) focusStroke(shape, color, LocalFocusStyle.current.ring) else this

/**
 * [focusRing] on a television only. For a pill or a row that fills under focus on every device but
 * whose fill alone is a 1.24:1 step on the light page: the phone and the desktop keep their fill,
 * the television adds the ring it needs from the sofa.
 */
@Composable
fun Modifier.focusRingOnTv(focused: Boolean, shape: Shape): Modifier =
    if (LocalFocusStyle.current.tv) focusRing(focused, shape) else this

/**
 * [focusRing] on a television with High contrast focus on, and nothing otherwise. For the pills
 * that are meant to read as plain fill swaps (the carousel's Play and Details, user 2026-11-01):
 * the calm look stays unless the viewer asked for the loud one.
 */
@Composable
fun Modifier.focusRingOnHighContrast(focused: Boolean, shape: Shape): Modifier =
    if (LocalFocusStyle.current.let { it.tv && it.highContrast }) focusRing(focused, shape) else this

/**
 * The same for a tile, a card or a chat row (no fill of its own under focus): [FocusStyle.edge]
 * wide, in [color]. Nothing at all when not [focused], not a transparent stroke.
 */
@Composable
fun Modifier.focusEdge(focused: Boolean, shape: Shape, color: Color = Tone.accent): Modifier =
    if (focused) focusStroke(shape, color, LocalFocusStyle.current.edge) else this

/** The colour [focusRing] draws in: see there for why it is not the accent in the dark theme. */
@Composable
@ReadOnlyComposable
fun focusRingColor(): Color = if (LocalDarkTheme.current) Tone.onFocusFill else Tone.accent

/**
 * A stroke in [color], [width] wide, or with the high contrast style the three bands from the
 * outside in: the outer line, the ring, the keyline. The standard style is [border], as it was.
 */
@Composable
private fun Modifier.focusStroke(shape: Shape, color: Color, width: Dp): Modifier {
    val style = LocalFocusStyle.current
    if (!style.highContrast) return border(width = width, color = color, shape = shape)
    val dark = LocalDarkTheme.current
    return drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val outer = FocusInk.outer(dark)
        val inner = FocusInk.inner(dark)
        val outerPx = ceil(style.outer.toPx())
        val ringPx = ceil(width.toPx())
        val innerPx = ceil(style.inner.toPx())
        onDrawWithContent {
            drawContent()
            drawFocusBands(outline, outer, outerPx, color, ringPx, inner, innerPx)
        }
    }
}

/**
 * A focus [fill] behind the content and a [FocusStyle.ring] in [ring] over it, painted from
 * [focused] in the draw phase alone, so a focus move draws the element again and composes nothing
 * (HX76: on a 1 GB TV stick each row or tile composed again cost a share of a frame). The stroke is
 * the one [border] draws for a rounded shape (whole pixels wide, inset by half of that, the corners
 * shrunk to match), and the fill is [androidx.compose.foundation.background]'s, so it looks as a
 * `background(...).focusRing(...)` pair did. [focused] decides alone: pass a State that already
 * holds [FocusVisibility] where a mouse click should leave no mark ([collectIsFocusVisibleAsState]).
 * With the television's High contrast focus the outer line and keyline are drawn with the ring.
 */
@Composable
fun Modifier.focusDrawn(focused: State<Boolean>, ring: Color, shape: Shape, fill: Color = Color.Unspecified): Modifier {
    val style = LocalFocusStyle.current
    val dark = LocalDarkTheme.current
    return drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val ringPx = min(ceil(style.ring.toPx()), ceil(size.minDimension / 2))
        val outerPx = if (style.highContrast) ceil(style.outer.toPx()) else 0f
        val innerPx = if (style.highContrast) ceil(style.inner.toPx()) else 0f
        val outer = FocusInk.outer(dark)
        val inner = FocusInk.inner(dark)
        onDrawWithContent {
            val on = focused.value
            if (on && fill.isSpecified) drawOutline(outline, fill)
            drawContent()
            if (on) drawFocusBands(outline, outer, outerPx, ring, ringPx, inner, innerPx)
        }
    }
}

/**
 * The bands of a focus, outside in, each a stroke of whole pixels inset by the ones outside it
 * and shrunk at the corners to match, the way [border] draws one. A band of 0 is not drawn. If the
 * three do not fit the shape, the ring alone is.
 */
private fun DrawScope.drawFocusBands(
    outline: Outline,
    outer: Color,
    outerPx: Float,
    ring: Color,
    ringPx: Float,
    inner: Color,
    innerPx: Float,
) {
    val room = size.minDimension / 2
    val fits = outerPx + ringPx + innerPx <= room
    val o = if (fits) outerPx else 0f
    val i = if (fits) innerPx else 0f
    val r = min(ringPx, room)
    band(outline, outer, 0f, o)
    band(outline, ring, o, r)
    band(outline, inner, o + r, i)
}

private fun DrawScope.band(outline: Outline, color: Color, inset: Float, width: Float) {
    if (width <= 0f) return
    val half = inset + width / 2
    val w = size.width - 2 * half
    val h = size.height - 2 * half
    if (w <= 0f || h <= 0f) return
    val style = Stroke(width)
    when (outline) {
        is Outline.Rounded -> {
            val corner = outline.roundRect.topLeftCornerRadius
            drawRoundRect(
                color,
                topLeft = Offset(half, half),
                size = Size(w, h),
                cornerRadius = CornerRadius((corner.x - half).coerceAtLeast(0f), (corner.y - half).coerceAtLeast(0f)),
                style = style,
            )
        }
        is Outline.Rectangle -> drawRect(color, topLeft = Offset(half, half), size = Size(w, h), style = style)
        // A shape of no known inset: the stroke on its edge, the outermost band alone.
        is Outline.Generic -> if (inset == 0f) drawOutline(outline, color, style = style)
    }
}
