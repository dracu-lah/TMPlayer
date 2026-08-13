package com.tmplayer.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The videos playing left behind: who claims the disk, who gives it up, and what to do about the
 * copies nothing has a record of.
 *
 * A download is a video the viewer ticked. A cached video is one that landed only because somebody
 * pressed Play, and the deal is that there is one of it and the next press replaces it.
 * [CacheShelf] does the arithmetic for that; this does the bookkeeping.
 *
 * [claim] is called from the player rather than from any screen, so it runs once for every video
 * that is ever opened, however it was opened: stepping to the next episode starts the player
 * directly and never passes a browse screen.
 *
 * [strays] covers bytes with no record at all. TDLib keeps files in a directory this app owns, and
 * anything in there that is not a download and not a cached video we know about is a stray: named
 * from its own file name, measured from disk, and deletable like anything else. That is the
 * difference between gigabytes the viewer can see and get rid of, and gigabytes simply missing.
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
        // Held for the whole of the claim, because stepping through a series overlaps two of them.
        // A claim is a read of the cached list followed by deletes decided from it, and
        // `playEpisode` starts the next player before the last has finished: without the lock,
        // episode N's claim could delete the video episode N+1 had just started. Claims queue.
        claiming.withLock {
            inFlight += item.fileId
            try {
                val settings = SettingsStore(context)
                val kept = runCatching { settings.isKeptDownload(item.chatId, item.messageId) }
                    .getOrDefault(false)
                // A download is not cache and must never be recorded as it: the next claim would
                // delete a video the viewer chose to keep.
                if (!kept) runCatching { settings.rememberCachedVideo(item, chatTitle) }
                evictAllBut(context, item.fileId)
            } finally {
                inFlight -= item.fileId
            }
        }
    }

    /** Serialises [claim] against itself. See the note there. */
    private val claiming = Mutex()

    /**
     * The files of claims that have not finished yet, spared by [evictAllBut].
     *
     * The lock alone orders two claims but does not stop the second from deleting the first's
     * video, since by then the record is simply another cached video that is not the one being
     * kept. Backing out of one episode straight into another would lose the bytes now on screen.
     */
    private val inFlight = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

    /**
     * Deletes every cached video except [keepFileId], and forgets the records once they are gone.
     *
     * A record is only dropped when the bytes actually went. TDLib can refuse a delete, and a
     * record removed for a file still on disk loses track of those bytes for good.
     */
    suspend fun evictAllBut(context: Context, keepFileId: Int) {
        val settings = SettingsStore(context)
        val cached = runCatching { settings.cachedVideosNow() }.getOrDefault(emptyList())
        if (cached.isEmpty()) return
        val busy = OfflineDownloads.active.value.keys
        for (record in cached) {
            if (record.fileId == keepFileId || record.fileId in busy) continue
            if (record.fileId in inFlight) continue
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
         * in particular where it does not, so many are called things like "75". A row headed "75"
         * reads as a name the viewer ought to recognise; "Cached video" says plainly that there is
         * nothing to recognise. The file name goes on the line below either way.
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
     * Found by walking TDLib's own files directory rather than by asking TDLib, because TDLib
     * reports the total and the breakdown but not the individual files, and a total on its own is
     * something the viewer can do nothing with.
     *
     * @param knownPaths the local paths of everything already on the Downloads screen, which are
     *   asked of TDLib by the caller: only it knows which records are worth resolving.
     */
    fun strays(context: Context, knownPaths: Set<String>): List<Stray> =
        straysIn(File(context.filesDir, "tdlib-files"), knownPaths)

    /** The same, against a directory rather than a device, so the rule can be tested. */
    internal fun straysIn(root: File, knownPaths: Set<String>): List<Stray> {
        if (!root.isDirectory) return emptyList()
        val known = knownPaths.map(::canonical).toSet()
        return MEDIA_DIRECTORIES
            .map { File(root, it) }
            .filter { it.isDirectory }
            .flatMap { directory -> directory.walkTopDown().maxDepth(3).toList() }
            .filter { it.isFile && it.length() > 0 && canonical(it.absolutePath) !in known }
            .map { Stray(it.absolutePath, it.length(), it.lastModified()) }
            .sortedByDescending { it.bytes }
    }

    /**
     * One spelling for one file, so two names for the same path cannot disagree.
     *
     * An app's data directory is reachable as both `/data/data/<package>` and
     * `/data/user/0/<package>`, and the two are the same bytes through different names. TDLib
     * answers with one, `filesDir` walks the other, and comparing the strings makes every file the
     * app owns look like a video nobody owns. That is a download counted as cache in both storage
     * panels, and, before the sweep learned to refuse a partial answer, a download deleted.
     */
    private fun canonical(path: String): String =
        runCatching { File(path).canonicalPath }.getOrDefault(path)

    /** Removes one stray from the disk. TDLib refetches it if the video is ever played again. */
    fun forget(stray: Stray): Boolean = runCatching { File(stray.path).delete() }.getOrDefault(false)

    /**
     * Empties the cache: every video that is not a download, and every stray beside them.
     *
     * The one implementation of "clear the cached videos", used by both Settings rows that mean it,
     * so that neither has to repeat it or reach for something coarser that takes downloads too.
     *
     * Downloads are skipped, and so is anything the queue is fetching this moment: its bytes are on
     * the disk with no record yet, and deleting them puts a running download back to zero.
     *
     * @return how many bytes went.
     */
    suspend fun clearAll(context: Context): Long = withContext(Dispatchers.IO) {
        val settings = SettingsStore(context)
        val kept = runCatching { settings.downloadHistory.first() }.getOrDefault(emptyList())
        val keptIds = kept.map { it.fileId }.toSet()
        // On the message too, because one video can hold two file ids. See [StorageSplit.measure].
        val keptMessages = kept.map { it.chatId to it.messageId }.toSet()
        val busy = OfflineDownloads.active.value.keys
        val doomed = runCatching { settings.cachedVideosNow() }.getOrDefault(emptyList())
        var freed = 0L
        for (record in doomed) {
            if (record.fileId in keptIds || record.fileId in busy) continue
            if ((record.chatId to record.messageId) in keptMessages) continue
            val held = runCatching { Td.localDownloadedBytes(record.fileId) }.getOrDefault(0L)
            runCatching { Td.deleteFile(record.fileId) }
            // Only forgotten once the file has actually gone, or the record stops pointing at
            // bytes that are still on the disk.
            val left = runCatching { Td.localDownloadedBytes(record.fileId) }.getOrDefault(0L)
            if (left <= 0) {
                freed += held
                runCatching { settings.forgetCachedVideo(record.chatId, record.messageId) }
            }
        }
        freed + sweep(context)
    }

    /**
     * Takes the videos nothing owns, without being asked.
     *
     * Runs where the ceiling trim runs, on launch and on the way out of a video, so a device does
     * not need the viewer to know about Settings and press Clear to get its space back.
     *
     * Only strays. A record the app can name is the cache's or the viewer's, and both of those are
     * decided by [claim]; this is for the bytes that have no record at all. Anything a download is
     * fetching this moment is accounted for and left alone, or the queue would find its own
     * part-loaded file deleted underneath it.
     *
     * @return how many bytes went, which is worth logging and nothing else.
     */
    suspend fun sweep(context: Context): Long = withContext(Dispatchers.IO) {
        val settings = SettingsStore(context)
        val records = runCatching { settings.downloadHistory.first() }.getOrDefault(emptyList()) +
            runCatching { settings.cachedVideosNow() }.getOrDefault(emptyList())
        // Resolved against this session. A saved id that no longer resolves would leave the video
        // it names unaccounted for, and an unaccounted video is what this deletes.
        val known = buildList {
            for (record in records) {
                add(
                    runCatching { Td.currentFileId(record.chatId, record.messageId, record.fileId) }
                        .getOrDefault(record.fileId),
                )
            }
            addAll(OfflineDownloads.active.value.keys)
        }.distinct()

        val accounted = known.mapNotNull { runCatching { Td.localPathAnyway(it) }.getOrNull() }

        // Every known file has to answer with a path before anything is deleted. A stray is
        // defined by absence, so an id TDLib will not resolve is indistinguishable from a video
        // nobody owns: sweeping on a partial answer would delete the viewer's own downloads and
        // leave their rows pointing at nothing. A sweep skipped costs a little disk until the
        // next one; a sweep guessed costs a video that cannot be got back without downloading it
        // again. There is no hurry here, so it waits.
        if (accounted.size != known.size) return@withContext 0L

        var freed = 0L
        for (stray in strays(context, accounted.toSet())) if (forget(stray)) freed += stray.bytes
        freed
    }

    /**
     * The directories a video can be in.
     *
     * Photos, thumbnails, profile photos and stickers are deliberately absent: they are small,
     * deleting them makes the browse grid grey for no gain, and the Settings screen has its own
     * button for the viewer who wants that space anyway. `temp` is here because a watch abandoned
     * part way leaves its bytes there, and half a video is still half a gigabyte.
     */
    private val MEDIA_DIRECTORIES = listOf(
        "videos",
        "documents",
        "animations",
        "video_notes",
        "temp",
    )
}
