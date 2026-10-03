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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.data.CardLayout
import com.tmplayer.ui.theme.Avatar
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv

/**
 * The sweep that every placeholder in one screen shares.
 *
 * It is a handle around a single animation rather than a plain [Brush] so the moving value is only
 * ever read inside a draw lambda. Reading it while composing would recompose the whole skeleton
 * tree on every frame, which on a 1 GB stick is far more expensive than the drawing itself.
 */
@Stable
class Shimmer internal constructor(
    private val progress: State<Float>,
    /**
     * The band's three stops, read once where the theme is in scope. They must be the scheme's own
     * greys, so a light window does not shimmer a dark block.
     */
    private val colors: List<Color>,
) {
    internal fun brush(width: Float): Brush {
        // A zero-width element would ask for a gradient whose two ends are the same point.
        if (width <= 0f) return SolidColor(colors.first())
        // The band is as wide as the element and travels from just off its left edge to just off
        // its right, so every placeholder sweeps in step with its neighbours whatever its width,
        // and is fully outside the element at both ends, hiding the jump back to the start.
        val x = (progress.value * 2f - 1f) * width
        return Brush.linearGradient(
            colors = colors,
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
    val base = Tone.surface
    val band = Tone.surfaceHigh
    val colors = listOf(base, band, base)
    return remember(progress, base, band) { Shimmer(progress, colors) }
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
    shape: Shape = RoundedCornerShape(Corner.Small),
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
 * The card is drawn solid and only its contents shimmer, because the row is the part we are certain
 * about. The measurements follow the screen the way the real listing does: a phone gets the touch
 * insets and as many columns as its width allows, a television the overscan margin and its fixed
 * three, so nothing shifts when the real cards arrive.
 */
@Composable
fun ChatListSkeleton(modifier: Modifier = Modifier, layout: CardLayout = CardLayout.List) {
    val shimmer = rememberShimmer()
    val touch = isTouch()

    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val start = if (touch) TOUCH_INSET else 28.dp
        val end = if (touch) TOUCH_INSET else 4.dp
        val top = if (touch) TOUCH_TOP else Tv.SafeV
        // Read here rather than below: inside the Column and Row lambdas the box's scope is no
        // longer the implicit receiver, so the measurements have to be carried in.
        val room = maxWidth - start - end
        val height = maxHeight - top

        // The rows the real list draws, so the two agree. A phone's chat list is Material's
        // two-line list item: 72dp full bleed, a 56dp avatar, 16dp insets, no gap between rows.
        // The television's is an 84dp card with a 64dp avatar and a 14dp gap.
        val rowHeight = if (touch) 72.dp else 84.dp
        val rowGap = if (touch) 0.dp else 14.dp
        val avatar = if (touch) Avatar.List else 64.dp
        val rowPad = if (touch) 16.dp else 24.dp

        Column(
            Modifier.padding(
                start = if (touch && layout == CardLayout.List) 0.dp else start,
                end = if (touch && layout == CardLayout.List) 0.dp else end,
                top = top,
            ),
            verticalArrangement = Arrangement.spacedBy(
                if (layout == CardLayout.List) rowGap else 16.dp,
            ),
        ) {
            when (layout) {
                CardLayout.List -> repeat(rowsToFill(height, rowHeight + rowGap)) { index ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(rowHeight)
                            .then(
                                if (touch) {
                                    Modifier
                                } else {
                                    Modifier
                                        .clip(RoundedCornerShape(Corner.Large))
                                        .background(Tone.surface)
                                },
                            )
                            .padding(horizontal = rowPad),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SkeletonBox(shimmer, Modifier.size(avatar), CircleShape)
                        Spacer(Modifier.width(if (touch) 16.dp else 24.dp))
                        // Weighted, so the bars below are measured against the room the name
                        // actually has. Widths in dp would run off the side of a phone.
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            // Names differ in length, and bars of one uniform width read as a
                            // barcode rather than as a list of chats.
                            SkeletonBox(
                                shimmer,
                                Modifier
                                    .fillMaxWidth(TITLE_FRACTIONS[index % TITLE_FRACTIONS.size])
                                    .height(16.dp),
                            )
                            SkeletonBox(shimmer, Modifier.fillMaxWidth(0.3f).height(11.dp))
                        }
                    }
                }

                CardLayout.Grid -> {
                    val columns = tileColumns(room, CHAT_TILE_COLUMNS, touch)
                    repeat(rowsToFill(height, 154.dp + 16.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            repeat(columns) {
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .height(154.dp)
                                        .clip(RoundedCornerShape(Corner.Large))
                                        .background(Tone.surface)
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    SkeletonBox(shimmer, Modifier.size(64.dp), CircleShape)
                                    SkeletonBox(
                                        shimmer,
                                        Modifier.fillMaxWidth(0.82f).height(16.dp),
                                    )
                                    SkeletonBox(
                                        shimmer,
                                        Modifier.fillMaxWidth(0.45f).height(11.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Stand-in for the video listing: the same columns, the same 16:9 art and the same spacing, so
 * nothing shifts sideways when the real tiles arrive. The column count is worked out from the width
 * the way the listing works it out, so a phone in either orientation gets the grid it is about to
 * be given. It follows [layout] for the same reason.
 */
@Composable
fun MediaGridSkeleton(modifier: Modifier = Modifier, layout: CardLayout = CardLayout.Grid) {
    val shimmer = rememberShimmer()
    val touch = isTouch()

    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val edge = if (touch) TOUCH_INSET else Tv.SafeH
        // Carried in for the same reason: the nested lambdas cannot see the box's own scope.
        val inner = maxWidth - edge * 2
        val height = maxHeight

        // The phone's grid is compact: small tiles a hairline apart, with two lines of the name
        // under each. The placeholder must match, or the shimmer resolves into a listing that has
        // moved.
        val dense = touch && layout == CardLayout.Grid
        val gridGap = if (dense) DENSE_GAP else 16.dp
        val gridEdge = if (dense) DENSE_GAP else edge
        val gridInner = if (dense) maxWidth - DENSE_GAP * 2 else inner

        Column(
            Modifier.padding(horizontal = gridEdge),
            verticalArrangement = Arrangement.spacedBy(
                if (layout == CardLayout.Grid) gridGap else 12.dp,
            ),
        ) {
            when (layout) {
                CardLayout.Grid -> {
                    val columns = tileColumns(gridInner, GRID_COLUMNS, touch)
                    // The art is 16:9 of whatever a column came out as, and the caption beneath it
                    // is a fixed block, so the tile's height is only known once the width is.
                    val tile = (gridInner - gridGap * (columns - 1)) / columns * 9f / 16f +
                        (if (dense) DENSE_CAPTION_HEIGHT else CAPTION_HEIGHT)
                    repeat(rowsToFill(height, tile + gridGap)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(gridGap),
                        ) {
                            repeat(columns) {
                                if (dense) {
                                    DenseTileSkeleton(shimmer, Modifier.weight(1f))
                                } else {
                                    MediaTileSkeleton(shimmer, Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                CardLayout.List -> {
                    // The art is a share of the row on a phone and a fixed width on a television,
                    // exactly as the real row is, so the row keeps its height across the swap.
                    val art = if (touch) (inner - 24.dp - 16.dp) * TOUCH_ART_SHARE else ROW_ART_WIDTH
                    val step = art * 9f / 16f + 24.dp + 12.dp
                    repeat(rowsToFill(height, step)) { index ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Corner.Medium))
                                .background(Tone.surface)
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SkeletonBox(
                                shimmer,
                                (if (touch) Modifier.weight(TOUCH_ART_SHARE) else Modifier.width(ROW_ART_WIDTH))
                                    .aspectRatio(16f / 9f),
                                RoundedCornerShape(Corner.Small),
                            )
                            Column(
                                Modifier.weight(if (touch) 1f - TOUCH_ART_SHARE else 1f),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                SkeletonBox(
                                    shimmer,
                                    Modifier
                                        .fillMaxWidth(TITLE_FRACTIONS[index % TITLE_FRACTIONS.size])
                                        .height(18.dp),
                                )
                                SkeletonBox(shimmer, Modifier.fillMaxWidth(0.55f).height(12.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The phone's compact tile: art, then the two lines of name it always reserves room for. */
@Composable
private fun DenseTileSkeleton(shimmer: Shimmer, modifier: Modifier = Modifier) {
    Column(modifier.padding(DENSE_GAP)) {
        SkeletonBox(
            shimmer,
            Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            RoundedCornerShape(Corner.Small),
        )
        Spacer(Modifier.height(6.dp))
        SkeletonBox(shimmer, Modifier.fillMaxWidth().height(10.dp))
        Spacer(Modifier.height(4.dp))
        SkeletonBox(shimmer, Modifier.fillMaxWidth(0.6f).height(10.dp))
        Spacer(Modifier.height(6.dp))
        SkeletonBox(shimmer, Modifier.fillMaxWidth(0.45f).height(8.dp))
    }
}

@Composable
private fun MediaTileSkeleton(shimmer: Shimmer, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(Corner.Medium))
            .background(Tone.surface),
    ) {
        // Rectangular, not rounded: the card's own clip is what gives the art its top corners,
        // exactly as it does for the real media preview.
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

/**
 * Enough rows of [step] to reach the bottom of [height], and then one more.
 *
 * The spare row stops the placeholder ending in mid-screen on a tall phone, which would read as a
 * short list that has finished loading.
 */
private fun rowsToFill(height: Dp, step: Dp): Int {
    if (step <= 0.dp) return 1
    return ((height / step).toInt() + 1).coerceIn(1, MAX_ROWS)
}

/**
 * How many tiles fit across [width], counting the 16dp between them.
 *
 * A television is a known size and keeps its [tv] count. A phone is not, and turns over while it is
 * being held, so it is asked the same question the real grids ask: how many tiles of the minimum
 * width will go in.
 */
private fun tileColumns(width: Dp, tv: Int, touch: Boolean): Int {
    if (!touch) return tv
    return ((width + 16.dp) / (TOUCH_TILE_MIN + 16.dp)).toInt().coerceAtLeast(1)
}

private const val SWEEP_MS = 1_400
private const val CHAT_TILE_COLUMNS = 3
private const val GRID_COLUMNS = 4

/** A ceiling on the row count, so a very tall window cannot ask for a hundred placeholders. */
private const val MAX_ROWS = 12

/** The smallest tile the phone grids accept, matching `TOUCH_TILE_MIN` on both browse screens. */
private val TOUCH_TILE_MIN = 120.dp

/** Barely a hairline between tiles. Mirrors the listing's own figure. */
private val DENSE_GAP = 4.dp

/** Two small lines of caption under a dense tile, matching the two the real tile reserves. */
private val DENSE_CAPTION_HEIGHT = 44.dp

/** The phone insets, matching `TouchInsets` on the browse screen and `TOUCH_EDGE` on the grid. */
private val TOUCH_INSET = 16.dp
private val TOUCH_TOP = 8.dp

/** Two lines of caption and its padding, under the art of a tile. */
private val CAPTION_HEIGHT = 53.dp

/** The art beside a list row, matching `ROW_ART_WIDTH` and `TOUCH_ART_SHARE` on the media screen. */
private val ROW_ART_WIDTH = 176.dp
private const val TOUCH_ART_SHARE = 0.4f

/**
 * Title bars as a share of the room the title has, rather than a width in dp, since fixed bars
 * would run off the side of a phone's narrower card.
 */
private val TITLE_FRACTIONS: List<Float> = listOf(0.72f, 0.5f, 0.86f, 0.6f, 0.8f, 0.42f, 0.66f)
