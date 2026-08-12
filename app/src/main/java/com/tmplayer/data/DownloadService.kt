package com.tmplayer.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.tmplayer.MainActivity
import com.tmplayer.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Fetches videos the viewer asked to keep, with the app closed if need be.
 *
 * The app had no downloads in this sense at all: playing a video filled the cache with it and
 * leaving the player cancelled whatever had not arrived, which is right for a television that
 * watches one thing at a time and wrong for somebody filling a phone before a train. A foreground
 * service is what Android gives for work the viewer started and expects to finish while they are
 * somewhere else, and the notification it must post is the same thing they will look at to see how
 * far it has come.
 *
 * TDLib does the fetching; this only asks, watches and reports. The synchronous form of
 * `downloadFile` returns when the whole file has landed, so the progress loop beside it exists
 * purely to move the notification along.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** File id to the job fetching it, so a second request for the same video is ignored. */
    private val jobs = ConcurrentHashMap<Int, Job>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        channel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android gives a few seconds from startForegroundService to this call, whatever the
        // intent turns out to be, so the notification goes up before anything else is decided.
        goToForeground(summary())

        when (intent?.action) {
            ACTION_CANCEL -> {
                val fileId = intent.getIntExtra(EXTRA_FILE_ID, 0)
                jobs.remove(fileId)?.cancel()
                OfflineDownloads.forget(fileId)
                Td.cancelDownloadInBackground(fileId)
                notifier().cancel(fileId.notificationId())
                stopWhenIdle()
            }
            ACTION_DOWNLOAD -> {
                val request = Request.from(intent)
                if (request == null || jobs.containsKey(request.fileId)) {
                    stopWhenIdle()
                    return START_NOT_STICKY
                }
                jobs[request.fileId] = scope.launch { fetch(request) }
            }
            else -> stopWhenIdle()
        }
        // Not sticky: a process the system killed should not silently start fetching a gigabyte
        // again with nobody watching. The partial file is kept, and the row offers to resume.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        OfflineDownloads.forgetAll()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun fetch(request: Request) {
        val progress = OfflineDownloads.Progress(
            fileId = request.fileId,
            title = request.title,
            downloadedBytes = 0,
            totalBytes = request.sizeBytes,
        )
        OfflineDownloads.note(progress)
        notifier().notify(request.fileId.notificationId(), itemNotification(progress))

        val session = Td.awaitConnectedSession()
        // Watches the same file TDLib is filling, only so the notification and the row have
        // something to move. The download itself is the call below.
        val ticker = scope.launch {
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                val done = Td.localDownloadedBytes(request.fileId)
                val step = progress.copy(downloadedBytes = done)
                OfflineDownloads.note(step)
                notifier().notify(request.fileId.notificationId(), itemNotification(step))
                goToForeground(summary())
            }
        }

        val result = runCatching {
            session.client.downloadFile(
                fileId = request.fileId,
                priority = DOWNLOAD_PRIORITY,
                offset = 0,
                // Zero means the whole file, which is the entire point of this service.
                limit = 0,
                synchronous = true,
            )
        }
        ticker.cancel()

        val error = result.exceptionOrNull()?.message ?: result.getOrNull()?.errorMessage
        val complete = Td.localFileAvailability(request.fileId) == LocalFileAvailability.Complete

        if (error != null || !complete) {
            Log.w(TAG, "Download of ${request.title} did not finish: ${error ?: "incomplete"}")
            OfflineDownloads.note(
                progress.copy(failure = error?.let(Failures::humanise) ?: FAILED_TEXT),
            )
            notifier().notify(request.fileId.notificationId(), failedNotification(request))
        } else {
            // Only a finished file is written to the Downloads list. A half of a film is not
            // something to offer somebody on a train as though it were there.
            SettingsStore(applicationContext).noteDownload(request.item(), request.chatTitle)
            OfflineDownloads.forget(request.fileId)
            notifier().notify(request.fileId.notificationId(), doneNotification(request))
        }

        jobs.remove(request.fileId)
        stopWhenIdle()
    }

    private fun stopWhenIdle() {
        if (jobs.isNotEmpty()) return
        // The per-file notifications stay: "downloaded" and "did not finish" are both things the
        // viewer wants to find later. Only the one holding the service up goes.
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goToForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(SUMMARY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(SUMMARY_ID, notification)
        }
    }

    private fun summary(): Notification {
        val running = OfflineDownloads.active.value.values
        val text = when (running.size) {
            0 -> "Finishing up"
            1 -> running.first().title
            else -> "${running.size} videos"
        }
        return builder()
            .setContentTitle("Downloading for later")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun itemNotification(progress: OfflineDownloads.Progress): Notification {
        val fraction = progress.fraction
        return builder()
            .setContentTitle(progress.title)
            .setContentText(
                if (fraction == null) {
                    "Downloading"
                } else {
                    "${(fraction * 100).toInt()}%  ·  " +
                        "${bytes(progress.downloadedBytes)} of ${bytes(progress.totalBytes)}"
                },
            )
            .setOngoing(true)
            .setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
            .build()
    }

    private fun doneNotification(request: Request): Notification = builder()
        .setContentTitle(request.title)
        .setContentText("Downloaded. It plays without a connection.")
        .setAutoCancel(true)
        .build()

    private fun failedNotification(request: Request): Notification = builder()
        .setContentTitle(request.title)
        .setContentText(FAILED_TEXT)
        .setAutoCancel(true)
        .build()

    private fun builder(): Notification.Builder =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentIntent(openApp())
            .setOnlyAlertOnce(true)

    private fun openApp(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notifier(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun channel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Downloads",
            // Low: a progress bar that pings and vibrates every time it moves is not information.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Videos being kept for watching without a connection"
            setShowBadge(false)
        }
        notifier().createNotificationChannel(channel)
    }

    private fun bytes(value: Long): String = com.tmplayer.player.StreamStats.formatBytes(value)

    private fun Int.notificationId(): Int = SUMMARY_ID + 1 + (this and 0xFFFF)

    /** Everything the service needs about a video, carried across the intent boundary. */
    private data class Request(
        val fileId: Int,
        val title: String,
        val sizeBytes: Long,
        val chatId: Long,
        val messageId: Long,
        val chatTitle: String,
        val durationSec: Int,
        val mimeType: String,
        val fileName: String,
    ) {
        /** Enough of a [MediaItem] for the Downloads list, which reads five of its fields. */
        fun item(): MediaItem = MediaItem(
            chatId = chatId,
            messageId = messageId,
            fileId = fileId,
            title = title,
            sizeBytes = sizeBytes,
            durationSec = durationSec,
            mimeType = mimeType,
            thumbnailFileId = 0,
            miniThumbnail = null,
            date = 0,
            fileName = fileName,
        )

        companion object {
            fun from(intent: Intent): Request? {
                val fileId = intent.getIntExtra(EXTRA_FILE_ID, 0)
                if (fileId <= 0) return null
                return Request(
                    fileId = fileId,
                    title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                    sizeBytes = intent.getLongExtra(EXTRA_SIZE, 0),
                    chatId = intent.getLongExtra(EXTRA_CHAT_ID, 0),
                    messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, 0),
                    chatTitle = intent.getStringExtra(EXTRA_CHAT_TITLE).orEmpty(),
                    durationSec = intent.getIntExtra(EXTRA_DURATION, 0),
                    mimeType = intent.getStringExtra(EXTRA_MIME).orEmpty(),
                    fileName = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty(),
                )
            }
        }
    }

    companion object {
        const val ACTION_DOWNLOAD = "com.tmplayer.action.DOWNLOAD"
        const val ACTION_CANCEL = "com.tmplayer.action.CANCEL_DOWNLOAD"

        const val EXTRA_FILE_ID = "fileId"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SIZE = "size"
        const val EXTRA_CHAT_ID = "chatId"
        const val EXTRA_MESSAGE_ID = "messageId"
        const val EXTRA_CHAT_TITLE = "chatTitle"
        const val EXTRA_DURATION = "duration"
        const val EXTRA_MIME = "mime"
        const val EXTRA_FILE_NAME = "fileName"

        private const val TAG = "DownloadService"
        private const val CHANNEL_ID = "downloads"
        private const val SUMMARY_ID = 4200

        /** Above the streaming player's priority: this is what the viewer is waiting on. */
        private const val DOWNLOAD_PRIORITY = 32

        private const val PROGRESS_INTERVAL_MS = 1_000L

        private const val FAILED_TEXT = "The download did not finish. Try again."
    }
}
