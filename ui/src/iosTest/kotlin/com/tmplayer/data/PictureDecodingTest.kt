package com.tmplayer.data

import androidx.compose.ui.graphics.ImageBitmapConfig
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * iOS decodes picture files as Android's BitmapFactory path does: sampled to the width asked for,
 * an opaque one in 16-bit colour, one with [alpha] in 32-bit, and nothing for a file that is not
 * a picture, without throwing.
 */
class PictureDecodingTest {
    private val written = mutableListOf<File>()

    @AfterTest
    fun removeFiles() {
        written.forEach { it.delete() }
    }

    /** A [width] by [height] picture in [format], red on the left half and blue on the right. */
    private fun picture(width: Int, height: Int, format: EncodedImageFormat, clear: Boolean = false): File {
        val bytes = ByteArray(width * height * 4)
        for (y in 0 until height) for (x in 0 until width) {
            val i = (y * width + x) * 4
            val left = x < width / 2
            bytes[i] = if (left) 0xFF.toByte() else 0
            bytes[i + 2] = if (left) 0 else 0xFF.toByte()
            bytes[i + 3] = if (clear && y < height / 2) 0 else 0xFF.toByte()
            if (clear && y < height / 2) {
                bytes[i] = 0
                bytes[i + 2] = 0
            }
        }
        val alphaType = if (clear) ColorAlphaType.PREMUL else ColorAlphaType.OPAQUE
        val image = Image.makeRaster(ImageInfo(width, height, ColorType.RGBA_8888, alphaType), bytes, width * 4)
        val encoded = assertNotNull(image.encodeToData(format, 90)).bytes
        val dir = platform.Foundation.NSTemporaryDirectory()
        val file = File("$dir/picture-${written.size}-${width}x$height.${format.name.lowercase()}")
        file.writeBytes(encoded)
        written += file
        return file
    }

    @Test
    fun aLargeOpaquePictureIsSampledToTheWidthAskedForIn16BitColour() {
        val file = picture(2160, 2880, EncodedImageFormat.JPEG)
        val bitmap = assertNotNull(decodeImageFile(file.path, 480))
        assertEquals(480, bitmap.width)
        assertEquals(640, bitmap.height)
        assertEquals(ImageBitmapConfig.Rgb565, bitmap.config)
        assertEquals(480 * 640 * 2, bitmap.byteCount())
        // The colours survive the sampling: red on the left, blue on the right.
        val pixel = IntArray(1)
        bitmap.readPixels(pixel, 60, 320, 1, 1)
        assertTrue((pixel[0] shr 16 and 0xFF) > 200 && (pixel[0] and 0xFF) < 60, "left is red: ${pixel[0].toUInt().toString(16)}")
        bitmap.readPixels(pixel, 420, 320, 1, 1)
        assertTrue((pixel[0] and 0xFF) > 200 && (pixel[0] shr 16 and 0xFF) < 60, "right is blue: ${pixel[0].toUInt().toString(16)}")
    }

    @Test
    fun aPictureNarrowerThanAskedKeepsItsOwnSize() {
        val file = picture(320, 180, EncodedImageFormat.JPEG)
        val bitmap = assertNotNull(decodeImageFile(file.path, 400))
        assertEquals(320, bitmap.width)
        assertEquals(180, bitmap.height)
    }

    @Test
    fun aPictureWithAClearGroundKeepsItIn32BitColour() {
        val file = picture(800, 400, EncodedImageFormat.PNG, clear = true)
        val bitmap = assertNotNull(decodeImageFile(file.path, 400, alpha = true))
        assertEquals(400, bitmap.width)
        assertEquals(200, bitmap.height)
        assertEquals(ImageBitmapConfig.Argb8888, bitmap.config)
        val pixel = IntArray(1)
        bitmap.readPixels(pixel, 100, 20, 1, 1)
        assertEquals(0, pixel[0] ushr 24, "the top half is clear")
        bitmap.readPixels(pixel, 100, 180, 1, 1)
        assertEquals(0xFF, pixel[0] ushr 24, "the bottom half is not")
    }

    @Test
    fun aFileThatIsNotAPictureGivesNothing() {
        val dir = platform.Foundation.NSTemporaryDirectory()
        val inputs = listOf(
            ByteArray(0),
            "not a picture".encodeToByteArray(),
            // A JPEG's start and nothing after it.
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10),
            ByteArray(4096) { (it * 31 + 7).toByte() },
        )
        inputs.forEachIndexed { i, bytes ->
            val file = File("$dir/garbage-$i.jpg")
            file.writeBytes(bytes)
            written += file
            assertNull(decodeImageFile(file.path, 480), "garbage $i")
        }
        assertNull(decodeImageFile("$dir/no-such-picture.jpg", 480))
    }

    /**
     * A video handed in as a thumbnail (an MP4's first bytes, then 40 MB) gives nothing, as
     * BitmapFactory gives null for it, and is not read whole on the way: read whole, a 268 MB one
     * took the app past what iOS allows.
     */
    @Test
    fun aLargeFileThatIsNotAPictureGivesNothing() {
        val dir = platform.Foundation.NSTemporaryDirectory()
        val file = File("$dir/video-as-thumbnail.mp4")
        written += file
        file.outputStream().use { out ->
            out.write(byteArrayOf(0, 0, 0, 0x20, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6F, 0x6D))
            val block = ByteArray(1024 * 1024) { (it * 7).toByte() }
            repeat(40) { out.write(block) }
        }
        assertNull(decodeImageFile(file.path, 400))
    }

    @Test
    fun aCutShortPictureDecodesOrGivesNothingWithoutThrowing() {
        val whole = picture(1280, 720, EncodedImageFormat.JPEG).readBytes()
        val dir = platform.Foundation.NSTemporaryDirectory()
        val file = File("$dir/cut.jpg")
        file.writeBytes(whole.copyOf(whole.size / 3))
        written += file
        val bitmap = decodeImageFile(file.path, 480)
        if (bitmap != null) assertEquals(480, bitmap.width)
    }
}
