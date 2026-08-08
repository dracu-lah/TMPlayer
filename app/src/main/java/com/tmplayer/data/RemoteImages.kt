package com.tmplayer.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Poster and backdrop art fetched over HTTP, cached in memory and on disk.
 *
 * The same reasoning as [Thumbnails]: this app needs to display a handful of JPEGs and an image
 * library would be a large dependency to carry for that. The disk half matters more here than it
 * does for Telegram thumbnails, because TMDB art is fetched over the internet rather than read
 * from a file TDLib already downloaded, so losing it on every restart would be visible.
 *
 * Failures are silent by design. Missing art means a tile falls back to its letter, which is a
 * perfectly readable outcome; an error message about a poster would be noise.
 */
object RemoteImages {

    private val memory = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    @Volatile
    private var diskDir: File? = null

    /** Guards against two tiles fetching the same poster at once, which is the common case. */
    private val inFlight = mutableMapOf<String, Mutex>()
    private val inFlightLock = Mutex()

    fun init(cacheDir: File) {
        diskDir = File(cacheDir, "tmdb").apply { mkdirs() }
    }

    /** Returns null whenever the image cannot be had, for any reason. */
    suspend fun load(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        memory.get(url)?.let { return it }

        val lock = inFlightLock.withLock { inFlight.getOrPut(url) { Mutex() } }
        return lock.withLock {
            // A second caller that queued behind the first finds the result already cached.
            memory.get(url)?.let { return@withLock it }

            val bitmap = withContext(Dispatchers.IO) {
                fromDisk(url) ?: if (NetworkMonitor.canTryInternet()) fromNetwork(url) else null
            }
            bitmap?.also { memory.put(url, it) }
        }.also {
            inFlightLock.withLock { inFlight.remove(url) }
        }
    }

    private fun fromDisk(url: String): Bitmap? {
        val file = cacheFile(url) ?: return null
        if (!file.exists() || file.length() == 0L) return null
        return runCatching { decode(file) }.getOrNull()
    }

    private fun fromNetwork(url: String): Bitmap? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        try {
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
            val bytes = connection.inputStream.use { it.readBytes() }

            // Written through a temporary file so a download cut halfway never leaves a
            // truncated JPEG behind that every later read would decode as null.
            cacheFile(url)?.let { target ->
                val temp = File(target.parentFile, "${target.name}.part")
                temp.writeBytes(bytes)
                if (!temp.renameTo(target)) temp.delete()
            }

            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options())
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun decode(file: File): Bitmap? = BitmapFactory.decodeFile(file.absolutePath, options())

    // RGB_565 halves the heap cost per bitmap. Posters are photographic and full of gradients,
    // but at the size a TV card renders them the banding is not visible from a sofa, and the
    // stick has 1 GB of RAM to spend on everything including the video decoder.
    private fun options() = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.RGB_565
    }

    private fun cacheFile(url: String): File? {
        val dir = diskDir ?: return null
        return File(dir, hash(url))
    }

    private fun hash(url: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Called when the viewer clears the cache, so art does not survive a deliberate wipe. */
    fun clear() {
        memory.evictAll()
        runCatching { diskDir?.listFiles()?.forEach { it.delete() } }
    }

    private const val MEMORY_BYTES = 16 * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 10_000
}
