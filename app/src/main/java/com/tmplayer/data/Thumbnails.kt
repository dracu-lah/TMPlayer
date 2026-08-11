package com.tmplayer.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import dev.g000sha256.tdl.TdlResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Telegram thumbnail data for media previews.
 *
 * Telegram ships a few-hundred-byte blurred JPEG inside every message, so a card can show
 * something the instant it scrolls into view, then swap in the real thumbnail once TDLib has
 * fetched it. No image library involved: these are tiny bitmaps and a 1 GB stick appreciates
 * the missing dependency.
 */
object Thumbnails {

    /**
     * Sized from the device rather than fixed.
     *
     * A flat 12 MB was a twelfth of the heap on a 1 GB stick, whose per-app limit is around 96 MB,
     * and a rounding error on a phone with 8 GB. An eighth of the heap is the figure image
     * libraries settle on for the same reason.
     */
    @Volatile
    private var cache = newCache(CACHE_BYTES)

    private fun newCache(bytes: Int) = object : LruCache<String, Bitmap>(bytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Called once from [com.tmplayer.App], before anything has been cached. */
    fun sizeFor(memoryClassMb: Int) {
        val bytes = (memoryClassMb * 1024 * 1024 / CACHE_HEAP_FRACTION)
            .coerceIn(MIN_CACHE_BYTES, MAX_CACHE_BYTES)
        cache = newCache(bytes)
    }

    /**
     * Hands the pictures back when the system says it needs the memory.
     *
     * Nothing implemented onTrimMemory anywhere before this, so the one cache in the app that
     * holds bitmaps held on to all of them right up to the point the process was killed for it.
     */
    fun trim(level: Int) {
        if (level >= TRIM_EVERYTHING) cache.evictAll() else cache.trimToSize(cache.size() / 2)
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
        val td = Td.awaitAuthorizedSession().client
        val file = td.getFile(fileId).valueOrNull ?: return null
        if (file.local.isDownloadingCompleted && !file.local.path.isNullOrEmpty()) {
            return file.local.path
        }
        if (!NetworkMonitor.canTryInternet() && !Td.connected.value) return null

        // Thumbnails are small and plentiful; low priority keeps them behind playback.
        val started = td.downloadFile(
            fileId = fileId,
            priority = THUMBNAIL_PRIORITY,
            offset = 0,
            limit = 0,
            synchronous = false,
        )
        if (started is TdlResult.Failure) return null

        // A row scrolls away and the coroutine waiting on its picture is cancelled, but TDLib was
        // never part of that conversation and carries on fetching. On a long scroll that is a
        // queue of downloads for cards nobody can see, competing with the video for the same
        // connection, so the request is withdrawn on the way out as well.
        val done = try {
            withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
                td.fileUpdates
                    .filter { it.file.id == fileId && it.file.local.isDownloadingCompleted }
                    .first()
            }
        } catch (cancellation: CancellationException) {
            Td.cancelDownloadInBackground(fileId)
            throw cancellation
        }
        if (done == null) Td.cancelDownloadInBackground(fileId)
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
    private const val CACHE_HEAP_FRACTION = 8
    private const val MIN_CACHE_BYTES = 4 * 1024 * 1024
    private const val MAX_CACHE_BYTES = 32 * 1024 * 1024

    /** At or above this, the system is asking for everything it can get. */
    private const val TRIM_EVERYTHING = android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
    private const val MAX_THUMBNAIL_WIDTH = 400
    private const val THUMBNAIL_PRIORITY = 4
    private const val DOWNLOAD_TIMEOUT_MS = 20_000L
}
