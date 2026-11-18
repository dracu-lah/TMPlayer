package com.tmplayer.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap

// Android's half of [Thumbnails]: BitmapFactory, as before the split.

/** An opaque picture from [argb], one Int per pixel, row by row ([MiniBlur]). */
internal fun imageFromPixels(argb: IntArray, width: Int, height: Int): ImageBitmap =
    Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()

internal fun decodeImage(data: ByteArray): ImageBitmap? =
    runCatching { BitmapFactory.decodeByteArray(data, 0, data.size) }.getOrNull()?.asImageBitmap()

/** [alpha] keeps a clear ground (a title logo); everything else is opaque and decoded at half the memory. */
internal fun decodeImageFile(path: String, maxWidth: Int, alpha: Boolean = false): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / sample > maxWidth * 2) sample *= 2
    val sampled = bounds.outWidth / sample
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = if (alpha) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
        // Sampling only halves, so a picture up to twice [maxWidth] would keep its full size: an
        // original still 1920 wide is 4 MB. Scaled to [maxWidth] as it is decoded instead.
        if (sampled > maxWidth && maxWidth > 0) {
            inScaled = true
            inDensity = sampled
            inTargetDensity = maxWidth
        }
    }
    // prepareToDraw lets the render thread upload the texture while it is idle, ahead of the frame
    // that first shows the tile, rather than inside it: Coil and Glide do the same.
    return BitmapFactory.decodeFile(path, options)?.also { it.prepareToDraw() }?.asImageBitmap()
}

internal fun ImageBitmap.byteCount(): Int = asAndroidBitmap().byteCount

/**
 * Hands the pictures back when the system says it needs the memory: all of them when it is
 * critical, in front (the player playing over Home on a 1 GB stick) or behind (about to be
 * killed), half of them otherwise. Only TRIM_MEMORY_COMPLETE emptied the cache before, so a
 * stick playing a video kept Home's whole poster cache while the system was already
 * killing other apps to feed the player. On a low-end device all of them from
 * TRIM_MEMORY_RUNNING_LOW up ([MemoryTrim]).
 */
fun Thumbnails.trim(level: Int, lowEnd: Boolean = false) = trim(
    everything = MemoryTrim.of(level, lowEnd).pictures == MemoryTrim.Pictures.All,
)

/** Android samples a picture down to the width asked for, so the cache keys it by that width. */
internal const val decodesFullSize: Boolean = false

/** The copy the metadata names: a phone and a television already draw it at about its size. */
@Suppress("UNUSED_PARAMETER")
internal fun sharperUrl(url: String, pixels: Int): String = url

/** Bilinear, as always on Android: cheap enough for a television stick. */
internal val artFilter: androidx.compose.ui.graphics.FilterQuality = androidx.compose.ui.graphics.FilterQuality.Low

/** TDLib's file [fileId], downloaded to [path], decoded as [decodeImageFile] does. */
@Suppress("UNUSED_PARAMETER")
internal suspend fun decodeTdImage(fileId: Int, path: String, maxWidth: Int): ImageBitmap? = decodeImageFile(path, maxWidth)
