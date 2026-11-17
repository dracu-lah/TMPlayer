package com.tmplayer.data

/**
 * When cancelling a download asks first (user, 2026-11-17): a download that is being fetched or
 * moved into Downloads right now has bytes that Cancel deletes, so Cancel on it is confirmed. One
 * still waiting (queued, paused, off the network, failed) holds nothing worth a question and goes
 * at once, as it always did. The phone's Downloads page keeps its own Undo window instead of this
 * question (7.2), so a toast is never stacked on a dialog.
 *
 * The question can sit open for a while, so what is cancelled is read again when it is answered:
 * [liveRow] is null once the download finished, was cancelled elsewhere or failed away meanwhile,
 * and then there is nothing left to cancel (never a delete of the finished copy).
 */
object CancelDownloadConfirm {

    fun needed(row: OfflineDownloads.Progress): Boolean =
        row.stage == OfflineDownloads.Stage.Running || row.stage == OfflineDownloads.Stage.Moving

    /** The download [fileId] as it stands now, or null when it is no longer on the queue. */
    fun liveRow(fileId: Int, active: Map<Int, OfflineDownloads.Progress>): OfflineDownloads.Progress? = active[fileId]
}
