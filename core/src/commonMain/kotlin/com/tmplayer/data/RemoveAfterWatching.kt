package com.tmplayer.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first

/**
 * Videos the viewer has just marked watched, or the player has just seen to the end, in this
 * process and for one profile ([owner], the profile the mark was written for). Raised by
 * [WatchedStore.markWatched] and [WatchedStore.markAllWatched] only: a sync import, a restore
 * from a backup, an undo putting marks back, a switch, a sign out and the app starting raise none.
 */
data class WatchedMark(val keys: Set<String>, val owner: AccountStamp)

object WatchedMarks {
    private val flow = MutableSharedFlow<WatchedMark>(extraBufferCapacity = 64)

    /** Each new mark, as it is made. Nothing is replayed to a late listener. */
    val events: SharedFlow<WatchedMark> = flow.asSharedFlow()

    internal fun marked(keys: Set<String>, owner: AccountStamp) {
        if (keys.isNotEmpty()) flow.tryEmit(WatchedMark(keys, owner))
    }
}

/**
 * "Remove after watching": a download is deleted once its video is marked watched, whether the
 * viewer pressed "Mark as watched" or the player saw the end.
 *
 * Downloads only. This works from the download index and nothing else, so a video that is merely
 * in the watch cache has nothing here to match and is never touched: the cache already makes way
 * for the next video on its own, and deleting it here would only make the next replay slower.
 *
 * Driven by marks as they are made ([WatchedMarks]), never by comparing two watched lists (HX88):
 * a profile switch, a sign out, a sync import marking 200 videos watched on another device, a
 * restore from a backup and the app starting delete nothing, since none of them is the viewer
 * finishing a video here. A mark made while the setting is off is passed over for good, so
 * switching the setting on never sweeps away every download that was watched last month. A mark
 * of a profile that is no longer open deletes nothing.
 *
 * Each app runs one of these for the life of its process, beside the player that writes the marks.
 *
 * @param watched kept for the callers that still pass the list; not read (marks come as events).
 * @param downloads the download index, read fresh each time something is marked.
 * @param busy whether a record's video is still being fetched or moved, which is left alone.
 * @param delete deletes one download's file and record; false when the file would not go, and then
 *   it is tried again with the next mark of the same profile.
 */
class RemoveAfterWatching(
    private val enabled: Flow<Boolean>,
    @Suppress("unused") private val watched: Flow<Map<String, WatchedRecord>>? = null,
    private val downloads: suspend () -> List<ResumeRecord>,
    private val busy: (ResumeRecord) -> Boolean = ::isFetching,
    /** The open profile's turn (HX88): a mark of another profile deletes nothing. */
    private val profile: () -> AccountStamp = { Accounts.stamp },
    private val marks: Flow<WatchedMark> = WatchedMarks.events,
    private val mayAct: (AccountStamp) -> Boolean = { owner -> OwnedSessions.mayAct(owner, current = owner) },
    private val delete: suspend (ResumeRecord) -> Boolean,
) {

    /** Watches until cancelled. */
    suspend fun run() {
        // A file that would not go, tried again with the next mark of the same profile.
        var retry: Set<String> = emptySet()
        var retryFor: AccountStamp? = null
        marks.collect { mark ->
            val on = runCatching { enabled.first() }.getOrDefault(false)
            val stamp = profile()
            if (!on || mark.owner != stamp) return@collect
            val keys = mark.keys + if (retryFor == stamp) retry else emptySet()
            val failed = mutableSetOf<String>()
            // As the profile whose marks these are, and only while it is open: a switch part way
            // stops the deletes, and the store writes of one under way are that profile's.
            kotlinx.coroutines.withContext(AccountOwner(stamp)) {
                for (record in due(keys, runCatching { downloads() }.getOrDefault(emptyList()))) {
                    // Nor while a switch is under way: its folders are already the next profile's.
                    if (profile() != stamp || !mayAct(stamp)) break
                    val gone = !busy(record) && runCatching { delete(record) }.getOrDefault(false)
                    if (!gone) failed += record.key
                }
            }
            retry = failed
            retryFor = stamp
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
