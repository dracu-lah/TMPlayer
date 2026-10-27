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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
    /** The sweep's place, 0 to 1; null where the placeholders stand still. */
    private val progress: State<Float>?,
    /**
     * The band's three stops, read once where the theme is in scope. They must be the scheme's own
     * greys, so a light window does not shimmer a dark block.
     */
    private val colors: List<Color>,
) {
    /** Whether the band moves, so a placeholder has to be painted again every frame. */
    internal val moving: Boolean get() = progress != null

    internal fun brush(width: Float): Brush {
        // A zero-width element would ask for a gradient whose two ends are the same point, and a
        // still placeholder is one flat colour.
        if (width <= 0f || progress == null) return SolidColor(colors.first())
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
    val base = Tone.surface
    val band = Tone.surfaceHigh
    // Reduce motion (CP68), and always on a television: the placeholders stand still, drawn once.
    // A sweep on a TV stick repainted every placeholder on screen every frame while a screen was
    // loading, the time the stick has least to spare.
    if (com.tmplayer.ui.theme.Motion.reduced || com.tmplayer.ui.theme.Motion.television) {
        return remember(base) { Shimmer(null, listOf(base, base, base)) }
    }
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
    val colors = listOf(base, band, base)
    return remember(progress, base, band) { Shimmer(progress, colors) }
}

/**
 * A single grey placeholder block.
 *
 * Painted with an outline rather than `clip().background()`: a clip allocates a graphics layer per
 * element, and a full grid of tiles would want forty of them. The outline, and a still
 * placeholder's paint, are made once per size in [drawWithCache]; only a moving band's gradient
 * is made again each frame.
 */
@Composable
fun SkeletonBox(
    shimmer: Shimmer,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Corner.Small),
) {
    Box(
        modifier.drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val still = if (shimmer.moving) null else shimmer.brush(size.width)
            onDrawBehind { drawOutline(outline, still ?: shimmer.brush(size.width)) }
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
fun ChatListSkeleton(
    modifier: Modifier = Modifier,
    /** Rows side by side: two on a television, whose list is two columns. */
    columns: Int = 1,
) {
    val shimmer = rememberShimmer()
    val touch = isTouch()

    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val start = if (touch) TOUCH_INSET else 28.dp
        val end = if (touch) TOUCH_INSET else 4.dp
        val top = if (touch) TOUCH_TOP else Tv.SafeV
        // Read here rather than below: inside the Column and Row lambdas the box's scope is no
        // longer the implicit receiver, so the measurements have to be carried in.
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
                start = if (touch) 0.dp else start,
                end = if (touch) 0.dp else end,
                top = top,
            ),
            verticalArrangement = Arrangement.spacedBy(rowGap),
        ) {
            repeat(rowsToFill(height, rowHeight + rowGap)) { line ->
                // A television's rows stand in [columns] side by side, as the list does.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                repeat(columns.coerceAtLeast(1)) { column ->
                val index = line * columns.coerceAtLeast(1) + column
                Row(
                    Modifier
                        .weight(1f)
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
                }
            }
        }
    }
}

/**
 * Stand-in for the video listing: the same columns, the same 16:9 art and the same spacing, so
 * nothing shifts sideways when the real tiles arrive. The column count is worked out from the width
 * the way the listing works it out, so a phone in either orientation gets the grid it is about to
 * be given.
 */
@Composable
fun MediaGridSkeleton(modifier: Modifier = Modifier) {
    val shimmer = rememberShimmer()
    val touch = isTouch()

    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val edge = if (touch) TOUCH_INSET else Tv.SafeH
        // Carried in for the same reason: the nested lambdas cannot see the box's own scope.
        val inner = maxWidth - edge * 2
        val height = maxHeight
        val landscape = maxWidth > maxHeight

        // The grid takes the listing's own figures from [TileGrid], so the shimmer resolves into
        // the tiles it stood for rather than a listing that has moved.
        val dense = touch
        val gridGap = if (touch) TileGrid.TouchGap else TileGrid.TvGap

        Column(
            Modifier.padding(horizontal = edge),
            verticalArrangement = Arrangement.spacedBy(gridGap),
        ) {
                val columns = TileGrid.wideColumns(inner, touch, landscape)
                // The art is 16:9 of whatever a column came out as, and the caption beneath it
                // is a fixed block, so the tile's height is only known once the width is.
                val tile = (inner - gridGap * (columns - 1)) / columns * 9f / 16f +
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
    }
}

/**
 * One row of the video grid's placeholder tiles, at the end of a listing while its next page is
 * read: the viewer sees more is on its way in the shape it will take, rather than a spinner
 * floated over the last row (user, 2026-10-23). [columns] and [gap] are the grid's own.
 */
