package com.tmplayer.data

import android.content.Context
import android.content.Intent

// The Android half of downloads: how a [DownloadRequest] crosses an intent to reach
// [DownloadService], and the service as the [DownloadRunner] behind [OfflineDownloads]. The queue,
// the request and its stored form live in `:core`.

fun DownloadRequest.intent(context: Context, action: String): Intent =
    Intent(context, DownloadService::class.java).apply {
        this.action = action
        putExtra(DownloadService.EXTRA_FILE_ID, fileId)
        putExtra(DownloadService.EXTRA_TITLE, title)
        putExtra(DownloadService.EXTRA_SIZE, sizeBytes)
        putExtra(DownloadService.EXTRA_CHAT_ID, chatId)
        putExtra(DownloadService.EXTRA_MESSAGE_ID, messageId)
        putExtra(DownloadService.EXTRA_CHAT_TITLE, chatTitle)
        putExtra(DownloadService.EXTRA_DURATION, durationSec)
        putExtra(DownloadService.EXTRA_MIME, mimeType)
        putExtra(DownloadService.EXTRA_FILE_NAME, fileName)
    }

fun DownloadRequest.Companion.from(intent: Intent): DownloadRequest? {
    val fileId = intent.getIntExtra(DownloadService.EXTRA_FILE_ID, 0)
    if (fileId <= 0) return null
    return DownloadRequest(
        fileId = fileId,
        title = intent.getStringExtra(DownloadService.EXTRA_TITLE).orEmpty(),
        sizeBytes = intent.getLongExtra(DownloadService.EXTRA_SIZE, 0),
        chatId = intent.getLongExtra(DownloadService.EXTRA_CHAT_ID, 0),
        messageId = intent.getLongExtra(DownloadService.EXTRA_MESSAGE_ID, 0),
        chatTitle = intent.getStringExtra(DownloadService.EXTRA_CHAT_TITLE).orEmpty(),
        durationSec = intent.getIntExtra(DownloadService.EXTRA_DURATION, 0),
        mimeType = intent.getStringExtra(DownloadService.EXTRA_MIME).orEmpty(),
        fileName = intent.getStringExtra(DownloadService.EXTRA_FILE_NAME).orEmpty(),
    )
}

/**
 * [DownloadService] as the [DownloadRunner].
 *
 * Every action goes through `startForegroundService`: pausing the only running download leaves
 * the service alive holding paused rows, and a plain `startService` onto a process Android had
 * already stopped throws rather than starting it. From Android 12 a foreground service may not be
 * started from the background at all, and that refusal is thrown on to [OfflineDownloads], which
 * turns it into a row that says what happened.
 */
class AndroidDownloadRunner(private val context: Context) : DownloadRunner {

    override fun download(request: DownloadRequest) {
        // A foreground service, so it keeps going with the app closed. Android requires the
        // notification within a few seconds of this call, which the service posts first thing.
        context.startForegroundService(request.intent(context, DownloadService.ACTION_DOWNLOAD))
    }

    override fun cancel(fileId: Int) = send(DownloadService.ACTION_CANCEL, fileId)

    override fun pause(fileId: Int) = send(DownloadService.ACTION_PAUSE, fileId)

    private fun send(action: String, fileId: Int) {
        val intent = Intent(context, DownloadService::class.java).apply {
            this.action = action
            putExtra(DownloadService.EXTRA_FILE_ID, fileId)
        }
        context.startForegroundService(intent)
    }
}

// The calls the screens have always made, with a context, over the shared queue in `:core`.

fun OfflineDownloads.start(context: Context, item: MediaItem, chatTitle: String) =
    start(AndroidDownloadRunner(context), item, chatTitle)

fun OfflineDownloads.cancel(context: Context, fileId: Int) =
    cancel(AndroidDownloadRunner(context), fileId)

fun OfflineDownloads.pause(context: Context, fileId: Int) =
    pause(AndroidDownloadRunner(context), fileId)

fun OfflineDownloads.resume(context: Context, fileId: Int) =
    resume(AndroidDownloadRunner(context), fileId)

fun OfflineDownloads.pauseAll(context: Context) = pauseAll(AndroidDownloadRunner(context))

fun OfflineDownloads.resumeAll(context: Context) = resumeAll(AndroidDownloadRunner(context))

suspend fun OfflineDownloads.restore(context: Context) = restore(SettingsStore(context))
