package com.tmplayer.data

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import java.io.File

// The desktop's half of [Thumbnails]: Skia decodes the JPEGs TDLib hands over.

internal fun decodeImage(data: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(data).toComposeImageBitmap() }.getOrNull()

/**
 * Full size. Telegram's thumbnails are already a few hundred pixels across, and the desktop has
 * the memory a television does not, so there is nothing to sample down.
 */
@Suppress("UNUSED_PARAMETER")
internal fun decodeImageFile(path: String, maxWidth: Int): ImageBitmap? =
    decodeImage(File(path).readBytes())

internal fun ImageBitmap.byteCount(): Int = width * height * 4
