package com.tmplayer.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.scale
import com.tmplayer.ui.theme.Tone
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tmplayer.data.Thumbnails
import com.tmplayer.ui.theme.Motion

/**
 * A hero's picture set into the page rather than laid over it: the Home carousel on every device.
 *
 * In a wide frame (a television, a desktop window, a phone on its side) the picture is full-bleed,
 * [share] of the width (close to the whole of it) at its own 16:9 shape, cropped from its foot
 * where that runs taller than the frame, and fades into the page towards the start, the foot and,
 * by [topFade], the top; [cornerShade] grows a glow of the page's colour out of the top end corner
 * for a clock or a window's buttons standing there. The start fade runs long and shallow (see
 * [START_FADE], [START_FADE_CAP]): never quite the page's own colour outright, so a hint of the
 * picture always shows through even under the words, which reads as the picture settling into the
 * page on its own rather than a panel laid over it, the way a streaming app's own hero reads,
 * rather than a narrower picture with a visible edge where it ends (user, 2026-11-01). In a narrow
 * frame (a phone held upright) there is no room beside it, so the picture spans the width and fills
 * the height, and only the foot and the top fade.
 *
 * Nothing is drawn behind it: a wash of the thumbnail's colours showed a channel's logo as a giant
 * ghost behind the words, since many a thumbnail is the channel's logo.
 *
 * A picture smaller than [SHARP_MIN_WIDTH] (Telegram's thumbnails are 320 pixels) is drawn no
 * more than [EDGE_SMALL_SCALE] times its own size, so it is never blown up into a blur, and floats
 * centred down the end, faded on every side but the edge. With no
 * picture at all the video's [GeneratedCover] takes its place, faded the same way.
 */
