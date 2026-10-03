package com.tmplayer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.ui.theme.Tone

/**
 * A media preview that never leaves a hole in the grid: the blurred inline preview paints
 * immediately, the real thumbnail replaces it when it arrives, and a letter tile stands in
 * when a message has no art at all. The loading is [MediaArt] in `:ui`; the letter is drawn here.
 */
@Composable
fun MediaPreview(
    miniThumbnail: ByteArray?,
    thumbnailFileId: Int,
    fallbackLabel: String,
    modifier: Modifier = Modifier,
) {
    MediaArt(miniThumbnail, thumbnailFileId, modifier) {
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
