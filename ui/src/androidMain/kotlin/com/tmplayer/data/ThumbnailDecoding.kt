package com.tmplayer.data

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap

// Android's half of [Thumbnails]: BitmapFactory, as before the split.

internal fun decodeImage(data: ByteArray): ImageBitmap? =
    runCatching { BitmapFactory.decodeByteArray(data, 0, data.size) }.getOrNull()?.asImageBitmap()

internal fun decodeImageFile(path: String, maxWidth: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / sample > maxWidth * 2) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeFile(path, options)?.asImageBitmap()
}

internal fun ImageBitmap.byteCount(): Int = asAndroidBitmap().byteCount

/** Hands the pictures back when the system says it needs the memory. */
fun Thumbnails.trim(level: Int) = trim(everything = level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
