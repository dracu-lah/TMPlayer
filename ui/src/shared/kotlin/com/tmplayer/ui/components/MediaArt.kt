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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
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
    // Where a television or a Lighter picture draws by hand ([DrawnPicture]) the surface under it
    // is left out, which on a television was one more fill of every tile on screen, every frame.
    val drawn = Motion.television || Thumbnails.light
    val shown: Any? = full ?: mini?.bitmap ?: if (mini == null) null else Missing
    Box(
        modifier.then(if (drawn && shown is ImageBitmap) Modifier else Modifier.background(Tone.surface)),
        contentAlignment = Alignment.Center,
    ) {
        // Nothing at all until the preview is decoded, and then no fade for the first picture: the
        // fade starts on whatever it is first given, so it only ever runs between two steps, the
        // better picture over the one it replaces ([FadeOver]).
        // A preview fading up from the surface under every tile of a scrolling grid is a flicker.
        if (shown != null) FadeOver(shown, Modifier.fillMaxSize()) { step ->
            when (step) {
                is ImageBitmap -> if (drawn) DrawnPicture(step) else Picture(step)
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

/** [bitmap] cropped to fill its frame, whatever its own shape. */
@Composable
private fun Picture(bitmap: ImageBitmap) {
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

/**
 * [Picture] drawn in one node instead of composing an [Image]: the same crop, with nothing
 * composed again when the size is known. One node per tile in place of three is also less to
 * lay out.
 */
@Composable
private fun DrawnPicture(bitmap: ImageBitmap) {
    val filter = Thumbnails.drawFilter
    Spacer(
        Modifier.fillMaxSize().drawWithCache {
            onDrawBehind {
                drawCropped(bitmap, filter)
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
