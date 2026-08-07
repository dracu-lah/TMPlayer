package com.tmplayer.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.RemoteImages
import com.tmplayer.data.Thumbnails
import com.tmplayer.ui.theme.SurfaceDark

/**
 * Poster art that never leaves a hole in the grid: the blurred inline preview paints
 * immediately, the real thumbnail replaces it when it arrives, and a letter tile stands in
 * when a message has no art at all.
 */
@Composable
fun Poster(
    miniThumbnail: ByteArray?,
    thumbnailFileId: Int,
    fallbackLabel: String,
    modifier: Modifier = Modifier,
) {
    val mini = remember(miniThumbnail) { Thumbnails.mini(miniThumbnail) }

    val full by produceState<Bitmap?>(initialValue = null, key1 = thumbnailFileId) {
        value = Thumbnails.full(thumbnailFileId)
    }

    Box(modifier.background(SurfaceDark), contentAlignment = Alignment.Center) {
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
                style = MaterialTheme.typography.headlineLarge,
            )
        }
    }
}

/**
 * A picture fetched over HTTP, for film art from TMDB.
 *
 * There is deliberately no error state. Art that fails to load leaves [placeholder] showing,
 * which for a poster is a letter tile and for a backdrop is the plain background. A viewer does
 * not need to be told that a decorative image did not arrive, and the film still plays.
 */
@Composable
fun NetworkImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alpha: Float = 1f,
    placeholder: @Composable () -> Unit = {},
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = url) {
        value = RemoteImages.load(url)
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        val loaded = bitmap
        if (loaded != null) {
            Image(
                bitmap = loaded.asImageBitmap(),
                contentDescription = null,
                contentScale = contentScale,
                alpha = alpha,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            placeholder()
        }
    }
}
