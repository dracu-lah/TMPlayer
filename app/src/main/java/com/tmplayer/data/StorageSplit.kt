package com.tmplayer.data

import android.content.Context
import kotlinx.coroutines.flow.first

/**
 * What TMPlayer is holding, split three ways, worked out in one place for both screens that show it.
 *
 * Settings and Downloads each used to do their own arithmetic, and they disagreed. Settings split
 * the total by TDLib's file types, so a video was a video whoever had asked for it; Downloads split
 * it into what the viewer had chosen to keep and one cached film. Two panels, two different stories
 * about the same disk, and neither of them adding up to the figure Android reports for the app.
 *
 * One split, and it is by ownership, because that is the question somebody looking at these panels
 * is actually asking: how much of this did I ask for, how much of it merely happened, and how much
 * is neither. [otherBytes] is the remainder rather than a measurement, so the three always add up
 * to [totalBytes] exactly.
 */
data class StorageSplit(
    /** Videos the viewer ticked, which nothing removes but the viewer. */
    val downloadBytes: Long,
    /** Videos left behind by playing something, including any an older version failed to clear. */
    val cachedBytes: Long,
    /** How many of those there are, so a row can say "4 videos" rather than name one of them. */
    val cachedCount: Int,
    /** Thumbnails, previews, the database: everything that is not a film. */
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
        suspend fun measure(context: Context): StorageSplit {
            val settings = SettingsStore(context)
            val total = runCatching { Td.storageUsedBytes() }.getOrDefault(0L)
            val kept = runCatching { settings.downloadHistory.first() }.getOrDefault(emptyList())
                .distinctBy { it.fileId }
            val keptIds = kept.map { it.fileId }.toSet()
            val downloads = kept.sumOf {
                runCatching { Td.localDownloadedBytes(it.fileId) }.getOrDefault(0L)
                    .coerceAtLeast(0L)
            }

            // A video can be both, if it was watched and then downloaded. It belongs to the viewer
            // then, and counting it in both halves would have the two figures overrun the total.
            val cachedRecords = runCatching { settings.cachedVideosNow() }.getOrDefault(emptyList())
                .filter { it.fileId !in keptIds }
                .distinctBy { it.fileId }
            var cached = 0L
            var count = 0
            for (record in cachedRecords) {
                val held = runCatching { Td.localDownloadedBytes(record.fileId) }.getOrDefault(0L)
                if (held <= 0) continue
                cached += held
                count++
            }

            // And the films on the disk that no record names: what a series watched end to end
            // left behind before the cache kept a list. Without these the panels quote a couple of
            // hundred megabytes on a device that Android reports as weighing two gigabytes.
            val accounted = (cachedRecords.map { it.fileId } + keptIds)
                .distinct()
                .mapNotNull { runCatching { Td.localPathAnyway(it) }.getOrNull() }
                .toSet()
            val strays = runCatching { WatchCache.strays(context, accounted) }
                .getOrDefault(emptyList())
            cached += strays.sumOf { it.bytes }
            count += strays.size

            return StorageSplit(
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
