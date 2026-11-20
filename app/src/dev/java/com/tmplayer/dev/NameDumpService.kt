package com.tmplayer.dev

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat

/**
 * Debug and preview builds only: holds the names dump ([NameDumpReceiver]) in the foreground, so
 * Android does not freeze the process while it pages the chats with the app out of sight or the
 * screen off. Stops itself when the dump is over. Not exported: only the receiver starts it.
 */
class NameDumpService : Service() {

    /** Whether a dump started by this service is running; cleared from the dump's own thread. */
    @Volatile
    private var holding = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // profile-notifications: the dev build's name dump, no profile's titles in it
        val inFront = runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type()) }
        inFront.exceptionOrNull()?.let {
            Log.w(NameDumpReceiver.TAG, "could not go to the foreground (${it.javaClass.simpleName}); keep the app open")
        }
        // A second start while a dump runs is ignored there: the service stays until the dump it
        // holds ends, or stops now when the running one is not its own (started without it).
        val started = NameDumpReceiver.start(applicationContext, intent) {
            holding = false
            stop()
        }
        if (started) holding = true else if (!holding) stop()
        return START_NOT_STICKY
    }

    private fun stop() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun notification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(NotificationChannel(CHANNEL, "Names dump", NotificationManager.IMPORTANCE_LOW))
            // profile-notifications: the dev build's name dump, no profile's titles in it
            Notification.Builder(this, CHANNEL)
        } else {
            // profile-notifications: as above
            Notification.Builder(this)
        }
        // profile-notifications: a fixed title, nothing of a profile
        return builder.setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Dumping video names").setOngoing(true).build()
    }

    private fun type(): Int = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0

    private companion object {
        const val CHANNEL = "tmdump"
        const val NOTIFICATION_ID = 0x7d0d
    }
}
