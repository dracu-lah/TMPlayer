package com.tmplayer.data

/**
 * The profile each queued download was asked under (HX88, review LOW 2): taken when the request
 * is queued, not when the queue reaches it, so a request asked before a switch never starts in the
 * next profile, whose database knows nothing of its file id. Not thread safe: the runner calls it
 * under its own lock. Android's DownloadService uses it; the desktop's runner can too.
 */
class DownloadStamps(
    private val now: () -> AccountStamp = { Accounts.stamp },
    private val rowsNow: () -> Long = { OfflineDownloads.rowsGeneration },
) {
    private val stamps = HashMap<Int, Pair<AccountStamp, Long>>()

    /** [id] is queued now, by the profile open now, in the rows of its database now. */
    fun queued(id: Int) {
        stamps[id] = now() to rowsNow()
    }

    private fun entry(id: Int): Pair<AccountStamp, Long> = stamps.getOrPut(id) { now() to rowsNow() }

    /** The stamp [id] was queued under, recorded now for a row that has none (one put back on the queue). */
    fun of(id: Int): AccountStamp = entry(id).first

    /** The set of rows ([OfflineDownloads.rowsGeneration]) [id] was queued in. */
    fun rowsOf(id: Int): Long = entry(id).second

    /**
     * Whether [id] may start: it was queued by the profile open now, on the database open now (a
     * sign out or an ended session of the same profile drops the rows, and with them the ids).
     */
    fun startable(id: Int): Boolean = entry(id) == (now() to rowsNow())

    fun forget(id: Int) {
        stamps.remove(id)
    }

    fun clear() {
        stamps.clear()
    }
}
