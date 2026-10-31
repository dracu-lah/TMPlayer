package com.tmplayer.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.tmplayer.data.Td
import com.tmplayer.data.Thumbnails
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/**
 * A media preview that never leaves a hole in the grid: the blurred inline preview paints
 * immediately, the real thumbnail replaces it when it arrives, and a stand-in fills the frame
 * when a message has no art at all.
 *
 * Each step fades into the next over [Motion.Normal] rather than popping: the preview into the
 * thumbnail, and the stand-in into a thumbnail that arrives late. The first picture is simply
 * there, and a thumbnail already in the cache is there on the first frame, so a tile scrolled
 * back into view does not fade in a second time.
 *
 * The stand-in is [fallback] where one is given, which is a slot because the television draws a
 * chat's letter with tv-material's text and the phone and the desktop with Material 3's. Without
 * one it is the [GeneratedCover] for [cover], and with neither the frame is left as the surface.
 */
@Composable
fun MediaArt(
    miniThumbnail: ByteArray?,
    thumbnailFileId: Int,
    modifier: Modifier = Modifier,
    cover: Cover? = null,
    compactCover: Boolean = false,
    /**
     * False when the caller writes the name beside or under the art, so the [GeneratedCover] keeps
     * only its colour, number and chat initial rather than the same words twice.
     */
    showCoverTitle: Boolean = true,
    fallback: (@Composable () -> Unit)? = null,
) {
    // Decoded off the composition thread. One ~40 px JPEG is nothing, but a grid row scrolling into
    // view is a dozen of them in a single frame, plus a hash of each byte array to key the cache,
    // which is a dropped frame on a stick every time the list moves. Null while that is under way,
    // so the stand-in is not flashed for a frame under a picture that is about to arrive.
    var full by remember(thumbnailFileId) { mutableStateOf(Thumbnails.peekFull(thumbnailFileId)) }
    // Not at all when the sharp thumbnail is already in memory: a tile scrolling back into view
    // would decode and hash a preview it never shows.
    var mini by remember {
        mutableStateOf(if (miniThumbnail == null || miniThumbnail.isEmpty() || full != null) NoMini else null)
    }
    // The bytes [mini] was decoded from. TDLib hands an equal but fresh array with every update of
    // the message, and the effect below restarts on the array itself; the same picture is not
    // decoded and hashed again for it, nor is one decoded at all under a sharp thumbnail.
    val miniSource = remember { MiniSource() }
    LaunchedEffect(miniThumbnail) {
        val bytes = miniThumbnail
        if (bytes == null || bytes.isEmpty()) {
            if (mini == null) mini = NoMini
            return@LaunchedEffect
        }
        val decoded = mini !== NoMini && mini != null
        if (decoded && miniSource.bytes?.contentEquals(bytes) == true) return@LaunchedEffect
        if (full != null && !decoded) return@LaunchedEffect
        val picture = withContext(Dispatchers.Default) { Thumbnails.mini(bytes) }
        miniSource.bytes = bytes
        mini = Decoded(picture)
    }
    // Retried when the connection comes back, but only a tile that still has no thumbnail collects
    // [Td.connected]: TDLib on a stick flaps often, and twenty collectors would mean the effect
    // restarting under every visible tile at once. A message with no thumbnail at all has nothing
    // to wait for.
    LaunchedEffect(thumbnailFileId) {
        if (thumbnailFileId <= 0) return@LaunchedEffect
        if (full == null) full = Thumbnails.full(thumbnailFileId)
        if (full != null) return@LaunchedEffect
        Td.connected.collectLatest { connected ->
            if (!connected || full != null) return@collectLatest
            full = Thumbnails.full(thumbnailFileId)
        }
    }

    // Whatever this stands in front of, it is the colour behind every avatar and every thumbnail
    // in the app, so it has to be the scheme's own step above the surface and not a fixed grey.
    // The frame's own shape, measured rather than assumed: the same art fills 16:9 tiles, square
    // avatars and circles, and the rule below is about the picture against the frame it is in.
    // Where no blur is drawn (a television, Lighter pictures) the picture is drawn by hand and
    // measures its frame as it draws ([DrawnPicture]). The frame is then never written here, which
    // composed every tile's picture a second time once its size was known.
    val drawn = Motion.television || Thumbnails.light
    var frame by remember { mutableStateOf(0f) }
    val miniBitmap = mini?.bitmap
    val shown: Any? = full ?: miniBitmap ?: if (mini == null) null else Missing
    Box(
        modifier
            // A picture drawn by hand fills the frame edge to edge (cropped, or letterboxed over
            // its own crop), so the surface under it is left out: on a television that was one
            // more fill of every tile on screen, every frame.
            .then(if (drawn && shown is ImageBitmap) Modifier else Modifier.background(Tone.surface))
            .then(if (drawn) Modifier else Modifier.onSizeChanged { if (it.height > 0) frame = it.width.toFloat() / it.height }),
        contentAlignment = Alignment.Center,
    ) {
        // Nothing at all until the preview is decoded, and then no fade for the first picture: the
        // fade starts on whatever it is first given, so it only ever runs between two steps, the
        // better picture over the one it replaces ([FadeOver]).
        // A preview fading up from the surface under every tile of a scrolling grid is a flicker.
        if (shown != null) FadeOver(shown, Modifier.fillMaxSize()) { step ->
            when (step) {
                is ImageBitmap -> if (drawn) DrawnPicture(step, miniBitmap) else Picture(step, miniBitmap, frame)
                Missing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when {
                        fallback != null -> fallback()
                        cover != null -> GeneratedCover(cover, Modifier.fillMaxSize(), compact = compactCover, showTitle = showCoverTitle)
                    }
                }
            }
        }
    }
}

