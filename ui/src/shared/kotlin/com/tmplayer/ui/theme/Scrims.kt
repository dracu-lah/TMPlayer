package com.tmplayer.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

/**
 * The gradients that sit between a picture and the text on it.
 *
 * Text on a picture never gets a plate or a box. It gets a long, soft fade instead, so the
 * picture runs into the page with no edge anywhere. Two- and three-stop fades band visibly over a
 * few hundred dp, which is why the long ones here carry seven stops on an eased curve: almost
 * nothing for the first third, then most of the opacity arriving late, under the text.
 *
 * [bottomLong] and [startLong] fade into [Tone.scrim], the page's own background, so they follow
 * light and dark. [tileFoot] is black in both themes, because it sits inside a tile, where the
 * page is not what the picture meets. [headerTop] is black in both themes too: it is what a page
 * header ([Tone.onArt] words and icons) stands on while a hero's picture runs up behind it.
 *
 * Apply one with `Modifier.background(brush)` on a box drawn over the picture.
 */
object Scrims {

    /**
     * The long curve, as (position, alpha) pairs from the clear end to the solid end. At its foot
     * it reaches 0.84 of the background by the last sixth, which is what keeps text there at
     * 4.5:1 over any picture.
     */
    internal val LongCurve: List<Pair<Float, Float>> = listOf(
        0f to 0f,
        0.16f to 0.04f,
        0.32f to 0.14f,
        0.50f to 0.34f,
        0.68f to 0.62f,
        0.84f to 0.84f,
        1f to 1f,
    )

    /** The foot of a tile, as (position, alpha) pairs of black down the tile's height. */
    internal val FootCurve: List<Pair<Float, Float>> = listOf(
        0f to 0f,
        0.45f to 0f,
        0.60f to 0.70f,
        1f to 0.95f,
    )

    /**
     * The top of a hero's picture where a page header sits over it, as (position, alpha) pairs
     * of black from the top edge down. The header fills the first [HEADER_SHARE] of the height,
     * where the black never drops under 0.66: enough for [Tone.onArtMuted] at 4.5:1 over a white
     * picture, and [Tone.onArt] at 7:1. Under the header it eases out to clear in a short step, so
     * it stops before the hero's own words below the header.
     */
    internal val TopCurve: List<Pair<Float, Float>> = listOf(
        0f to 0.74f,
        0.40f to 0.70f,
        HEADER_SHARE to 0.66f,
        0.90f to 0.42f,
        0.95f to 0.16f,
        1f to 0f,
    )

    /**
     * [TopCurve] down whatever it is drawn across: a header's own height stretched by
     * [HEADER_REACH], so the header sits in its dark part and the picture shows clear under it.
     * Draw it behind the header with [androidx.compose.ui.draw.drawBehind], sized
     * `size.height * HEADER_REACH`.
     */
    val headerTop: Brush = Brush.verticalGradient(
        colorStops = TopCurve.map { (at, alpha) -> at to Color.Black.copy(alpha = alpha) }.toTypedArray(),
    )

    /**
     * [headerTop] with its end fixed at [height] px. A brush with an open end spans the size of the
     * draw scope it is used in, not the rect drawn with it, so drawing [headerTop] taller than its
     * header still ended the fade at the header's foot, and in the light theme the header's second
     * line stood on white.
     */
    fun headerTop(height: Float): Brush = Brush.verticalGradient(
        colorStops = TopCurve.map { (at, alpha) -> at to Color.Black.copy(alpha = alpha) }.toTypedArray(),
        startY = 0f,
        endY = height,
    )

    /** The share of [headerTop]'s height the header itself takes. */
    const val HEADER_SHARE = 0.85f

    /** How much taller than the header [headerTop] is drawn: the header over its first [HEADER_SHARE]. */
    const val HEADER_REACH = 1f / HEADER_SHARE

    /**
     * The fade at the foot of a hero or a page backdrop: clear above the last [height] of
     * whatever it is drawn across, then [LongCurve] down to the background at the bottom edge.
     */
    @Composable
    @ReadOnlyComposable
    fun bottomLong(height: Dp): Brush {
        val reach = with(LocalDensity.current) { height.toPx() }
        return LongBrush(Tone.scrim, reach, Edge.Bottom)
    }

