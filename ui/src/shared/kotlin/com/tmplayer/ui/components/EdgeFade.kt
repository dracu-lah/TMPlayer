package com.tmplayer.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

/**
 * Fades a horizontal row out over its side margins ([start] and [end], the row's own padding), so
 * cards scrolled past the margin melt away instead of being cut off hard against the sidebar or the
 * window's edge. Each side fades only while there is something on that side to scroll back to: a
 * row at rest starts on its first card, whole. The start side comes in with the first few pixels
 * of scroll, so it never pops.
 */
fun Modifier.edgeFade(state: LazyListState, start: Dp, end: Dp, ground: Color? = null): Modifier =
    if (ground != null) groundFade(state, start, end, ground) else this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            val edges = Edges(this, start, end, mask = true, ground = Color.Black)
            onDrawWithContent {
                drawContent()
                edges.draw(state) { brush, topLeft, size -> drawRect(brush, topLeft, size, blendMode = BlendMode.DstIn) }
            }
        }

/**
 * [edgeFade] for a row that stands on one plain colour, [ground]: the colour is drawn over the
 * margins rather than the row cut out of an offscreen layer, which a television redrew whole on
 * every frame of every scroll.
 */
private fun Modifier.groundFade(state: LazyListState, start: Dp, end: Dp, ground: Color): Modifier = drawWithCache {
    val edges = Edges(this, start, end, mask = false, ground = ground)
    onDrawWithContent {
        drawContent()
        edges.draw(state) { brush, topLeft, size -> drawRect(brush, topLeft, size) }
    }
}

/**
 * The two fades for one size of the row. The scroll is read where it is drawn, and a side's
 * gradient is made again only when how far that side fades has changed: at rest and through most
 * of a scroll both stay as they are, where every frame of a scroll used to make two.
 */
private class Edges(scope: CacheDrawScope, start: Dp, end: Dp, private val mask: Boolean, private val ground: Color) {
    private val startPx = with(scope) { start.toPx() }
    private val endPx = with(scope) { end.toPx() }
    private val rtl = scope.layoutDirection == LayoutDirection.Rtl
    private val width = scope.size.width
    private val leftPx = if (rtl) endPx else startPx
    private val rightPx = if (rtl) startPx else endPx
    private val leftSize = Size(leftPx, scope.size.height)
    private val rightSize = Size(rightPx, scope.size.height)
    private val rightAt = Offset(width - rightPx, 0f)

    // As a mask, black keeps the row and clear removes it; drawn over, the ground covers it.
    private val keep = if (mask) Color.Black else ground.copy(alpha = 0f)

    private var leftAmount = -1f
    private var leftBrush: Brush? = null
    private var rightAmount = -1f
    private var rightBrush: Brush? = null

    private fun gone(amount: Float) = if (mask) Color.Black.copy(alpha = 1f - amount) else ground.copy(alpha = amount)

    fun draw(state: LazyListState, draw: (Brush, Offset, Size) -> Unit) {
        val scrolled = if (state.firstVisibleItemIndex > 0) 1f else (state.firstVisibleItemScrollOffset / startPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val back = if (state.canScrollBackward) scrolled else 0f
        val on = if (state.canScrollForward) 1f else 0f
        val left = if (rtl) on else back
        val right = if (rtl) back else on
        if (left > 0f && leftPx > 0f) {
            if (left != leftAmount || leftBrush == null) {
                leftAmount = left
                leftBrush = Brush.horizontalGradient(0f to gone(left), 1f to keep, startX = 0f, endX = leftPx)
            }
            draw(leftBrush!!, Offset.Zero, leftSize)
        }
        if (right > 0f && rightPx > 0f) {
            if (right != rightAmount || rightBrush == null) {
                rightAmount = right
                rightBrush = Brush.horizontalGradient(0f to keep, 1f to gone(right), startX = width - rightPx, endX = width)
            }
            draw(rightBrush!!, rightAt, rightSize)
        }
    }
}

/**
 * Melts the foot of a vertically scrolling list into [ground] while there is more below it, for a list
 * that ends against something fixed (a row of filters, a field): without it the last line is cut
 * through its middle by a hard edge (phone audit, 2026-11-25). [canScrollForward] is read where it is
 * drawn, so the fade comes and goes without a recomposition; at the list's end nothing is drawn.
 */
fun Modifier.bottomFade(canScrollForward: () -> Boolean, height: Dp, ground: Color): Modifier = drawWithCache {
    val fadePx = height.toPx()
    val brush = Brush.verticalGradient(
        0f to ground.copy(alpha = 0f), 1f to ground,
        startY = size.height - fadePx, endY = size.height,
    )
    val at = Offset(0f, size.height - fadePx)
    val box = Size(size.width, fadePx)
    onDrawWithContent {
        drawContent()
        if (canScrollForward()) drawRect(brush, at, box)
    }
}

/** [bottomFade] for a column that scrolls by a [ScrollState]; put it before `verticalScroll`, so it is drawn on the viewport and does not move with the content. */
fun Modifier.bottomFade(state: androidx.compose.foundation.ScrollState, height: Dp, ground: Color): Modifier =
    bottomFade({ state.canScrollForward }, height, ground)

/**
 * Keeps a card that takes focus (the remote's or the keyboard's) inside a row's side margins rather
 * than at its very edge, where [edgeFade] would fade it: the row scrolls just far enough to stand
 * the card between [start] and [end] of the row's own edges.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WithinMargins(start: Dp, end: Dp, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val startPx = with(density) { start.toPx() }
    val endPx = with(density) { end.toPx() }
    val spec = remember(startPx, endPx) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val lead = startPx
                val trail = containerSize - endPx
                return when {
                    // Wider than the room between the margins: line its leading edge up.
                    size > trail - lead -> offset - lead
                    offset < lead -> offset - lead
                    offset + size > trail -> offset + size - trail
                    else -> 0f
                }
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/**
 * Soft top and bottom edges for a vertical list that stands on one plain colour, [ground]: the last [fade] of the
 * viewport melts into the page instead of ending on a hard line through a heading or a card (TV, 2026-11-25: the
 * "PRIVACY" heading cut through its letters at the foot of Settings). Each edge shows only while there is more to
 * scroll to that way ([atTop], [atBottom], read where it is drawn), so a list at rest is whole. Drawn over the
 * content, never an offscreen layer: a television redraws it with the list at no extra cost.
 */
fun Modifier.softVerticalEdges(
    ground: Color,
    fade: Dp = 24.dp,
    atTop: () -> Boolean = { false },
    atBottom: () -> Boolean = { false },
): Modifier = drawWithContent {
    drawContent()
    val edge = fade.toPx().coerceAtMost(size.height / 2f)
    if (edge <= 0f) return@drawWithContent
    if (atTop()) {
        drawRect(
            Brush.verticalGradient(0f to ground, 1f to ground.copy(alpha = 0f), startY = 0f, endY = edge),
            topLeft = Offset.Zero,
            size = Size(size.width, edge),
        )
    }
    if (atBottom()) {
        drawRect(
            Brush.verticalGradient(0f to ground.copy(alpha = 0f), 1f to ground, startY = size.height - edge, endY = size.height),
            topLeft = Offset(0f, size.height - edge),
            size = Size(size.width, edge),
        )
    }
}
