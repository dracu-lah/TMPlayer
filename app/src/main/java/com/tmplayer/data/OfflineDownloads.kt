package com.tmplayer.data

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A video the viewer asked to keep, fetched whether or not they are still looking at it.
 *
 * This is the difference between the cache and a download. Playing something already fills the
 * cache with it, but that copy belongs to the player: closing the video cancels what was still
 * coming, and the next video may evict it. A download asked for by name is for the train, and has
 * to survive the app being put away, so it is fetched by a foreground service with a notification
 * of its own rather than by whichever screen happened to start it.
 *
 * The state here is what the browse screens draw a tick or a ring from. It is deliberately only in
 * memory: the record that a file was downloaded lives in [SettingsStore.noteDownload], and a
 * download that a killed process left half-finished shows as a partial file the next tap resumes,
 * not as a job that claims to still be running.
 */
object OfflineDownloads {

    /** How far a file has come, for the one or two rows on screen that are waiting on it. */
    data class Progress(
        val fileId: Int,
        val title: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val failure: String? = null,
    ) {
        val fraction: Float?
            get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
    }

    private val _active = MutableStateFlow<Map<Int, Progress>>(emptyMap())

    /** Keyed by TDLib file id, which is what a media row already has to hand. */
    val active: StateFlow<Map<Int, Progress>> = _active.asStateFlow()

    /** Whether this file is being fetched by a download rather than by a player. */
    fun isDownloading(fileId: Int): Boolean = _active.value.containsKey(fileId)

    /**
     * Asks for a video to be kept.
     *
     * Safe to call twice: the service checks its own queue, so a second press on a row that is
     * already coming down does nothing rather than starting a second fetch of the same bytes.
     */
    fun start(context: Context, item: MediaItem, chatTitle: String) {
        val intent = Intent(context, DownloadService::class.java).apply {
            action = DownloadService.ACTION_DOWNLOAD
            putExtra(DownloadService.EXTRA_FILE_ID, item.fileId)
            putExtra(DownloadService.EXTRA_TITLE, item.title)
            putExtra(DownloadService.EXTRA_SIZE, item.sizeBytes)
            putExtra(DownloadService.EXTRA_CHAT_ID, item.chatId)
            putExtra(DownloadService.EXTRA_MESSAGE_ID, item.messageId)
            putExtra(DownloadService.EXTRA_CHAT_TITLE, chatTitle)
            putExtra(DownloadService.EXTRA_DURATION, item.durationSec)
            putExtra(DownloadService.EXTRA_MIME, item.mimeType)
            putExtra(DownloadService.EXTRA_FILE_NAME, item.fileName)
        }
        // A foreground service, so it keeps going with the app closed. Android requires the
        // notification within a few seconds of this call, which the service posts first thing.
        context.startForegroundService(intent)
    }

    /** Stops one download and keeps the bytes that already landed. */
    fun cancel(context: Context, fileId: Int) {
        val intent = Intent(context, DownloadService::class.java).apply {
            action = DownloadService.ACTION_CANCEL
            putExtra(DownloadService.EXTRA_FILE_ID, fileId)
        }
        context.startService(intent)
    }

    // ---- called by the service --------------------------------------------------------------

    internal fun note(progress: Progress) {
        _active.value = _active.value + (progress.fileId to progress)
    }

    internal fun forget(fileId: Int) {
        _active.value = _active.value - fileId
    }

    internal fun forgetAll() {
        _active.value = emptyMap()
    }
}
