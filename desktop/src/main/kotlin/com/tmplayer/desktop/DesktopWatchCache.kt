package com.tmplayer.desktop

import com.tmplayer.data.MediaItem
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.Td
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** The few TDLib file calls the watch cache needs, behind an interface so the rules can be tested. */
interface TdFiles {
    suspend fun currentFileId(chatId: Long, messageId: Long, storedFileId: Int): Int
    suspend fun deleteFile(fileId: Int)
    suspend fun localDownloadedBytes(fileId: Int): Long
    suspend fun localPathAnyway(fileId: Int): String?

    /** The real thing, through [Td]. */
    object Live : TdFiles {
        override suspend fun currentFileId(chatId: Long, messageId: Long, storedFileId: Int) =
            Td.currentFileId(chatId, messageId, storedFileId)
        override suspend fun deleteFile(fileId: Int) = Td.deleteFile(fileId)
        override suspend fun localDownloadedBytes(fileId: Int) = Td.localDownloadedBytes(fileId)
        override suspend fun localPathAnyway(fileId: Int) = Td.localPathAnyway(fileId)
    }
}

/**
 * The desktop's copy of the phone's `WatchCache` rules: what streaming left on the disk, who
 * claims it and who gives it up.
 *
 * A download is a video the viewer asked to keep. A cached video is one that landed only because
 * somebody pressed Play, and there is one of it: the next video played replaces it. [claim] runs
 * from the player for every video it opens, episodes it moved on to by itself included, so a
 * binge never leaves a series behind. [sweep] takes the bytes nothing has a record of, at launch.
 *
 * Three kinds of file always survive: the video being played, anything the viewer downloaded, and
 * anything the download queue is fetching this moment.
 *
 * @param busy the file ids the download queue holds; [OfflineDownloads] in the app.
 * @param playing the file ids a player has open right now.
 */
