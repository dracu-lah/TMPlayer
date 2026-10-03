package com.tmplayer.ui.auth

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** ARGB pixels with no transparency as a bitmap: RGB_565, half the memory, as it always was. */
internal fun opaqueImage(pixels: IntArray, width: Int, height: Int): ImageBitmap =
    Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565).apply {
        setPixels(pixels, 0, width, 0, 0, width, height)
    }.asImageBitmap()
