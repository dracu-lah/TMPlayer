package com.tmplayer.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * What TMPlayer is holding, split three ways, worked out in one place for both screens that show it.
 *
 * One split, and it is by ownership, because that is the question somebody looking at these panels
 * is actually asking: how much of this did I ask for, how much of it merely happened, and how much
 * is neither. [otherBytes] is the remainder rather than a measurement, so the three always add up
 * to [totalBytes] exactly.
 */
data class StorageSplit(
    /** Videos the viewer ticked, which nothing removes but the viewer. */
    val downloadBytes: Long,
    /** Videos left behind by playing something, strays on the disk included. */
    val cachedBytes: Long,
    /** How many of those there are, so a row can say "4 videos" rather than name one of them. */
    val cachedCount: Int,
    /** Thumbnails, previews, the database: everything that is not a video. */
    val otherBytes: Long,
    /** What TDLib says the whole of it comes to, which is the figure the device agrees with. */
    val totalBytes: Long,
) {
    companion object {
        val EMPTY = StorageSplit(0, 0, 0, 0, 0)

        /**
         * Measures the disk. Slow enough to want a background thread: a TDLib round trip per
         * record, plus a walk of the files directory.
         */
        suspend fun measure(context: Context): StorageSplit = withContext(Dispatchers.IO) {
            val settings = SettingsStore(context)
            val total = runCatching { Td.storageUsedBytes() }.getOrDefault(0L)
            val kept = runCatching { settings.downloadHistory.first() }.getOrDefault(emptyList())
                .distinctBy { it.fileId }
            val keptIds = kept.map { it.fileId }.toSet()
            // Matched on the message as well as the file. TDLib does not promise the same file id
            // for the same video twice: watching it and then downloading it can produce two ids
            // for one file on disk, so matching only on the id counts the download a second time
            // as cache. Eviction matches on the message, so this must too.
            val keptMessages = kept.map { it.chatId to it.messageId }.toSet()

            // A video can be both, if it was watched and then downloaded. It belongs to the viewer
            // then, and counting it in both halves would have the figures overrun the total.
            val cachedRecords = runCatching { settings.cachedVideosNow() }.getOrDefault(emptyList())
                .filter { it.fileId !in keptIds && (it.chatId to it.messageId) !in keptMessages }
                .distinctBy { it.fileId }

            // Every stored id resolved against this session before anything is measured from it,
            // and all of them at once rather than one after another.
            val keptNow = kept
                .map { r -> async { r to Td.currentFileId(r.chatId, r.messageId, r.fileId) } }
                .awaitAll()
            val cachedNow = cachedRecords
                .map { r -> async { r to Td.currentFileId(r.chatId, r.messageId, r.fileId) } }
                .awaitAll()

            val keptBytes = keptNow.map { (_, id) ->
                async { runCatching { Td.localDownloadedBytes(id) }.getOrDefault(0L) }
            }
            val cachedBytesEach = cachedNow.map { (_, id) ->
                async { runCatching { Td.localDownloadedBytes(id) }.getOrDefault(0L) }
            }
            val downloads = keptBytes.awaitAll().sumOf { it.coerceAtLeast(0L) }
            var cached = 0L
            var count = 0
            for (held in cachedBytesEach.awaitAll()) {
                if (held <= 0) continue
                cached += held
                count++
            }

            // And the videos on the disk that no record names, or the panels quote a couple of
            // hundred megabytes on a device Android reports as weighing two gigabytes.
            //
            // Anything the queue is fetching this moment counts as accounted for: a download is
            // only written to the history once it finishes, so without this a half-arrived video
            // is filed under "cached videos" and Clear offers to free the download being waited on.
            val knownIds = (
                cachedNow.map { it.second } + keptNow.map { it.second } +
                    OfflineDownloads.active.value.keys
                ).distinct()
            val accounted = knownIds
                .map { id -> async { runCatching { Td.localPathAnyway(id) }.getOrNull() } }
                .awaitAll()
                .filterNotNull()
            // Only when every known file answered with a path. An id TDLib will not resolve looks
            // exactly like a video nobody owns, so a partial answer would file the viewer's own
            // download under "cached" and offer to clear it.
            val strays = if (accounted.size != knownIds.size) {
                emptyList()
            } else {
                runCatching { WatchCache.strays(context, accounted.toSet()) }
                    .getOrDefault(emptyList())
            }
            cached += strays.sumOf { it.bytes }
            count += strays.size

            StorageSplit(
                downloadBytes = downloads,
                cachedBytes = cached,
                cachedCount = count,
                // The remainder, floored: TDLib's total and a walk of the disk are taken a moment
                // apart, and a negative band is worse than a missing one.
                otherBytes = (total - downloads - cached).coerceAtLeast(0L),
                totalBytes = maxOf(total, downloads + cached),
            )
        }
    }
}