@Composable
fun MediaGridSkeletonRow(columns: Int, gap: androidx.compose.ui.unit.Dp, dense: Boolean, modifier: Modifier = Modifier) {
    val shimmer = rememberShimmer()
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
        repeat(columns.coerceAtLeast(1)) {
            if (dense) DenseTileSkeleton(shimmer, Modifier.weight(1f)) else MediaTileSkeleton(shimmer, Modifier.weight(1f))
        }
    }
}

/** The phone's tile: art, the two lines of name it always reserves room for, and its facts. */
@Composable
private fun DenseTileSkeleton(shimmer: Shimmer, modifier: Modifier = Modifier) {
    Column(modifier) {
        SkeletonBox(
            shimmer,
            Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            RoundedCornerShape(Corner.Medium),
        )
        Spacer(Modifier.height(8.dp))
        SkeletonBox(shimmer, Modifier.fillMaxWidth().height(12.dp))
        Spacer(Modifier.height(6.dp))
        SkeletonBox(shimmer, Modifier.fillMaxWidth(0.6f).height(12.dp))
        Spacer(Modifier.height(8.dp))
        SkeletonBox(shimmer, Modifier.fillMaxWidth(0.45f).height(10.dp))
    }
}

/** A television's tile: art with no panel round it, a line of name and a line of facts. */
@Composable
private fun MediaTileSkeleton(shimmer: Shimmer, modifier: Modifier = Modifier) {
    Column(modifier) {
        SkeletonBox(
            shimmer,
            Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            RoundedCornerShape(Corner.Medium),
        )
        Spacer(Modifier.height(10.dp))
        SkeletonBox(shimmer, Modifier.fillMaxWidth(0.86f).height(16.dp))
        Spacer(Modifier.height(8.dp))
        SkeletonBox(shimmer, Modifier.fillMaxWidth(0.5f).height(12.dp))
    }
}

/**
 * The video grid's figures, read by the listing on the phone and the television and by the
 * placeholder above, so the two always agree on how many tiles go across and how far apart.
 */
object TileGrid {

    /** Between a phone's tiles, both ways. */
    val TouchGap: Dp = 10.dp

    /** Between a television's tiles, both ways. */
    val TvGap: Dp = 12.dp

    /**
     * How many 2:3 posters (films and shows) go across [width], the room inside the side margins:
     * three on a phone held upright and six on its side, a poster of about 126 dp; on a tablet as
     * many as keep one under [POSTER_TOUCH_MAX]; on a television about seven.
     */
    fun posterColumns(width: Dp, touch: Boolean, landscape: Boolean): Int =
        if (touch) {
            maxOf(if (landscape) 6 else 3, across(width, POSTER_TOUCH_MAX, TouchGap))
        } else {
            across(width, POSTER_TV_MIN, TvGap)
        }

    /**
     * How many 16:9 cards (clips, lectures, episodes) go across [width]: two on a phone held
     * upright and four on its side, so a lecture's name has room to be read, and about four on a
     * television.
     */
    fun wideColumns(width: Dp, touch: Boolean, landscape: Boolean): Int =
        if (touch) {
            maxOf(if (landscape) 4 else 2, across(width, WIDE_TOUCH_MAX, TouchGap))
        } else {
            across(width, WIDE_TV_MIN, TvGap)
        }

    /**
     * The grid's own column count when posters and wide cards share it: a count both divide, so a
     * poster spans [columns] / posters of them and a wide card [columns] / wide.
     */
    fun columns(posters: Int, wide: Int): Int = posters / gcd(posters, wide) * wide

    private fun across(width: Dp, tile: Dp, gap: Dp): Int = ((width + gap) / (tile + gap)).toInt().coerceAtLeast(1)

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    private val POSTER_TOUCH_MAX: Dp = 160.dp
    private val POSTER_TV_MIN: Dp = 108.dp
    private val WIDE_TOUCH_MAX: Dp = 280.dp
    private val WIDE_TV_MIN: Dp = 196.dp
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

private const val SWEEP_MS = 1_400

/** A ceiling on the row count, so a very tall window cannot ask for a hundred placeholders. */
private const val MAX_ROWS = 12

/** The phone insets, matching `TouchInsets` on the browse screen and `TOUCH_EDGE` on the grid. */
private val TOUCH_INSET = 16.dp
private val TOUCH_TOP = 8.dp

/** Two lines of name and a line of facts under a phone's tile, as the real tile reserves. */
private val DENSE_CAPTION_HEIGHT = 68.dp

/** A line of name and a line of facts under the art of a television's tile. */
private val CAPTION_HEIGHT = 54.dp


/**
 * Title bars as a share of the room the title has, rather than a width in dp, since fixed bars
 * would run off the side of a phone's narrower card.
 */
private val TITLE_FRACTIONS: List<Float> = listOf(0.72f, 0.5f, 0.86f, 0.6f, 0.8f, 0.42f, 0.66f)
