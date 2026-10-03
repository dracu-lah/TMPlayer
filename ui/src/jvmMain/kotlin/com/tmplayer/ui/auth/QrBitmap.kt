package com.tmplayer.ui.auth

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** ARGB pixels with no transparency as a Skia bitmap. */
internal fun opaqueImage(pixels: IntArray, width: Int, height: Int): ImageBitmap {
    // Skia's N32 is BGRA in memory on every platform the desktop ships for, which is an ARGB int
    // written little-endian.
    val bytes = ByteBuffer.allocate(pixels.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    bytes.asIntBuffer().put(pixels)
    val bitmap = Bitmap()
    bitmap.allocPixels(ImageInfo.makeN32(width, height, ColorAlphaType.OPAQUE))
    bitmap.installPixels(bytes.array())
    bitmap.setImmutable()
    return bitmap.asComposeImageBitmapCompat()
}

private fun Bitmap.asComposeImageBitmapCompat(): ImageBitmap =
    org.jetbrains.skia.Image.makeFromBitmap(this).toComposeImageBitmap()
