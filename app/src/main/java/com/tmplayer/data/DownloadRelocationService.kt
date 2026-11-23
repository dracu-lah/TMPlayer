package com.tmplayer.data

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.tmplayer.R
import com.tmplayer.i18n.L
import com.tmplayer.platform.JobFailures
import com.tmplayer.player.StreamStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps a downloads-folder move ([DownloadRelocation], HX26) going with the app out of sight: the
 * notification shows how far it has come, with Cancel, and the service stops itself once nothing
 * is moving any more, whether the move finished, failed, or was cancelled.
 *
 * Started alongside [DownloadRelocation.start]; the move itself runs in its own background scope
 * regardless, so a system that refuses the foreground service (Android 15's data sync allowance
 * spent, say) still finishes the move, just without a notification to show for it meanwhile.
 */
class DownloadRelocationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + JobFailures.handler(TAG))

    /** The profile whose downloads are moving (HX88). */
    private val owner = com.tmplayer.data.Accounts.stamp

    init {
        // A switch stops the move with its own Cancel: the files are that profile's.
        com.tmplayer.data.ProfileJobs.stopOnSwitch(scope, owner) {
            DownloadRelocation.cancel()
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            DownloadRelocation.cancel()
            return START_NOT_STICKY
        }
        AndroidTransferNotifier.ensureChannel(this)
        runCatching {
            val first = notification(DownloadRelocation.moving.value)
            com.tmplayer.data.ProfileNotifications.foreground(this, NOTIFICATION_ID, first, com.tmplayer.data.ProfileNotifications.Kind.Ongoing, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, owner)
        }
        scope.launch {
            DownloadRelocation.moving.collect { moving ->
                if (!DownloadRelocation.running) {
                    stopSelf()
                    return@collect
                }
                runCatching { com.tmplayer.data.ProfileNotifications.post(this@DownloadRelocationService, NOTIFICATION_ID, notification(moving), com.tmplayer.data.ProfileNotifications.Kind.Ongoing, owner) }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Android 15 gives data sync services a shared 6 hour allowance and calls this when it is
     * spent; the service has to stop within seconds or the system ends the app with an error. The
     * move is stopped with its own Cancel (the files already in place stay where they are).
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        android.util.Log.w(TAG, "The system's time for this foreground service is up, stopping (type $fgsType)")
        DownloadRelocation.cancel()
        runCatching { stopForegroundRemoving() }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        // The system taking the service down mid move: the move itself runs in its own scope and
        // carries on (or is resumed next launch); only the notification goes.
    }

    private fun notification(moving: DownloadRelocation.Moving?): Notification {
        val cancelIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, DownloadRelocationService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val progress = moving?.let { if (it.totalBytes > 0) ((it.doneBytes.toFloat() / it.totalBytes) * 1000).toInt() else 0 }
        return notificationBuilder(this, AndroidTransferNotifier.CHANNEL_ID, OldAndroid.IMPORTANCE_LOW)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(L.storageMovingDownloads)
            .setContentText(moving?.let { "${StreamStats.formatBytes(it.doneBytes)} / ${StreamStats.formatBytes(it.totalBytes)}" })
            .setOngoing(true)
            .setProgress(1000, progress ?: 0, moving == null)
            .addAction(Notification.Action.Builder(null, L.commonCancel, cancelIntent).build())
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun notifier(): NotificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private companion object {
        const val ACTION_CANCEL = "com.tmplayer.downloadrelocation.CANCEL"
        const val NOTIFICATION_ID = 90_101
        const val TAG = "DownloadRelocationService"
    }
}
