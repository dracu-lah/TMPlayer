package com.tmplayer.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * "Remove after watching": a download is deleted once its video is marked watched, whether the
 * viewer pressed "Mark as watched" or the player saw the end.
 *
 * Downloads only. This works from the download index and nothing else, so a video that is merely
 * in the watch cache has nothing here to match and is never touched: the cache already makes way
 * for the next video on its own, and deleting it here would only make the next replay slower.
 *
 * Only marks made while this is running count. The first list it sees is taken as the starting
 * point, and a mark made while the setting is off is passed over for good, so switching the setting
 * on never sweeps away every download that was watched last month. Taking a mark off and putting it
 * back counts as a new mark.
 *
 * Each app runs one of these for the life of its process, beside the player that writes the marks.
 *
 * @param downloads the download index, read fresh each time something is marked.
 * @param busy whether a record's video is still being fetched or moved, which is left alone.
 * @param delete deletes one download's file and record; false when the file would not go, and then
 *   the same mark is tried again the next time the list changes.
 */
class RemoveAfterWatching(
    private val enabled: Flow<Boolean>,
    private val watched: Flow<Map<String, WatchedRecord>>,
    private val downloads: suspend () -> List<ResumeRecord>,
    private val busy: (ResumeRecord) -> Boolean = ::isFetching,
    /** The open profile's turn (HX88): a change of it is never a change of what was watched. */
    private val profile: () -> AccountStamp = { Accounts.stamp },
    private val delete: suspend (ResumeRecord) -> Boolean,
) {

    /** Watches until cancelled. */
    suspend fun run() {
        var seen: Set<String>? = null
        var seenFor: AccountStamp? = null
        combine(enabled, watched) { on, marks -> on to marks.keys }.collect { (on, keys) ->
            val before = seen
            // Another profile's list (HX88), or the last one's arriving after a switch: a new
            // starting point, nothing deleted. What the next profile watched is not newly watched.
            // The stamp moves before the stores' views do, so no list of the next profile is ever
            // compared with the last one's.
            val stamp = profile()
            if (before == null || stamp != seenFor) {
                seen = keys
                seenFor = stamp
                return@collect
            }
            val fresh = keys - before
            // Kept to what is still marked, so an unmark followed by a mark counts again.
            var next = before.intersect(keys) + fresh
            if (on && fresh.isNotEmpty()) {
                val retry = mutableSetOf<String>()
                // As the profile whose marks these are, and only while it is open: a switch part way
                // stops the deletes, and the store writes of one under way are that profile's.
                kotlinx.coroutines.withContext(AccountOwner(stamp)) {
                    for (record in due(fresh, runCatching { downloads() }.getOrDefault(emptyList()))) {
                        if (profile() != stamp) break
                        val gone = !busy(record) && runCatching { delete(record) }.getOrDefault(false)
                        if (!gone) retry += record.key
                    }
                }
                next = next - retry
            }
            seen = next
        }
    }

    companion object {
        /** The downloads among [records] whose video is in [newlyWatched], keyed as the marks are. */
        fun due(newlyWatched: Set<String>, records: List<ResumeRecord>): List<ResumeRecord> =
            records.filter { it.key in newlyWatched }

        private val ResumeRecord.key: String get() = SettingsStore.progressKey(chatId, messageId)

        /** Whether the download queue still holds this video, being fetched or moved into place. */
        fun isFetching(record: ResumeRecord): Boolean = OfflineDownloads.active.value.values.any {
            it.request.chatId == record.chatId && it.request.messageId == record.messageId
        }
    }
}
