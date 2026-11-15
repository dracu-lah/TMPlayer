package com.tmplayer.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.tmplayer.data.TvArt
import com.tmplayer.data.TvChannels
import com.tmplayer.data.WatchNext
import com.tmplayer.data.WatchNextPublisher
import com.tmplayer.platform.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * The one picture the Continue watching widget shows: the poster the app already has for the
 * newest unfinished video, kept as a file of the app's own so the widget can draw it without
 * Telegram or the network being up.
 *
 * Where it comes from, in order: the online poster in the metadata cache ([TvArt.posterFile], what
 * Home already looked up, only when the viewer turned online posters on), else Telegram's thumbnail
 * of the video ([TvArt.thumbnailPath], downloaded first when it is not on the device). Neither is
 * kept for a chat that restricts saving content ([com.tmplayer.data.MediaItem.canBeSaved] false):
 * the widget is another surface and such a video is watched in the app only. The tile then has no
 * picture, and the files kept for earlier videos are deleted with it.
 *
 * Only one file is kept, for one video: the directory is in the cache, so Android may clear it,
 * and a tile without its file is a plain tile until the next [prepare]. Nothing here is called by
 * the system's own refresh: that one only draws what is already on disk.
 */
object WidgetArt {

    private const val TAG = "WidgetArt"

    /** The longest side the widget decodes a picture at: a RemoteViews bitmap crosses a process and has a memory ceiling. */
    internal const val MAX_SIDE_PX = 360

    /** How long Telegram's answer about the video, and then each picture, may take. */
    private const val STEP_TIMEOUT_MS = 15_000L

    /** Online lookups and downloads one video's picture may make: the title's lookup, then its poster. */
    private const val ONLINE_BUDGET = 2

    private val tried = HashSet<String>()

    internal fun dir(context: Context): File = File(context.cacheDir, "widget-art")

    private fun keyOf(video: WatchNext.Video): String = video.internalId

    private fun fileFor(context: Context, key: String): File = File(dir(context), "a_${TvArt.sha1(key)}.img")

    /** The picture kept for [video], or null for a plain tile. */
    fun fileFor(context: Context, video: WatchNext.Video): File? =
        fileFor(context, keyOf(video)).takeIf { it.isFile && it.length() > 0L }

    /** The picture decoded small enough for the widget, or null when there is none or it cannot be read. */
    suspend fun bitmapFor(context: Context, video: WatchNext.Video): Bitmap? = withContext(Dispatchers.IO) {
        val file = fileFor(context, video) ?: return@withContext null
        runCatching { decode(file) }.getOrNull()
    }

    internal fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE_PX) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(file.path, options)
    }

    /**
     * Makes sure the picture for [video] is on disk, once per video per process: asks Telegram for
     * the video (waiting at most [STEP_TIMEOUT_MS], since the account may not be connected yet),
     * then the online poster, then the thumbnail. Called from the running app, never from the
     * system's refresh. Returns whether a picture is there afterwards.
     */
    suspend fun prepare(context: Context, video: WatchNext.Video): Boolean {
        if (fileFor(context, video) != null) {
            prune(context, keep = keyOf(video))
            return true
        }
        val key = keyOf(video)
        synchronized(tried) { if (!tried.add(key)) return false }
        // A video whose picture could not be had stays "tried" for the rest of the process, so a
        // slow account or a video with no thumbnail is not asked about again at every redraw.
        return try {
            fetch(context, video)
        } catch (e: CancellationException) {
            // Cut off (the app went away, a newer tile took over): asked for again next time.
            synchronized(tried) { tried.remove(key) }
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "widget picture not made", e)
            false
        }
    }

    private suspend fun fetch(context: Context, video: WatchNext.Video): Boolean {
        val key = keyOf(video)
        val item = withTimeoutOrNull(STEP_TIMEOUT_MS) { WatchNextPublisher.mediaLookup(video.chatId, video.messageId) }
            ?: return false
        if (!item.canBeSaved) {
            // A protected chat: no picture of it leaves the app, and the ones kept before go.
            prune(context, keep = null)
            return false
        }
        val art = TvChannels.Art(
            thumbnailFileId = item.thumbnailFileId,
            titled = false,
            name = item.nameForParsing,
            caption = item.caption,
        )
        var source: File? = null
        if (art.name.isNotBlank()) {
            source = withTimeoutOrNull(STEP_TIMEOUT_MS) { TvArt.posterFile(art, TvArt.OnlineBudget(ONLINE_BUDGET)) }
        }
        if (source == null && art.thumbnailFileId > 0) {
            source = withTimeoutOrNull(STEP_TIMEOUT_MS) {
                runCatching { TvArt.thumbnailPath(art.thumbnailFileId) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let(::File)
            }
        }
        if (source == null || !source.isFile || source.length() == 0L) return false
        return withContext(Dispatchers.IO) { keep(context, source, key) }
    }

    private fun keep(context: Context, source: File, key: String): Boolean = runCatching {
        val dir = dir(context).apply { mkdirs() }
        val target = fileFor(context, key)
        val temp = File(dir, "${target.name}.part")
        source.copyTo(temp, overwrite = true)
        if (!temp.renameTo(target)) {
            temp.delete()
            return false
        }
        prune(context, keep = key)
        true
    }.getOrDefault(false)

    /** Deletes every picture but [keep]'s (all of them for null), and any half copy left by a crash. */
    fun prune(context: Context, keep: String?) {
        runCatching {
            val kept = keep?.let { fileFor(context, it).name }
            dir(context).listFiles()?.forEach { file -> if (file.name != kept) file.delete() }
        }
    }

    /** Takes every picture away: the last widget was removed. */
    fun clear(context: Context) {
        prune(context, keep = null)
        synchronized(tried) { tried.clear() }
    }

    /** For tests: forget what was tried this process. */
    internal fun forgetTried() = synchronized(tried) { tried.clear() }
}
