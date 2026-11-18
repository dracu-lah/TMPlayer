package com.tmplayer.player

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * "Save a frame" on the phone: the picture on screen, at the video's own size, as a PNG in
 * Pictures/TMPlayer, the folder the desktop's S key fills too.
 *
 * The player draws into a [SurfaceView] (Media3's PlayerView default), whose pixels never pass
 * through the view hierarchy, so [PixelCopy] is the one way to read them; the subtitles, drawn in
 * a view of their own, are not in it. The file goes in through MediaStore, which from Android 10
 * needs no storage permission for an app's own pictures. Before that it would need
 * WRITE_EXTERNAL_STORAGE, which this app does not ask for: [supported] is false there, and the
 * menu line says so rather than asking for a permission over all of the phone's files.
 *
 * Never offered for a chat that restricts saving: the activity leaves the line out (see
 * [PlayerMenu.phoneEntries]).
 */
object FrameSaver {

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** The largest side the copy is made at: a 4K frame as is, anything bigger scaled down to it. */
    private const val MAX_SIDE = 3840

    /** The frame showing in [surface], [width] by [height] (the video's size), or null when it could not be read. */
    suspend fun capture(surface: SurfaceView, width: Int, height: Int): Bitmap? {
        // PixelCopy is Android 7's. The menu only offers this from [supported] on, so this is a
        // second line, not the one that hides it.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        if (width <= 0 || height <= 0 || !surface.holder.surface.isValid) return null
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(width, height))
        val bitmap = Bitmap.createBitmap((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val result = suspendCancellableCoroutine { done ->
            runCatching {
                PixelCopy.request(surface, bitmap, { code -> done.resume(code) }, Handler(Looper.getMainLooper()))
            }.onFailure { done.resume(PixelCopy.ERROR_UNKNOWN) }
        }
        if (result != PixelCopy.SUCCESS) {
            bitmap.recycle()
            return null
        }
        return bitmap
    }

    /**
     * Writes [bitmap] to Pictures/TMPlayer as "<title> <time>.png" and gives back its address in
     * the gallery, or null when it could not be written. MediaStore adds " (1)" to a name already
     * there, so a second press on a paused frame keeps both.
     */
    suspend fun save(context: Context, bitmap: Bitmap, title: String, positionMs: Long): Uri? = withContext(Dispatchers.IO) {
        if (!supported) return@withContext null
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName(title, positionMs))
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TMPlayer")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) }.getOrNull()
            ?: return@withContext null
        val written = runCatching {
            resolver.openOutputStream(uri)?.use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) } == true
        }.getOrDefault(false)
        if (!written) {
            runCatching { resolver.delete(uri, null, null) }
            return@withContext null
        }
        runCatching {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
        uri
    }

    private val VIDEO_EXTENSION = Regex("(?i)\\.(mkv|mp4|m4v|mov|webm|avi|ts|wmv|flv)$")
    private val WHITESPACE = Regex("\\s+")

    /** "Night Train 01-02-03.png": the video's name without its extension, and the moment. */
    fun fileName(title: String, positionMs: Long): String {
        val name = title.replace(VIDEO_EXTENSION, "")
            .map { c -> if (c < ' ' || c in "\\/:*?\"<>|") ' ' else c }.joinToString("")
            .replace(WHITESPACE, " ").take(80).trim().trimEnd('.', ' ')
            .ifBlank { "TMPlayer" }
        val total = (positionMs / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        val clock = if (h > 0) "%d-%02d-%02d".format(h, m, s) else "%02d-%02d".format(m, s)
        return "$name $clock.png"
    }
}
