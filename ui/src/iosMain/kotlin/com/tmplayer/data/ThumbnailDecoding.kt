@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.tmplayer.data

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFNumberGetValue
import platform.CoreFoundation.CFNumberRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFURLRef
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextSetInterpolationQuality
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.CoreGraphics.kCGColorSpaceSRGB
import platform.CoreGraphics.kCGInterpolationHigh
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSURL
import platform.ImageIO.CGImageSourceCopyPropertiesAtIndex
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.CGImageSourceCreateWithURL
import platform.ImageIO.kCGImagePropertyPixelHeight
import platform.ImageIO.kCGImagePropertyPixelWidth
import platform.ImageIO.kCGImageSourceCreateThumbnailFromImageAlways
import platform.ImageIO.kCGImageSourceCreateThumbnailWithTransform
import platform.ImageIO.kCGImageSourceShouldCache
import platform.ImageIO.kCGImageSourceShouldCacheImmediately
import platform.ImageIO.kCGImageSourceThumbnailMaxPixelSize

// iOS's half of [Thumbnails], decoding as Android's does: every picture from a file sampled down to
// the width asked for, an opaque one in 16-bit colour, so the cache keys it by that width. Apple's
// ImageIO samples it as it decodes (as BitmapFactory does), so a large original never sits in
// memory at full size; Skia holds the result, since Compose Multiplatform draws through Skia.

/** An opaque picture from [argb], one Int per pixel, row by row ([MiniBlur]). */
internal fun imageFromPixels(argb: IntArray, width: Int, height: Int): ImageBitmap {
    val bytes = ByteArray(argb.size * 4)
    for (i in argb.indices) {
        val p = argb[i]
        bytes[i * 4] = (p shr 16).toByte()
        bytes[i * 4 + 1] = (p shr 8).toByte()
        bytes[i * 4 + 2] = p.toByte()
        bytes[i * 4 + 3] = 0xFF.toByte()
    }
    val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE)
    return Image.makeRaster(info, bytes, width * 4).toComposeImageBitmap()
}

/** Bytes in memory (an inline preview), at full size, as Android's `decodeByteArray`. */
internal fun decodeImage(data: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(data).toComposeImageBitmap() }.getOrNull()
        .also { if (artCheck) describeBytes(data, it) }

/**
 * The picture in the file at [path], at most [maxWidth] pixels across, as Android decodes it:
 * [alpha] keeps a clear ground (a title logo), everything else is opaque and held in 16-bit colour,
 * half the memory. A file ImageIO cannot read is tried by Skia only when it starts as a picture
 * and is a picture's size: anything else gives nothing, as BitmapFactory gives null for it after
 * its first bytes. Read whole instead, a 268 MB video handed in as a thumbnail took Kotlin's heap
 * past 1.6 GB and iOS ended the app (iPhone 13, 2026-11-12).
 */
internal fun decodeImageFile(path: String, maxWidth: Int, alpha: Boolean = false): ImageBitmap? {
    // In a pool of its own: the decoding threads are Kotlin's, with no pool to drain what Foundation autoreleases.
    val sampled = kotlinx.cinterop.autoreleasepool { runCatching { sampledDecode(path, maxWidth, alpha) }.getOrNull() }
    val bitmap = sampled ?: File(path).takeIf { looksLikePicture(it) }?.let { file -> runCatching { file.readBytes() }.getOrNull() }?.let(::decodeImage)
    if (artCheck) describe(path, bitmap)
    return bitmap
}

/** The largest file Skia is given whole when ImageIO could not read it. No thumbnail or poster comes near it. */
private const val MAX_FALLBACK_BYTES = 32L * 1024 * 1024

/** Whether [file] is small enough and opens as a format Skia reads (JPEG, PNG, GIF, WebP, BMP, ICO). */
private fun looksLikePicture(file: File): Boolean {
    val size = file.length()
    if (size <= 0 || size > MAX_FALLBACK_BYTES) return false
    val head = runCatching { file.inputStream().use { it.readNBytes(12) } }.getOrNull() ?: return false
    fun at(i: Int) = if (i < head.size) head[i].toInt() and 0xFF else -1
    return (at(0) == 0xFF && at(1) == 0xD8) ||
        (at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47) ||
        (at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46) ||
        (at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 && at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50) ||
        (at(0) == 0x42 && at(1) == 0x4D) ||
        (at(0) == 0x00 && at(1) == 0x00 && at(2) == 0x01 && at(3) == 0x00)
}

/** The most pixels one picture is decoded to; a longer one is sampled further to fit. 64 MB at 32 bits. */
private const val MAX_PIXELS = 16L * 1024 * 1024

