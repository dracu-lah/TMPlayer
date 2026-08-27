package com.tmplayer.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Whether the next video will fit, asked in one place so that every way of starting one gets the
 * same answer.
 *
 * [CacheShelf] does the arithmetic and knows nothing about a device; this gathers the facts to feed
 * it and carries out what it decides. It must not live in the screen a video is started from: the
 * in-player next-episode button and the autoplay countdown both go through `playEpisode`, which
 * starts the player directly, and a video reached that way has to be refused with the same clear
 * message as one pressed from the grid rather than stalling part way through.
 *
 * One decision, every caller, and no way to reach a video without passing through it.
 */
object RoomOnDisk {

    /**
     * What to do about a video, and what it costs to do it.
     *
     * [evict] is deliberately separate from the decision: the caller has to look at [plan] first,
     * because two of its three cases are answered on screen rather than on the disk.
     */
    class Decision internal constructor(
        val plan: CacheShelf.Plan,
        private val owners: Map<Int, ResumeRecord>,
    ) {
        /**
         * Deletes what the plan named and forgets the records that went with it.
         *
         * A record is dropped only once its bytes have actually gone. TDLib can refuse a delete,
         * and a record removed for a file still on the disk loses track of those bytes for good.
         *
         * The plan's ids are the ones [decide] measured, already resolved against this session,
         * so they are the ids the delete can trust.
         */
        suspend fun evict(context: Context) {
            val fileIds = (plan as? CacheShelf.Plan.Evict)?.fileIds ?: return
            val settings = SettingsStore(context)
            for (fileId in fileIds.toSet()) {
                runCatching { Td.deleteFile(fileId) }
                val record = owners[fileId] ?: continue
                val left = runCatching { Td.localDownloadedBytes(fileId) }.getOrDefault(0L)
                if (left <= 0) runCatching { settings.forgetCachedVideo(record.chatId, record.messageId) }
            }
        }
    }

    /**
     * Measures the disk and asks [CacheShelf] what to do about [item].
     *
     * @param alreadyCached the whole video is already here, so nothing has to be fetched and
     *   nothing has to be given up for it.
     * @param partialBytes what an interrupted watch of this same video already left on the disk.
     *   Without it the shelf sees a full disk, frees some of it, and what it deletes is the half of
     *   the video the viewer came back to carry on from.
     */
    suspend fun decide(
        context: Context,
        item: MediaItem,
        alreadyCached: Boolean,
        partialBytes: Long,
    ): Decision = withContext(Dispatchers.IO) {
        val settings = SettingsStore(context)
        val free = DiskSpace.read(context).freeBytes

        // Every cached video, not just the most recent. A record whose file is no longer on the
        // disk holds nothing, so it is dropped rather than offered as room that never comes back.
        // Each is measured through the id the message answers with now, not the one saved with
        // the record: a saved id from an earlier session measures as zero, and the shelf would
        // plan against room that never comes back and refuse a video the disk has space for.
        val cachedRecords = runCatching { settings.cachedVideosNow() }.getOrDefault(emptyList())
        val owned = cachedRecords
            .map { record ->
                async {
                    val fileId = runCatching {
                        Td.currentFileId(record.chatId, record.messageId, record.fileId)
                    }.getOrDefault(record.fileId)
                    val bytes = runCatching { Td.localDownloadedBytes(fileId) }.getOrDefault(0L)
                    if (bytes <= 0) null else CacheShelf.Held(fileId, bytes, record.updatedAt) to record
                }
            }
            .awaitAll()
            .filterNotNull()
        val held = owned.map { it.first }

        // The viewer's own downloads, which are never given up to make room. Measured only so a
        // refusal can say where the space went and point at the screen that manages them.
        val keptBytes = runCatching {
            settings.downloadHistory.first()
                .distinctBy { it.fileId }
                .map { record ->
                    async {
                        val fileId = runCatching {
                            Td.currentFileId(record.chatId, record.messageId, record.fileId)
                        }.getOrDefault(record.fileId)
                        Td.localDownloadedBytes(fileId).coerceAtLeast(0L)
                    }
                }
                .awaitAll()
                .sum()
        }.getOrDefault(0L)

        val plan = CacheShelf.plan(
            targetFileId = item.fileId,
            targetSizeBytes = item.sizeBytes,
            targetPartialBytes = partialBytes,
            alreadyCached = alreadyCached,
            cached = held,
            keptBytes = keptBytes,
            freeBytes = free,
        )
        Decision(plan, owned.associate { it.first.fileId to it.second })
    }
}
