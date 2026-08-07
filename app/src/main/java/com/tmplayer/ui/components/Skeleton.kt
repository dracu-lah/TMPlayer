package com.tmplayer.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.data.CardLayout
import com.tmplayer.ui.theme.SurfaceDark
import com.tmplayer.ui.theme.SurfaceRaised
import com.tmplayer.ui.theme.Tv

/**
 * The sweep that every placeholder in one screen shares.
 *
 * It is a handle around a single animation rather than a plain [Brush] so the moving value is only
 * ever read inside a draw lambda. Reading it while composing would recompose the whole skeleton
 * tree on every frame, which on a 1 GB stick is far more expensive than the drawing itself.
 */
@Stable
class Shimmer internal constructor(private val progress: State<Float>) {
    internal fun brush(width: Float): Brush {
        // A zero-width element would ask for a gradient whose two ends are the same point.
        if (width <= 0f) return SolidColor(SurfaceDark)
        // The band is as wide as the element and travels from just off its left edge to just off
        // its right, so every placeholder completes a full sweep in step with its neighbours no
        // matter how wide it is. It is fully outside the element at both ends of the animation,
        // which is what hides the jump back to the start.
        val x = (progress.value * 2f - 1f) * width
        return Brush.linearGradient(
            colors = SweepColors,
            start = Offset(x, 0f),
            end = Offset(x + width, 0f),
        )
    }
}

/**
 * One animation for a whole screen of placeholders.
 *
 * Deliberately slow. This is watched from three metres away, where a quick pulse stops reading as
 * "content is on its way" and starts reading as a fault in the picture.
 */
@Composable
fun rememberShimmer(): Shimmer {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(SWEEP_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )
    return remember(progress) { Shimmer(progress) }
}

/**
 * A single grey placeholder block.
 *
 * Painted with [drawBehind] and an outline rather than `clip().background()`: a clip allocates a
 * graphics layer per element, and a full grid of tiles would want forty of them.
 */
@Composable
fun SkeletonBox(
    shimmer: Shimmer,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(6.dp),
) {
    Box(
        modifier.drawBehind {
            drawOutline(shape.createOutline(size, layoutDirection, this), shimmer.brush(size.width))
        },
    )
}

/**
 * Stand-in for the chat list, shaped like the rows that are about to replace it.
 *
 * The card itself is drawn solid and only its contents shimmer, because the row is the part we are
 * certain about; it is the avatar and the name inside it that are still unknown.
 */
@Composable
fun ChatListSkeleton(modifier: Modifier = Modifier, layout: CardLayout = CardLayout.List) {
    val shimmer = rememberShimmer()

    Column(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .padding(start = 28.dp, top = Tv.SafeV),
        verticalArrangement = Arrangement.spacedBy(if (layout == CardLayout.List) 14.dp else 16.dp),
    ) {
        when (layout) {
            CardLayout.List -> repeat(CHAT_ROWS) { index ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(84.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(SurfaceDark)
                        .padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SkeletonBox(shimmer, Modifier.size(64.dp), CircleShape)
                    Spacer(Modifier.width(24.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Names differ in length, and bars of one uniform width read as a barcode
                        // rather than as a list of chats.
                        SkeletonBox(
                            shimmer,
                            Modifier.width(TITLE_WIDTHS[index % TITLE_WIDTHS.size]).height(16.dp),
                        )
                        SkeletonBox(shimmer, Modifier.width(84.dp).height(11.dp))
                    }
                }
            }

            CardLayout.Grid -> repeat(CHAT_TILE_ROWS) {
                Row(
                    Modifier.fillMaxWidth().padding(end = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    repeat(CHAT_TILE_COLUMNS) {
                        Column(
                            Modifier
                                .weight(1f)
                                .height(154.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(SurfaceDark)
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            SkeletonBox(shimmer, Modifier.size(64.dp), CircleShape)
                            SkeletonBox(shimmer, Modifier.fillMaxWidth(0.82f).height(16.dp))
                            SkeletonBox(shimmer, Modifier.fillMaxWidth(0.45f).height(11.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Stand-in for the film listing: the same four columns, the same 16:9 art and the same spacing, so
 * nothing shifts sideways at the moment the real tiles arrive.
 *
 * It follows [layout] for the same reason it matches the grid's measurements — a screen of tiles
 * that resolves into a screen of rows is the placeholder having described the wrong thing.
 */
@Composable
fun MediaGridSkeleton(modifier: Modifier = Modifier, layout: CardLayout = CardLayout.Grid) {
    val shimmer = rememberShimmer()

    Column(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .padding(horizontal = Tv.SafeH),
        verticalArrangement = Arrangement.spacedBy(if (layout == CardLayout.Grid) 16.dp else 12.dp),
    ) {
        when (layout) {
            CardLayout.Grid -> repeat(GRID_ROWS) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    repeat(GRID_COLUMNS) {
                        MediaTileSkeleton(shimmer, Modifier.weight(1f))
                    }
                }
            }

            CardLayout.List -> repeat(LIST_ROWS) { index ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(SurfaceDark)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SkeletonBox(
                        shimmer,
                        Modifier.width(176.dp).aspectRatio(16f / 9f),
                        RoundedCornerShape(8.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SkeletonBox(
                            shimmer,
                            Modifier.width(TITLE_WIDTHS[index % TITLE_WIDTHS.size]).height(18.dp),
                        )
                        SkeletonBox(shimmer, Modifier.width(140.dp).height(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaTileSkeleton(shimmer: Shimmer, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceDark),
    ) {
        // Rectangular, not rounded: the card's own clip is what gives the art its top corners,
        // exactly as it does for the real poster.
        SkeletonBox(
            shimmer,
            Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            RectangleShape,
        )
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            SkeletonBox(shimmer, Modifier.fillMaxWidth(0.86f).height(14.dp))
            Spacer(Modifier.height(8.dp))
            SkeletonBox(shimmer, Modifier.fillMaxWidth(0.5f).height(11.dp))
        }
    }
}

private val SweepColors = listOf(SurfaceDark, SurfaceRaised, SurfaceDark)
private const val SWEEP_MS = 1_400
private const val CHAT_ROWS = 7
private const val CHAT_TILE_COLUMNS = 3

/** Three rows of chat tiles is one more than a 540 dp screen shows, so the grid never looks short. */
private const val CHAT_TILE_ROWS = 3
private const val GRID_COLUMNS = 4

/** Rows of the list arrangement, which stand taller than a chat row and so need fewer. */
private const val LIST_ROWS = 5

/** Three rows of tiles is one row more than a 540 dp screen shows, so the grid never looks short. */
private const val GRID_ROWS = 3

private val TITLE_WIDTHS: List<Dp> = listOf(240.dp, 176.dp, 288.dp, 208.dp, 264.dp, 152.dp, 224.dp)
