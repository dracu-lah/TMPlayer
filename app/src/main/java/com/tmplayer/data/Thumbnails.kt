package com.tmplayer.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import dev.g000sha256.tdl.TdlResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Poster art for the grid.
 *
 * Telegram ships a few-hundred-byte blurred JPEG inside every message, so a card can show
 * something the instant it scrolls into view, then swap in the real thumbnail once TDLib has
 * fetched it. No image library involved: these are tiny bitmaps and a 1 GB stick appreciates
 * the missing dependency.
 */
object Thumbnails {

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun mini(data: ByteArray?): Bitmap? {
        if (data == null || data.isEmpty()) return null
        val key = "mini:${data.size}:${data.contentHashCode()}"
        cache.get(key)?.let { return it }
        return decode(data, 0, data.size)?.also { cache.put(key, it) }
    }

    /** Downloads the real thumbnail if needed. Returns null when there isn't one. */
    suspend fun full(fileId: Int): Bitmap? {
        if (fileId <= 0) return null
        val key = "file:$fileId"
        cache.get(key)?.let { return it }

        val path = withContext(Dispatchers.IO) { downloadThumbnail(fileId) } ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { decodeFile(path) }.getOrNull()?.also { cache.put(key, it) }
        }
    }

    private suspend fun downloadThumbnail(fileId: Int): String? {
        val td = runCatching { Td.client }.getOrNull() ?: return null
        val file = td.getFile(fileId).valueOrNull ?: return null
        if (file.local.isDownloadingCompleted && !file.local.path.isNullOrEmpty()) {
            return file.local.path
        }

        // Thumbnails are small and plentiful; low priority keeps them behind playback.
        val started = td.downloadFile(
            fileId = fileId,
            priority = THUMBNAIL_PRIORITY,
            offset = 0,
            limit = 0,
            synchronous = false,
        )
        if (started is TdlResult.Failure) return null

        val done = withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
            td.fileUpdates
                .filter { it.file.id == fileId && it.file.local.isDownloadingCompleted }
                .first()
        }
        val path = done?.file?.local?.path ?: td.getFile(fileId).valueOrNull?.local?.path
        return path?.takeIf { it.isNotEmpty() }
    }

    private fun decode(data: ByteArray, offset: Int, length: Int): Bitmap? =
        runCatching { BitmapFactory.decodeByteArray(data, offset, length) }.getOrNull()

    private fun decodeFile(path: String): Bitmap? {
        // Card art is never larger than ~400 px wide; decoding full size would waste heap.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > MAX_THUMBNAIL_WIDTH * 2) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(path, options)
    }

    private const val CACHE_BYTES = 12 * 1024 * 1024
    private const val MAX_THUMBNAIL_WIDTH = 400
    private const val THUMBNAIL_PRIORITY = 4
    private const val DOWNLOAD_TIMEOUT_MS = 20_000L
}
