package com.tmplayer.ui.components

import android.graphics.Bitmap
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.Td
import com.tmplayer.data.Thumbnails
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/**
 * A media preview that never leaves a hole in the grid: the blurred inline preview paints
 * immediately, the real thumbnail replaces it when it arrives, and a letter tile stands in
 * when a message has no art at all.
 */
@Composable
fun MediaPreview(
    miniThumbnail: ByteArray?,
    thumbnailFileId: Int,
    fallbackLabel: String,
    modifier: Modifier = Modifier,
) {
    // Decoded off the composition thread. One ~40 px JPEG is nothing, but a grid row scrolling into
    // view is a dozen of them in a single frame, plus a hash of each byte array to key the cache,
    // which is a dropped frame on a stick every time the list moves.
    val mini by produceState<Bitmap?>(initialValue = null, miniThumbnail) {
        value = withContext(Dispatchers.Default) { Thumbnails.mini(miniThumbnail) }
    }

    var full by remember(thumbnailFileId) { mutableStateOf<Bitmap?>(null) }
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
    Box(modifier.background(Tone.surface), contentAlignment = Alignment.Center) {
        val bitmap = full ?: mini
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // The inline preview is a ~40 px blur; smoothing it beats showing the blocks.
                filterQuality = FilterQuality.Low,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                fallbackLabel.take(1).uppercase(),
                // The tile this letter stands in for is a third of the size on a phone, where a
                // headline glyph fills it corner to corner and reads as a mistake.
                style = if (isTouch()) {
                    MaterialTheme.typography.titleLarge
                } else {
                    MaterialTheme.typography.headlineLarge
                },
                color = if (isTouch()) Tone.muted else Color.Unspecified,
            )
        }
    }
}
