package com.tmplayer.ui.auth

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders a Telegram login link as a QR code.
 *
 * Deliberately plain black-on-white: phone cameras across a living room read that far more
 * reliably than anything tinted to match the app.
 */
object QrCode {

    fun render(text: String, sizePx: Int): Bitmap? = runCatching {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                pixels[row + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }.getOrNull()
}
