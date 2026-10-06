package com.tmplayer.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.tmplayer.MainActivity
import com.tmplayer.R
import com.tmplayer.i18n.L
import com.tmplayer.i18n.Translator
import com.tmplayer.platform.TransferNotifier
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * [TransferNotifier] on Android: the notification shade, in the same channel and with the same
 * look as the download service's own notifications.
 *
 * The service keeps drawing its per video notifications itself, since they carry its Pause and
 * Cancel buttons; this is what it posts a finished download with, and what the one off transfers
 * that have no service behind them (moving old downloads into the folder) report through. Every
 * completion is a new notification that opens the Downloads screen, never an update of the
 * progress one, so it is seen by a viewer who swiped the progress away.
 *
 * A television shows no shade to apps like this one, so each completion is also put on
 * [completions], which the app's window turns into a toast while it is in front.
 */
class AndroidTransferNotifier(context: Context) : TransferNotifier {

    private val app = context.applicationContext
    private val titles = java.util.concurrent.ConcurrentHashMap<Long, String>()
    private val kinds = java.util.concurrent.ConcurrentHashMap<Long, TransferNotifier.Kind>()

    override val capabilities: Set<TransferNotifier.Capability> = setOf(
        TransferNotifier.Capability.ProgressBar,
        TransferNotifier.Capability.InPlaceUpdate,
        TransferNotifier.Capability.Actions,
    )

    override fun begin(id: Long, kind: TransferNotifier.Kind, title: String) {
        titles[id] = title
        kinds[id] = kind
        post(id.progressId(), builder().setContentTitle(title).setOngoing(true).setProgress(0, 0, true).build())
    }

    override fun progress(id: Long, done: Long, total: Long?, bytesPerSecond: Long?) {
        val title = titles[id] ?: return
        val known = total != null && total > 0
        // A migration counts videos; everything else counts bytes.
        val line = when {
            kinds[id] == TransferNotifier.Kind.Migrate && known -> L.commonOfTotal(done = done.toString(), total = total.toString())
            known -> L.commonOfTotal(done = Translator.messages.formatter.bytes(done), total = Translator.messages.formatter.bytes(total!!))
            else -> Translator.messages.formatter.bytes(done)
        }
        post(
            id.progressId(),
            builder()
                .setContentTitle(title)
                .setContentText(line)
                .setOngoing(true)
                .setProgress(100, if (known) ((done * 100) / total!!).toInt() else 0, !known)
                .build(),
        )
    }

    override fun complete(id: Long, title: String, body: String, open: TransferNotifier.OpenTarget?) {
        drop(id)
        post(id.doneId(), builder().setContentTitle(title).setContentText(body).setAutoCancel(true).build())
        _completions.tryEmit(title)
    }

    override fun fail(id: Long, title: String, reason: String, retryable: Boolean) {
        drop(id)
        post(id.doneId(), builder().setContentTitle(title).setContentText(reason).setAutoCancel(true).build())
    }

    override fun cancel(id: Long) = drop(id)

    private fun drop(id: Long) {
        titles.remove(id)
        kinds.remove(id)
        runCatching { manager().cancel(id.progressId()) }
    }

    private fun post(id: Int, notification: Notification) {
        ensureChannel(app)
        // A refused permission is not a failure of the transfer: it simply goes unseen.
        runCatching { manager().notify(id, notification) }
    }

    private fun builder(): Notification.Builder =
        Notification.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentIntent(openDownloads(app))
            .setOnlyAlertOnce(true)

    private fun manager(): NotificationManager =
        app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** The id the progress is drawn under. Transfers are numbered by their callers, in range. */
    private fun Long.progressId(): Int = (this and 0x3FFFFFFF).toInt()

    /** A different id for the end, so the completion pops rather than quietly replacing the bar. */
    private fun Long.doneId(): Int = progressId() + DONE_OFFSET

    companion object {
        /** The download service's channel: one switch in Android's settings covers both. */
        const val CHANNEL_ID = "downloads"

        /** Clear of every id the service uses, which stay under 4200 + 65537. */
        private const val DONE_OFFSET = 0x100000

        private val _completions = MutableSharedFlow<String>(extraBufferCapacity = 8)

        /** The title of each completion, for the in app toast. Nothing is replayed to late arrivals. */
        val completions: SharedFlow<String> = _completions.asSharedFlow()

        /** Says a download finished, for the toast, when the shade is not where it is posted from. */
        fun announce(title: String) {
            _completions.tryEmit(title)
        }

        fun ensureChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                L.notifyChannelDownloads,
                // Low: a progress bar that pings and vibrates every time it moves is not information.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = L.notifyChannelDownloadsDescription
                setShowBadge(false)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        /** Where pressing any of these goes: the Downloads screen, which is what they are about. */
        fun openDownloads(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_DOWNLOADS)
            return PendingIntent.getActivity(
                context,
                OPEN_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /** The request code the download service has always used for the same intent. */
        private const val OPEN_REQUEST_CODE = 1
    }
}
