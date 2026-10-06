package com.tmplayer.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.tmplayer.data.Td
import com.tmplayer.data.Thumbnails
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/**
 * A media preview that never leaves a hole in the grid: the blurred inline preview paints
 * immediately, the real thumbnail replaces it when it arrives, and [fallback] (a letter tile, as a
 * rule) stands in when a message has no art at all.
 *
 * The fallback is a slot because the television draws its letter with tv-material's text and the
 * phone and the desktop with Material 3's.
 */
@Composable
fun MediaArt(
    miniThumbnail: ByteArray?,
    thumbnailFileId: Int,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit,
) {
    // Decoded off the composition thread. One ~40 px JPEG is nothing, but a grid row scrolling into
    // view is a dozen of them in a single frame, plus a hash of each byte array to key the cache,
    // which is a dropped frame on a stick every time the list moves.
    val mini by produceState<ImageBitmap?>(initialValue = null, miniThumbnail) {
        value = withContext(Dispatchers.Default) { Thumbnails.mini(miniThumbnail) }
    }

    var full by remember(thumbnailFileId) { mutableStateOf<ImageBitmap?>(null) }
    // Retried when the connection comes back, but only a tile that still has no thumbnail collects
    // [Td.connected]: TDLib on a stick flaps often, and twenty collectors would mean the effect
    // restarting under every visible tile at once.
    LaunchedEffect(thumbnailFileId) {
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
    var frame by remember { mutableStateOf(0f) }
    Box(
        modifier
            .background(Tone.surface)
            .onSizeChanged { if (it.height > 0) frame = it.width.toFloat() / it.height },
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = full ?: mini
        if (bitmap != null) {
            if (letterboxes(bitmap.width, bitmap.height, frame)) {
                // A phone held upright, or a square clip: cropped to a 16:9 tile it would be a band
                // across somebody's middle. So it sits whole in the frame, over a soft, dimmed crop of
                // itself. The inline preview is the backdrop where there is one, since a ~40 px image
                // stretched that far is already a blur on every platform; blur() only adds to it
                // where the platform can (Android 12 and up, the desktop).
                Image(
                    bitmap = mini ?: bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.Low,
                    modifier = Modifier.fillMaxSize().blur(LETTERBOX_BLUR),
                )
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = LETTERBOX_DIM)))
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.Low,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    // The inline preview is a ~40 px blur; smoothing it beats showing the blocks.
                    filterQuality = FilterQuality.Low,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            fallback()
        }
    }
}

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