    /**
     * The same curve across a wide backdrop, never quite the page's own colour outright at the
     * start edge ([cap] short of it, see [START_CAP]) and clear by [reach] of the width, for text
     * that sits on the start half. A hint of the picture always shows through even under the
     * words, so a title page's hero reads as the picture settling into the page rather than a
     * panel laid over it (user, 2026-11-01). It starts from the right in a right to left layout,
     * where the text does.
     */
    @Composable
    @ReadOnlyComposable
    fun startLong(reach: Float = START_REACH, cap: Float = START_CAP): Brush {
        val edge = if (LocalLayoutDirection.current == LayoutDirection.Rtl) Edge.Right else Edge.Left
        return LongBrush(Tone.scrim, -reach, edge, cap)
    }

    /**
     * The foot of a tile: clear for the top 45 per cent, black at 70 per cent by 0.6 of the
     * height and at 95 per cent at the bottom, for a title and a progress line drawn on the art.
     */
    val tileFoot: Brush = Brush.verticalGradient(
        colorStops = FootCurve.map { (at, alpha) -> at to Color.Black.copy(alpha = alpha) }.toTypedArray(),
    )


    /**
     * How far across a backdrop [startLong] reaches by default: past the text column, and now
     * that the backdrop it is drawn on is full-bleed ([com.tmplayer.ui.browse.BACKDROP_SHARE]),
     * the same reach as the Home carousel's own start fade
     * ([com.tmplayer.ui.components.EDGE_SHARE]'s start fade), so a title page's hero and the
     * carousel it was opened from read as the same picture treatment (user, 2026-11-01).
     */
    const val START_REACH = 0.72f

    /** The deepest point of [startLong]'s default [cap], short of the page's own colour outright. */
    const val START_CAP = 0.82f

    private enum class Edge { Bottom, Left, Right }

    /**
     * [LongCurve] in [color], solid at [edge] and clear [reach] away from it: a length in pixels
     * when positive, a share of the width when negative (the start fade's form). Its own deepest
     * point is [cap] of [color]'s alpha rather than all of it, so what sits behind it never fully
     * disappears; 1 (the default) is solid at the edge, as [bottomLong] stays.
     *
     * A brush that is handed its size, rather than a plain gradient with fixed end points, so the
     * same brush serves a box of any height and stays clear above the fade.
     */
    private class LongBrush(val color: Color, val reach: Float, val edge: Edge, val cap: Float = 1f) : ShaderBrush() {
        override fun createShader(size: Size): Shader {
            val length = if (reach < 0f) size.width * -reach else reach
            val (from, to) = when (edge) {
                Edge.Bottom -> Offset(0f, size.height - length) to Offset(0f, size.height)
                Edge.Left -> Offset(length, 0f) to Offset(0f, 0f)
                Edge.Right -> Offset(size.width - length, 0f) to Offset(size.width, 0f)
            }
            return LinearGradientShader(
                from = from,
                to = to,
                colors = LongCurve.map { color.copy(alpha = it.second * cap) },
                colorStops = LongCurve.map { it.first },
            )
        }

        override fun equals(other: Any?): Boolean =
            other is LongBrush && other.color == color && other.reach == reach && other.edge == edge && other.cap == cap

        override fun hashCode(): Int = ((color.hashCode() * 31 + reach.hashCode()) * 31 + edge.hashCode()) * 31 + cap.hashCode()
    }
}

/**
 * [Scrims.tileFoot] drawn over only the part of the tile where it is not clear: the same picture,
 * but the top 45 per cent of every tile is no longer filled with a transparent gradient. A
 * TV stick's GPU paid for every pixel of the old full-tile rectangle on every frame.
 */
fun Modifier.tileFootScrim(): Modifier = drawWithCache {
    val clear = Scrims.FootCurve[1].first
    val top = size.height * clear
    // The curve's stops past the clear part, measured from where the drawn band starts.
    val stops = Scrims.FootCurve.drop(1).map { (at, alpha) -> (at - clear) / (1f - clear) to Color.Black.copy(alpha = alpha) }.toTypedArray()
    val brush = Brush.verticalGradient(colorStops = stops, startY = top, endY = size.height)
    onDrawBehind { drawRect(brush, topLeft = Offset(0f, top), size = Size(size.width, size.height - top)) }
}