private fun sampledDecode(path: String, maxWidth: Int, alpha: Boolean): ImageBitmap? {
    val url = CFBridgingRetain(NSURL.fileURLWithPath(path)) ?: return null
    val source = try {
        @Suppress("UNCHECKED_CAST")
        CGImageSourceCreateWithURL(url as CFURLRef, sourceOptions)
    } finally {
        CFRelease(url)
    }
    source ?: return null
    try {
        val (width, height) = pixelSize(source) ?: return null
        if (width <= 0 || height <= 0) return null
        // Android's width: the picture's own, or [maxWidth] where it is wider.
        var targetW = if (maxWidth in 1 until width) maxWidth else width
        var targetH = ((height.toLong() * targetW + width / 2) / width).toInt().coerceAtLeast(1)
        val pixels = targetW.toLong() * targetH
        if (pixels > MAX_PIXELS) {
            val scale = sqrt(MAX_PIXELS.toDouble() / pixels)
            targetW = (targetW * scale).toInt().coerceAtLeast(1)
            targetH = (targetH * scale).toInt().coerceAtLeast(1)
        }
        val thumbnail = thumbnail(source, maxOf(targetW, targetH)) ?: return null
        try {
            return draw(thumbnail, targetW, targetH, alpha)
        } finally {
            CGImageRelease(thumbnail)
        }
    } finally {
        CFRelease(source)
    }
}

/** Read the file when asked, not kept by ImageIO: the cache in [Thumbnails] is the one that keeps pictures. */
private val sourceOptions: CFDictionaryRef? by lazy {
    val options = CFDictionaryCreateMutable(null, 1, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
    CFDictionarySetValue(options, kCGImageSourceShouldCache, kCFBooleanFalse)
    options
}

/** The picture's own width and height, from its header. */
private fun pixelSize(source: platform.ImageIO.CGImageSourceRef): Pair<Int, Int>? {
    val properties = CGImageSourceCopyPropertiesAtIndex(source, 0u, null) ?: return null
    try {
        val width = intOf(CFDictionaryGetValue(properties, kCGImagePropertyPixelWidth)) ?: return null
        val height = intOf(CFDictionaryGetValue(properties, kCGImagePropertyPixelHeight)) ?: return null
        return width to height
    } finally {
        CFRelease(properties)
    }
}

private fun intOf(number: kotlinx.cinterop.COpaquePointer?): Int? = memScoped {
    if (number == null) return null
    val out = alloc<IntVar>()
    @Suppress("UNCHECKED_CAST")
    if (CFNumberGetValue(number as CFNumberRef, kCFNumberIntType, out.ptr)) out.value else null
}

/**
 * The picture decoded with its longer side at most [longest] pixels, sampled as it is decoded.
 * Its own orientation tag is not applied, as BitmapFactory does not apply it.
 */
private fun thumbnail(source: platform.ImageIO.CGImageSourceRef, longest: Int): platform.CoreGraphics.CGImageRef? {
    val options = CFDictionaryCreateMutable(null, 4, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        ?: return null
    try {
        val size = memScoped {
            val value = alloc<IntVar>().apply { this.value = longest }
            CFNumberCreate(null, kCFNumberIntType, value.ptr)
        } ?: return null
        try {
            // Always from the picture itself: a small preview stored in the file may be smaller than asked.
            CFDictionarySetValue(options, kCGImageSourceCreateThumbnailFromImageAlways, kCFBooleanTrue)
            CFDictionarySetValue(options, kCGImageSourceThumbnailMaxPixelSize, size)
            CFDictionarySetValue(options, kCGImageSourceCreateThumbnailWithTransform, kCFBooleanFalse)
            // Decoded here, on the decoding thread, not later where it is first drawn.
            CFDictionarySetValue(options, kCGImageSourceShouldCacheImmediately, kCFBooleanTrue)
            return CGImageSourceCreateThumbnailAtIndex(source, 0u, options)
        } finally {
            CFRelease(size)
        }
    } finally {
        CFRelease(options)
    }
}

/**
 * [image] drawn at [width] by [height] in sRGB, then held by Skia: 16-bit for an opaque picture,
 * 32-bit with [alpha]. CoreGraphics draws straight into Skia's own pixels, and the 16-bit copy is
 * read from there: drawn into a Kotlin array first, every poster went through Kotlin's heap and a
 * second copy into Skia, about 27 MB of the 1.2 GB allocated in Home's first 20 s (iPhone 13,
 * 2026-11-13, `-perfAllocs`; the decoding as a whole is about 50).
 */
private fun draw(image: platform.CoreGraphics.CGImageRef, width: Int, height: Int, alpha: Boolean): ImageBitmap? {
    val drawn = Bitmap()
    val type = if (alpha) ColorAlphaType.PREMUL else ColorAlphaType.OPAQUE
    if (!drawn.allocPixels(ImageInfo(width, height, ColorType.RGBA_8888, type)) || !drawInto(drawn, image, width, height)) {
        drawn.close()
        return null
    }
    if (alpha) {
        drawn.setImmutable()
        return drawn.asComposeImageBitmap()
    }
    val bitmap = Bitmap()
    try {
        val copied = bitmap.allocPixels(ImageInfo(width, height, ColorType.RGB_565, ColorAlphaType.OPAQUE)) &&
            readInto(drawn, bitmap)
        if (!copied) {
            bitmap.close()
            return null
        }
    } finally {
        drawn.close()
    }
    bitmap.setImmutable()
    return bitmap.asComposeImageBitmap()
}

/** [image] drawn by CoreGraphics into [bitmap]'s own pixels, RGBA premultiplied. */
private fun drawInto(bitmap: Bitmap, image: platform.CoreGraphics.CGImageRef, width: Int, height: Int): Boolean {
    val pixels = bitmap.peekPixels() ?: return false
    try {
        val space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB) ?: return false
        try {
            val context = CGBitmapContextCreate(
                kotlinx.cinterop.interpretCPointer<kotlinx.cinterop.ByteVar>(pixels.addr),
                width.toULong(),
                height.toULong(),
                8u,
                pixels.rowBytes.toULong(),
                space,
                CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big,
            ) ?: return false
            try {
                CGContextSetInterpolationQuality(context, kCGInterpolationHigh)
                CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), image)
            } finally {
                CGContextRelease(context)
            }
            return true
        } finally {
            CGColorSpaceRelease(space)
        }
    } finally {
        pixels.close()
    }
}