/** [bitmap] in a frame of shape [frame], cropped or letterboxed by [letterboxes]. */
@Composable
private fun Picture(bitmap: ImageBitmap, mini: ImageBitmap?, frame: Float) {
    if (letterboxes(bitmap.width, bitmap.height, frame)) {
        // A phone held upright, or a square clip: cropped to a 16:9 tile it would be a band
        // across somebody's middle. So it sits whole in the frame, over a soft, dimmed crop of
        // itself. The inline preview is the backdrop where there is one, since a ~40 px image
        // stretched that far is already a blur on every platform; blur() only adds to it
        // where the platform can (Android 12 and up, the desktop).
        Box(Modifier.fillMaxSize()) {
            Image(
                bitmap = mini ?: bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Low,
                // Lighter pictures (CP68) skip the blur: the stretched preview is soft enough.
                // So does a television, where a blur pass per tile costs frames.
                modifier = if (Thumbnails.light || com.tmplayer.ui.theme.Motion.television) Modifier.fillMaxSize() else Modifier.fillMaxSize().blur(LETTERBOX_BLUR),
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = LETTERBOX_DIM)))
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                filterQuality = Thumbnails.drawFilter,
                modifier = Modifier.fillMaxSize(),
            )
        }
    } else {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            // The inline preview is a ~40 px blur; smoothing it beats showing the blocks. A
            // thumbnail drawn smaller than itself is sampled with mipmaps on the desktop.
            filterQuality = Thumbnails.drawFilter,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * [Picture] without the blur, drawn in one node that reads its frame's shape as it draws: the
 * same crop, or the same whole picture over a dimmed crop of the preview, with nothing composed
 * again when the size is known. One node per tile in place of three is also less to lay out.
 */
@Composable
private fun DrawnPicture(bitmap: ImageBitmap, mini: ImageBitmap?) {
    val filter = Thumbnails.drawFilter
    Spacer(
        Modifier.fillMaxSize().drawWithCache {
            val frame = if (size.height > 0f) size.width / size.height else 0f
            val boxed = letterboxes(bitmap.width, bitmap.height, frame)
            val back = mini ?: bitmap
            onDrawBehind {
                if (boxed) {
                    drawCropped(back, FilterQuality.Low)
                    drawRect(Color.Black.copy(alpha = LETTERBOX_DIM))
                    drawFitted(bitmap, filter)
                } else {
                    drawCropped(bitmap, filter)
                }
            }
        },
    )
}

/** [image] scaled to fill the frame and centred, the overflow cut off: [ContentScale.Crop]. */
private fun DrawScope.drawCropped(image: ImageBitmap, filter: FilterQuality) {
    if (image.width <= 0 || image.height <= 0 || size.width <= 0f || size.height <= 0f) return
    val scale = maxOf(size.width / image.width, size.height / image.height)
    val srcW = (size.width / scale).roundToInt().coerceIn(1, image.width)
    val srcH = (size.height / scale).roundToInt().coerceIn(1, image.height)
    drawImage(
        image,
        srcOffset = IntOffset((image.width - srcW) / 2, (image.height - srcH) / 2),
        srcSize = IntSize(srcW, srcH),
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        filterQuality = filter,
    )
}

/** [image] whole in the frame, centred: [ContentScale.Fit]. */
private fun DrawScope.drawFitted(image: ImageBitmap, filter: FilterQuality) {
    if (image.width <= 0 || image.height <= 0 || size.width <= 0f || size.height <= 0f) return
    val scale = minOf(size.width / image.width, size.height / image.height)
    val w = (image.width * scale).roundToInt()
    val h = (image.height * scale).roundToInt()
    drawImage(
        image,
        dstOffset = IntOffset(((size.width - w) / 2f).roundToInt(), ((size.height - h) / 2f).roundToInt()),
        dstSize = IntSize(w, h),
        filterQuality = filter,
    )
}

/** The inline preview once it has been decoded, [bitmap] null when it would not decode. */
private class Decoded(val bitmap: ImageBitmap?)

/** The preview's bytes last decoded, held outside snapshot state: only compared, never drawn. */
private class MiniSource {
    var bytes: ByteArray? = null
}

/** A message with no inline preview to decode: known at once, with nothing to wait for. */
private val NoMini = Decoded(null)

/** The step of the cross-fade where there is no picture at all and the stand-in shows. */
private object Missing

/**
 * The one rule for a picture whose shape is not its frame's: crop to fill, unless cropping would
 * hide more than [LETTERBOX_LOSS] of it, and then fit it whole over a blurred fill of itself.
 *
 * Only a picture narrower than its frame is ever letterboxed. A 4:3 frame in a 16:9 tile loses a
 * quarter of its height and crops; a square one or anything upright would lose half or more and
 * is shown whole. A picture wider than its frame (a cinemascope still, a banner) always crops.
 */
internal fun letterboxes(width: Int, height: Int, frame: Float): Boolean {
    if (width <= 0 || height <= 0 || frame <= 0f) return false
    val picture = width.toFloat() / height
    return picture < frame && 1f - picture / frame > LETTERBOX_LOSS
}

/** More than this share of a picture cut away by the crop, and it is shown whole instead. */
private const val LETTERBOX_LOSS = 0.3f
private const val LETTERBOX_DIM = 0.35f
private val LETTERBOX_BLUR = 16.dp
