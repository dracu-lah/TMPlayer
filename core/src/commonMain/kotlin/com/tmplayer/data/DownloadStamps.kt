package com.tmplayer.data

/**
 * The profile each queued download was asked under (HX88, review LOW 2): taken when the request
 * is queued, not when the queue reaches it, so a request asked before a switch never starts in the
 * next profile, whose database knows nothing of its file id. Not thread safe: the runner calls it
 * under its own lock. Android's DownloadService uses it; the desktop's runner can too.
 */
class DownloadStamps(private val now: () -> AccountStamp = { Accounts.stamp }) {
    private val stamps = HashMap<Int, AccountStamp>()

    /** [id] is queued now, by the profile open now. */
    fun queued(id: Int) {
        stamps[id] = now()
    }

    /** The stamp [id] was queued under, recorded now for a row that has none (one put back on the queue). */
    fun of(id: Int): AccountStamp = stamps.getOrPut(id) { now() }

    /** Whether [id] may start: it was queued by the profile open now. */
    fun startable(id: Int): Boolean = of(id) == now()

    fun forget(id: Int) {
        stamps.remove(id)
    }

    fun clear() {
        stamps.clear()
    }
}
