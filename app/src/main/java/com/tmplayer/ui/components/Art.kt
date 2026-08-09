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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.data.Td
import com.tmplayer.data.Thumbnails
import com.tmplayer.ui.theme.SurfaceDark

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
    val mini = remember(miniThumbnail) { Thumbnails.mini(miniThumbnail) }

    val telegramConnected by Td.connected.collectAsStateWithLifecycle()
    var full by remember(thumbnailFileId) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(thumbnailFileId, telegramConnected) {
        if (full == null) full = Thumbnails.full(thumbnailFileId)
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