@Composable
fun EdgeBackdrop(
    source: BackdropSource,
    modifier: Modifier = Modifier,
    share: Float = EDGE_SHARE,
    topFade: Dp = 0.dp,
    /**
     * [topFade] in pixels, read where the fades are drawn rather than here: for a reach that
     * follows something measured (the TV carousel's heading), so its arrival redraws the fades
     * without composing the picture again. Null uses [topFade].
     */
    topFadePx: (() -> Float)? = null,
    cornerShade: Dp = 0.dp,
    /** Called once the first picture (or the cover standing for it) is drawn. */
    onPictured: (() -> Unit)? = null,
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val frameW = constraints.maxWidth
        val frameH = constraints.maxHeight
        val wide = frameW > frameH * WIDE_RATIO
        val pictureW = if (wide) (frameW * share).toInt() else frameW
        // The size the carousel's next pages are decoded at ahead ([prefetchBackdrop]).
        androidx.compose.runtime.SideEffect { if (pictureW > 0) edgeDrawnWidth = pictureW }
        val art = rememberBackdropArt(source, pictureW)
        val topFadeFixed = with(density) { topFade.toPx() }
        val topFadeOf: () -> Float = topFadePx ?: { topFadeFixed }
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

        // The picture stands still (user, 2026-10-27: the slow push in "does not look good").
        val pictured by androidx.compose.runtime.rememberUpdatedState(onPictured)
        androidx.compose.runtime.LaunchedEffect(art != null) { if (art != null) pictured?.invoke() }
        // From nothing as well as between steps, so the first picture fades in rather than popping.
        // At once on a television: no two backdrops drawn together for a fade.
        Crossfade(targetState = art, animationSpec = if (Motion.television) androidx.compose.animation.core.snap() else Motion.standard(), modifier = Modifier.fillMaxSize(), label = "edgeBackdrop") { step ->
            if (step == null) return@Crossfade
            Box(Modifier.fillMaxSize()) {
                // Until the sharp picture is in, the inline preview stands in its place, blurry but
                // the same picture, rather than a cover of other colours flashing first.
                val picture = step.picture ?: step.field
                // The size the picture is drawn at, in pixels: its own shape at the share of the
                // width, no taller than the frame (cropped from its foot then), and a small one
                // capped at a few times its own pixels.
                // A thumbnail too small to stand at the top floats, centred down the end, faded at
                // its top as well, so it reads as a vignette rather than a strip under the clock.
                // The stand-in is meant blurry, so it is stretched to the full size, never floated.
                val standIn = step.picture == null
                val small = wide && !standIn && picture != null && picture.width < SHARP_MIN_WIDTH
                // Blurred whenever it isn't the sharp picture itself: the field alone, or a real
                // but small Telegram thumbnail, read as a loading placeholder rather than a small
                // sharp picture sitting oddly on the page (user, 2026-10-31, the Home carousel).
                val lowRes = picture != null && (standIn || picture.width < SHARP_MIN_WIDTH)
                // An upright picture (the online poster, for a title with no wide one) cropped to
                // the wide frame showed a slice of it: it stands whole at the frame's height
                // instead, over a soft copy of itself filling the rest.
                val upright = wide && !standIn && !small && picture != null && picture.height > picture.width
                val (w, h) = when {
                    picture == null -> pictureW to (if (wide) minOf(frameH, pictureW * 9 / 16) else frameH)
                    upright -> pictureW to frameH
                    !wide -> frameW to frameH
                    else -> {
                        val cap = if (!standIn && picture.width < SHARP_MIN_WIDTH) (picture.width * EDGE_SMALL_SCALE).toInt() else Int.MAX_VALUE
                        val width = minOf(pictureW, cap)
                        width to minOf(frameH, if (standIn) width * 9 / 16 else width * picture.height / picture.width.coerceAtLeast(1))
                    }
                }
                val sized = with(density) { Modifier.size(w.toDp(), h.toDp()) }
                val ground = Tone.background
                // A television paints the page's colour over the picture's edges instead: the
                // same look on the page's plain ground, without an offscreen layer two thirds of
                // the screen in size, which a TV stick's GPU filled again with every new picture.
                // The brushes are made once per size here, not on every draw: the stops, their
                // arrays and three or four gradients each time the picture was drawn again.
                val fades = if (Motion.television) Modifier.drawWithCache {
                    val across = if (wide) {
                        val stops = fadeOut(START_FADE)
                        Brush.horizontalGradient(*(if (rtl) stops.reversedStops(ground, START_FADE_CAP) else stops.inStops(ground, START_FADE_CAP)))
                    } else null
                    val foot = Brush.verticalGradient(*fadeOut(FOOT_FADE).reversedStops(ground))
                    val reach = if (small) FOOT_FADE else (topFadeOf() / size.height).coerceIn(0f, 1f)
                    val top = if (reach > 0f) Brush.verticalGradient(*fadeOut(reach).inStops(ground, TOP_FADE_CAP)) else null
                    onDrawWithContent {
                        drawContent()
                        if (across != null) drawRect(across)
                        drawRect(foot)
                        if (top != null) drawRect(top)
                    }
                } else Modifier
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                    .drawWithCache {
                        val across = if (wide) {
                            val stops = fadeOut(START_FADE)
                            Brush.horizontalGradient(*(if (rtl) stops.reversedStops(START_FADE_CAP) else stops.inStops(START_FADE_CAP)))
                        } else null
                        val foot = Brush.verticalGradient(*fadeOut(FOOT_FADE).reversedStops())
                        val reach = if (small) FOOT_FADE else (topFadeOf() / size.height).coerceIn(0f, 1f)
                        val top = if (reach > 0f) Brush.verticalGradient(*fadeOut(reach).inStops(TOP_FADE_CAP)) else null
                        onDrawWithContent {
                            drawContent()
                            if (across != null) drawRect(across, blendMode = BlendMode.DstIn)
                            drawRect(foot, blendMode = BlendMode.DstIn)
                            if (top != null) drawRect(top, blendMode = BlendMode.DstIn)
                        }
                    }
                val place = Modifier.align(if (small) Alignment.CenterEnd else Alignment.TopEnd).then(sized).then(fades)
                if (picture != null && upright) {
                    Box(place) {
                        // Not on a television: a blur that size is a full screen pass on a stick's GPU.
                        if (!Motion.television) {
                            Image(
                                bitmap = picture,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                filterQuality = Thumbnails.drawFilter,
                                alpha = UPRIGHT_FIELD_ALPHA,
                                modifier = Modifier.fillMaxSize().blur(UPRIGHT_FIELD_BLUR),
                            )
                        }
                        Image(
                            bitmap = picture,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.CenterEnd,
                            filterQuality = Thumbnails.drawFilter,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else if (picture != null) {
                    Image(
                        bitmap = picture,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        filterQuality = Thumbnails.drawFilter,
                        modifier = if (lowRes && !Motion.television) {
                            place.then(Modifier.blur(STAND_IN_BLUR))
                        } else {
                            place
                        },
                    )
                } else {
                    // The colours alone: the cover's number and initial would sit under the clock.
                    GeneratedCover(
                        remember(source.cover) { source.cover.copy(number = null, chatInitial = null) },
                        place,
                        showTitle = false,
                    )
                }
            }
        }
        // The page's own colour, glowing out of the top end corner over the picture, so a clock or
        // a window's buttons there read in either theme while the rest of the picture stays whole.
        if (cornerShade > 0.dp) {
            val ground = Tone.background
            Box(
                Modifier.fillMaxSize().drawWithCache {
                    val corner = Offset(if (rtl) 0f else size.width, 0f)
                    val radius = cornerShade.toPx()
                    val glow = Brush.radialGradient(
                        0f to ground.copy(alpha = 0.9f),
                        0.5f to ground.copy(alpha = 0.78f),
                        0.8f to ground.copy(alpha = 0.32f),
                        1f to Color.Transparent,
                        center = corner,
                        radius = radius,
                    )
                    onDrawBehind {
                        scale(scaleX = CORNER_STRETCH, scaleY = 1f, pivot = corner) {
                            drawCircle(glow, radius = radius, center = corner)
                        }
                    }
                },
            )
        }
    }
}

/**
 * A fade over the first [reach] of a length, as (position, alpha) stops from the clear edge: an
 * eased curve, so the picture melts into the page with no band where it starts.
 */
private fun fadeOut(reach: Float): List<Pair<Float, Float>> =
    EASE.map { (at, alpha) -> at * reach to alpha } + (1f to 1f)

/** The stops as they are, clear at the start of the gradient. */
private fun List<Pair<Float, Float>>.inStops(): Array<Pair<Float, Color>> =
    map { (at, alpha) -> at to Color.Black.copy(alpha = alpha) }.toTypedArray()

/**
 * As [inStops], a mask that never drops the picture fully: at its darkest point only [cap] of it
 * is erased, so a hint of the picture always shows even under the words, which is what reads as
 * the picture settling into the page rather than a layer painted over it (user, 2026-11-01). The
 * same [cap] as the ground-colour [inStops] overload, so the television and the offscreen-layer
 * paths read alike.
 */
private fun List<Pair<Float, Float>>.inStops(cap: Float): Array<Pair<Float, Color>> {
    val floor = 1f - cap
    return map { (at, alpha) -> at to Color.Black.copy(alpha = floor + alpha * cap) }.toTypedArray()
}

/** The stops mirrored, clear at the end of the gradient. */
private fun List<Pair<Float, Float>>.reversedStops(): Array<Pair<Float, Color>> =
    map { (at, alpha) -> (1f - at) to Color.Black.copy(alpha = alpha) }.reversed().toTypedArray()

/** As [reversedStops], with the same [cap] as [inStops]. */
private fun List<Pair<Float, Float>>.reversedStops(cap: Float): Array<Pair<Float, Color>> {
    val floor = 1f - cap
    return map { (at, alpha) -> (1f - at) to Color.Black.copy(alpha = floor + alpha * cap) }.reversed().toTypedArray()
}

/** As [inStops], drawn over the picture in the [ground] colour: solid where the mask is clear. */
private fun List<Pair<Float, Float>>.inStops(ground: Color): Array<Pair<Float, Color>> =
    map { (at, alpha) -> at to ground.copy(alpha = 1f - alpha) }.toTypedArray()

/**
 * As [inStops], its deepest point [cap] of the [ground] colour rather than solid: the picture
 * keeps showing faintly through even under the words, so the fade reads as settling into the page
 * rather than a panel laid over it (user, 2026-11-01).
 */
private fun List<Pair<Float, Float>>.inStops(ground: Color, cap: Float): Array<Pair<Float, Color>> =
    map { (at, alpha) -> at to ground.copy(alpha = (1f - alpha) * cap) }.toTypedArray()

/** As [reversedStops], in the [ground] colour. */
private fun List<Pair<Float, Float>>.reversedStops(ground: Color): Array<Pair<Float, Color>> =
    map { (at, alpha) -> (1f - at) to ground.copy(alpha = 1f - alpha) }.reversed().toTypedArray()

/** As [reversedStops], with the same [cap] as [inStops]. */
private fun List<Pair<Float, Float>>.reversedStops(ground: Color, cap: Float): Array<Pair<Float, Color>> =
    map { (at, alpha) -> (1f - at) to ground.copy(alpha = (1f - alpha) * cap) }.reversed().toTypedArray()

private val EASE = listOf(0f to 0f, 0.2f to 0.06f, 0.4f to 0.24f, 0.6f to 0.52f, 0.8f to 0.82f, 1f to 1f)

/**
 * How much of a wide frame's width the picture takes, against the end edge: the whole of it,
 * outright. A share short of 1 (0.98 was tried) still left a sliver at the start edge with
 * nothing drawn at all, flat and textureless against the faded picture right beside it, which
 * read as the picture being cut exactly as the original 68 per cent share did, only narrower
 * (user, 2026-11-01, caught by zooming into the rendered edge itself rather than trusting the
 * numbers). At 1f the picture's own width is the frame's width exactly, so there is no gap to
 * reintroduce by nudging this back down for a rounding worry that never showed up in practice.
 */
const val EDGE_SHARE = 1f

/** A frame this many times wider than tall puts the picture at its end; a narrower one spans it. */
private const val WIDE_RATIO = 1.4f

/**
 * The share of the picture's width [START_FADE] fades into the page at its start, now that the
 * picture is full-bleed. A short, deep fade read as a dark panel dropped on the picture; a long,
 * shallow one (this, with [START_FADE_CAP] keeping its own deepest point short of solid) reads as
 * the picture itself settling into the page, the words still readable the whole way (user,
 * 2026-11-01). The foot fade stacks with this one under the words for the rest of the contrast.
 */
private const val START_FADE = 0.6f

/** The deepest point of [START_FADE], short of the page's own colour outright: see [START_FADE]. */
private const val START_FADE_CAP = 0.78f

/**
 * The deepest point of the top fade a caller reaches for with [topFade] or [topFadePx] (a
 * television's clock and account, a window's own controls): short of the page's own colour
 * outright, the same reasoning as [START_FADE_CAP], so a deep top fade does not read as a dark
 * cap dropped over the picture's own top (user, 2026-11-01).
 */
private const val TOP_FADE_CAP = 0.85f

/** The share of the picture's height that fades into the page at its foot. */
private const val FOOT_FADE = 0.42f

/**
 * How much wider than tall the [cornerShade] glow reaches: the whole cluster of a television's
 * header (refresh, clock and avatar) stands in it, so the clock reads over a bright picture in
 * the light theme too.
 */
private const val CORNER_STRETCH = 2.6f

/** How many times its own width a thumbnail may be drawn here, at most, before it reads as a blur. */
private const val EDGE_SMALL_SCALE = 2f

/** The soft copy behind an upright picture: blurred past any detail, and dimmed so the picture leads. */
private val UPRIGHT_FIELD_BLUR = 48.dp
private const val UPRIGHT_FIELD_ALPHA = 0.55f

