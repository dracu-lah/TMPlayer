package com.tmplayer.data

import android.content.Context
import java.io.File

/**
 * The videos playing left behind: who claims the disk, who gives it up, and what to do about the
 * copies that were left on it before any of this existed.
 *
 * A download is a video the viewer ticked. A cached video is one that landed only because somebody
 * pressed Play, and the deal has always been that there is one of it and the next press replaces
 * it. [CacheShelf] does the arithmetic for that; this does the bookkeeping.
 *
 * It exists because the deal was not being kept. Eviction lived on the screen a video is started
 * from, and stepping to the next episode inside the player does not go past that screen: it starts
 * the player again directly. So a series watched end to end evicted nothing, and every episode
 * stayed. The record of what the cache held still named episode one, which is why the app could
 * report a cached video of 400 MB while holding 2.3 GB of them, and why nothing on the Downloads
 * screen could point at the difference.
 *
 * [claim] is the fix, and it is called from the player rather than from any screen, so it runs once
 * for every video that is ever opened however it was opened.
 *
 * [strays] is the apology to devices that already have the problem. The records are gone, but the
 * files are not: TDLib keeps them in a directory this app owns, and anything in there that is not
 * a download and not one of the cached videos we know about is a stray. Named from its own file
 * name, measured from disk, and deletable like anything else. That is the difference between two
 * and a half gigabytes the viewer can see and get rid of, and two and a half gigabytes that are
 * simply missing.
 */
object WatchCache {

    /**
     * Marks a video as the one the cache is holding, and gives up the ones it was holding before.
     *
     * Called at the top of every playback. Idempotent: opening the same video twice claims it
     * twice and deletes nothing, because the only thing evicted is a cached video that is not this
     * one.
     *
     * Three kinds of file survive it. The video being played, obviously. Anything the viewer
     * downloaded on purpose, which is not the cache's to spend. And anything a download is
     * currently fetching, which would otherwise be deleted out from under the queue.
     */
    suspend fun claim(context: Context, item: MediaItem, chatTitle: String) {
        val settings = SettingsStore(context)
        val kept = runCatching { settings.isKeptDownload(item.chatId, item.messageId) }
            .getOrDefault(false)
        // A download is not cache and must never be recorded as it: the next claim would delete a
        // film the viewer chose to keep.
        if (!kept) runCatching { settings.rememberCachedVideo(item, chatTitle) }
        evictAllBut(context, item.fileId)
    }

    /**
     * Deletes every cached video except [keepFileId], and forgets the records once they are gone.
     *
     * A record is only dropped when the bytes actually went. TDLib can refuse, and a record
     * removed for a file still on disk is exactly the state this whole file exists to prevent.
     */
    suspend fun evictAllBut(context: Context, keepFileId: Int) {
        val settings = SettingsStore(context)
        val cached = runCatching { settings.cachedVideosNow() }.getOrDefault(emptyList())
        if (cached.isEmpty()) return
        val busy = OfflineDownloads.active.value.keys
        for (record in cached) {
            if (record.fileId == keepFileId || record.fileId in busy) continue
            if (runCatching { settings.isKeptDownload(record.chatId, record.messageId) }
                    .getOrDefault(false)
            ) {
                // Downloaded since it was cached. It belongs to the viewer now, so the cache lets
                // go of the record without touching the file.
                runCatching { settings.forgetCachedVideo(record.chatId, record.messageId) }
                continue
            }
            runCatching { Td.deleteFile(record.fileId) }
            val left = runCatching { Td.localDownloadedBytes(record.fileId) }.getOrDefault(0L)
            if (left <= 0) runCatching { settings.forgetCachedVideo(record.chatId, record.messageId) }
        }
    }

    /** One video on the disk that nothing in the app has a name for. */
    data class Stray(val path: String, val bytes: Long, val modifiedAt: Long) {

        /** The file's own name, which is the only identifying thing about it. */
        val fileName: String get() = File(path).name

        /**
         * Something to call it, taken from the file itself.
         *
         * TDLib names a file after the one on Telegram where it knows the name, and after nothing
         * in particular where it does not: a good half of what a binge leaves behind is called
         * "75". A row headed "75" is worse than one headed "Cached video", because the first reads
         * as a name the viewer ought to recognise and the second says plainly that there is
         * nothing to recognise. The file name goes on the line below either way, so two of them
         * are still told apart.
         */
        val title: String
            get() {
                val stem = fileName.substringBeforeLast('.').replace('_', ' ').trim()
                return if (stem.any { it.isLetter() }) stem else "Cached video"
            }
    }

    /**
     * Every video file on this device that is neither a download nor a cached video we can name.
     *
     * Found by walking TDLib's own files directory rather than by asking TDLib, because TDLib will
     * report the total and the breakdown but not the individual files, and a total is precisely
     * what the viewer already had and could do nothing with.
     *
     * @param knownPaths the local paths of everything already on the Downloads screen, which are
     *   asked of TDLib by the caller: only it knows which records are worth resolving.
     */
    fun strays(context: Context, knownPaths: Set<String>): List<Stray> =
        straysIn(File(context.filesDir, "tdlib-files"), knownPaths)

    /** The same, against a directory rather than a device, so the rule can be tested. */
    internal fun straysIn(root: File, knownPaths: Set<String>): List<Stray> {
        if (!root.isDirectory) return emptyList()
        return MEDIA_DIRECTORIES
            .map { File(root, it) }
            .filter { it.isDirectory }
            .flatMap { directory -> directory.walkTopDown().maxDepth(3).toList() }
            .filter { it.isFile && it.length() > 0 && it.absolutePath !in knownPaths }
            .map { Stray(it.absolutePath, it.length(), it.lastModified()) }
            .sortedByDescending { it.bytes }
    }

    /** Removes one stray from the disk. TDLib refetches it if the video is ever played again. */
    fun forget(stray: Stray): Boolean = runCatching { File(stray.path).delete() }.getOrDefault(false)

    /**
     * The directories a film can be in.
     *
     * Photos, thumbnails, profile photos and stickers are deliberately absent: they are small,
     * deleting them makes the browse grid grey for no gain, and the Settings screen has its own
     * button for the viewer who wants that space anyway. `temp` is here because a watch abandoned
     * part way leaves its bytes there, and half a film is still half a gigabyte.
     */
    private val MEDIA_DIRECTORIES = listOf(
        "videos",
        "documents",
        "animations",
        "video_notes",
        "temp",
    )
}