/** [source]'s pixels converted into [target]'s, in Skia's memory. */
private fun readInto(source: Bitmap, target: Bitmap): Boolean {
    val from = source.peekPixels() ?: return false
    val to = target.peekPixels()
    try {
        return to != null && from.readPixels(to)
    } finally {
        to?.close()
        from.close()
    }
}

/** A developer's check, launched with `-artCheck YES`: what each picture file decoded to. */
private val artCheck: Boolean by lazy { platform.Foundation.NSUserDefaults.standardUserDefaults.boolForKey("artCheck") }

private fun describe(path: String, bitmap: ImageBitmap?) {
    val file = File(path)
    com.tmplayer.platform.Logger.i(
        "ArtCheck",
        "${file.name.take(16)}: ${file.length() / 1024} KiB -> ${bitmap?.width}x${bitmap?.height} ${bitmap?.config}, " +
            "${bitmap?.byteCount()?.let { it / 1024 }} KiB, mean ${meanOf(bitmap)}",
    )
}

/** An inline preview: its bytes' size and key (as [PromoCovers.keyOf]), what it decoded to. */
private fun describeBytes(data: ByteArray, bitmap: ImageBitmap?) {
    com.tmplayer.platform.Logger.i(
        "ArtCheck",
        "preview ${PromoCovers.keyOf(data).toULong().toString(16)}: ${data.size} B -> ${bitmap?.width}x${bitmap?.height}, mean ${meanOf(bitmap)}",
    )
}

/** The mean brightness of 64 pixels spread over [bitmap], 0 to 255; -1 for none. */
private fun meanOf(bitmap: ImageBitmap?): Long {
    if (bitmap == null) return -1
    var sum = 0L
    var n = 0
    val one = IntArray(1)
    for (gy in 0 until 8) for (gx in 0 until 8) {
        val x = (bitmap.width - 1) * gx / 7
        val y = (bitmap.height - 1) * gy / 7
        runCatching { bitmap.readPixels(one, x, y, 1, 1) }.getOrNull() ?: continue
        val p = one[0]
        sum += ((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)
        n++
    }
    return if (n > 0) sum / (3 * n) else -1
}

/** What a decoded picture holds in memory, for the cache's budget: 2 bytes a pixel in 16-bit colour. */
internal fun ImageBitmap.byteCount(): Int = width * height * when (config) {
    ImageBitmapConfig.Rgb565 -> 2
    ImageBitmapConfig.Alpha8 -> 1
    ImageBitmapConfig.F16 -> 8
    else -> 4
}

/** iOS samples a picture down to the width asked for, as Android does, so the cache keys it by that width. */
internal const val decodesFullSize: Boolean = false

/** The copy the metadata names, as on Android: a phone or a tablet draws it at about its size. */
@Suppress("UNUSED_PARAMETER")
internal fun sharperUrl(url: String, pixels: Int): String = url

/** Bilinear, as on Android. */
internal val artFilter: androidx.compose.ui.graphics.FilterQuality = androidx.compose.ui.graphics.FilterQuality.Low

/**
 * TDLib's file [fileId], downloaded to [path], decoded as [decodeImageFile] does. One that is not a
 * picture is said in the log, with its size: a thumbnail id that names a video is worth finding.
 */
internal suspend fun decodeTdImage(fileId: Int, path: String, maxWidth: Int): ImageBitmap? =
    decodeImageFile(path, maxWidth).also {
        if (it == null) {
            val file = File(path)
            com.tmplayer.platform.Logger.w("Thumbnails", "file $fileId is not a picture: ${file.name.takeLast(48)}, ${file.length() / 1024} KiB")
        }
    }

/**
 * Hands the pictures back when the system needs the memory, as on Android: all of them when it is
 * critical or the app is behind, half otherwise. The shell turns iOS's memory warning into a
 * critical level when TMPlayer is the one short of memory, and hands every picture back itself
 * when the warning is the device's.
 */
fun Thumbnails.trim(level: Int) = trim(
    everything = level == android.app.Application.TRIM_MEMORY_RUNNING_CRITICAL ||
        level >= android.app.Application.TRIM_MEMORY_UI_HIDDEN,
)