class DesktopWatchCache(
    private val settings: SettingsStore,
    private val filesRoot: File,
    private val td: TdFiles = TdFiles.Live,
    private val busy: () -> Set<Int> = { OfflineDownloads.active.value.keys },
    private val playing: () -> Set<Int> = { emptySet() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Two claims overlap when the player steps to the next episode; they queue. */
    private val claiming = Mutex()

    /** Claims that have not finished, spared by [evictAllBut] (the phone's reasoning, unchanged). */
    private val inFlight = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

    /**
     * Marks [item] (playing as [fileId], which may differ from the id the item was listed with)
     * as the one cached video, and deletes the cached videos before it. Idempotent.
     */
    suspend fun claim(item: MediaItem, chatTitle: String, fileId: Int = item.fileId) = claiming.withLock {
        inFlight += fileId
        try {
            val kept = runCatching { settings.isKeptDownload(item.chatId, item.messageId) }.getOrDefault(false)
            // A download is not cache, and recording it as cache would get it deleted next time.
            if (!kept) runCatching { settings.rememberCachedVideo(item.copy(fileId = fileId), chatTitle) }
            evictAllBut(fileId)
        } finally {
            inFlight -= fileId
        }
    }

    /**
     * Deletes every cached video but [keepFileId], and forgets a record only once its bytes went:
     * TDLib can refuse a delete, and a record dropped for a file still there loses it for good.
     */
    suspend fun evictAllBut(keepFileId: Int) {
        val cached = runCatching { settings.cachedVideosNow() }.getOrDefault(emptyList())
        if (cached.isEmpty()) return
        val busyNow = busy()
        val playingNow = playing()
        // By file id too: a video forwarded into two chats is one TDLib file.
        val keptIds = runCatching { settings.downloadHistory.first() }.getOrDefault(emptyList()).map { it.fileId }.toSet()
        for (record in cached) {
            // Saved ids die with the TDLib session; the message is the durable identity.
            val fileId = runCatching { td.currentFileId(record.chatId, record.messageId, record.fileId) }
                .getOrDefault(record.fileId)
            if (fileId == keepFileId || record.fileId == keepFileId) continue
            if (fileId in busyNow || fileId in inFlight || fileId in playingNow) continue
            val downloaded = fileId in keptIds ||
                runCatching { settings.isKeptDownload(record.chatId, record.messageId) }.getOrDefault(false)
            if (downloaded) {
                // Downloaded since: the viewer's now, so the record goes and the file stays.
                runCatching { settings.forgetCachedVideo(record.chatId, record.messageId) }
                continue
            }
            runCatching { td.deleteFile(fileId) }
            val left = runCatching { td.localDownloadedBytes(fileId) }.getOrDefault(0L)
            if (left <= 0) runCatching { settings.forgetCachedVideo(record.chatId, record.messageId) }
        }
    }

    /** One file on disk nothing in the app has a name for. */
    data class Stray(val path: String, val bytes: Long, val modifiedAt: Long)

    /** Video files under TDLib's directory that are neither a download nor a cached video we know. */
    fun strays(knownPaths: Set<String>): List<Stray> {
        if (!filesRoot.isDirectory) return emptyList()
        val known = knownPaths.map(::canonical).toSet()
        return MEDIA_DIRECTORIES
            .map { File(filesRoot, it) }
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().maxDepth(3).toList() }
            .filter { it.isFile && it.length() > 0 && canonical(it.absolutePath) !in known }
            .map { Stray(it.absolutePath, it.length(), it.lastModified()) }
            .sortedByDescending { it.bytes }
    }

    /**
     * Deletes the strays, and nothing else. Every known file must answer with a path first: a
     * stray is defined by absence, so sweeping on a partial answer would take the viewer's own
     * downloads. Nothing written in the last ten minutes counts, since a file being fetched is
     * touched with every chunk.
     *
     * @return how many bytes went.
     */
    suspend fun sweep(): Long = withContext(Dispatchers.IO) {
        val records = runCatching { settings.downloadHistory.first() }.getOrDefault(emptyList()) +
            runCatching { settings.cachedVideosNow() }.getOrDefault(emptyList())
        val known = buildList {
            for (record in records) {
                add(runCatching { td.currentFileId(record.chatId, record.messageId, record.fileId) }.getOrDefault(record.fileId))
            }
            addAll(busy())
        }.distinct()
        val accounted = known.mapNotNull { runCatching { td.localPathAnyway(it) }.getOrNull() }
        if (accounted.size != known.size) return@withContext 0L
        val open = playing().mapNotNull { runCatching { td.localPathAnyway(it) }.getOrNull() }
        val settled = now() - STRAY_MIN_AGE_MS
        var freed = 0L
        for (stray in strays((accounted + open).toSet())) {
            if (stray.modifiedAt > settled) continue
            if (runCatching { File(stray.path).delete() }.getOrDefault(false)) freed += stray.bytes
        }
        freed
    }

    /**
     * Empties the cache: every cached video that is not a download, and the strays beside them.
     * Downloads and whatever the queue is fetching are left alone.
     */
    suspend fun clearAll(): Long = withContext(Dispatchers.IO) {
        val kept = runCatching { settings.downloadHistory.first() }.getOrDefault(emptyList())
        val keptIds = kept.map { it.fileId }.toSet()
        val keptMessages = kept.map { it.chatId to it.messageId }.toSet()
        val busyNow = busy()
        val playingNow = playing()
        var freed = 0L
        for (record in runCatching { settings.cachedVideosNow() }.getOrDefault(emptyList())) {
            val fileId = runCatching { td.currentFileId(record.chatId, record.messageId, record.fileId) }
                .getOrDefault(record.fileId)
            if (fileId in keptIds || record.fileId in keptIds || fileId in busyNow || fileId in playingNow) continue
            if ((record.chatId to record.messageId) in keptMessages) continue
            val held = runCatching { td.localDownloadedBytes(fileId) }.getOrDefault(0L)
            runCatching { td.deleteFile(fileId) }
            val left = runCatching { td.localDownloadedBytes(fileId) }.getOrDefault(0L)
            if (left <= 0) {
                freed += held
                runCatching { settings.forgetCachedVideo(record.chatId, record.messageId) }
            }
        }
        freed + sweep()
    }

    private fun canonical(path: String): String = runCatching { File(path).canonicalPath }.getOrDefault(path)

    companion object {
        const val STRAY_MIN_AGE_MS = 10 * 60_000L

        /** Where TDLib puts videos; photos, thumbnails and stickers are small and left alone. */
        val MEDIA_DIRECTORIES = listOf("videos", "documents", "animations", "video_notes", "temp")
    }
}
