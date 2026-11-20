package com.tmplayer.player

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import com.tmplayer.R
import com.tmplayer.cast.LoopbackStream
import com.tmplayer.data.Td
import com.tmplayer.data.notificationBuilder
import com.tmplayer.data.stopForegroundRemoving
import com.tmplayer.i18n.L
import java.io.File
import java.io.IOException

/**
 * The one video being served to another app on this device (PR4): a [LoopbackStream] on
 * 127.0.0.1 with its own token, fed from TDLib's window for a video that is still coming down or
 * from the disk for a finished one the app cannot take as a file.
 *
 * One at a time: a new video replaces the old, so a forgotten stream never holds the port or a
 * TDLib download. While it runs an [ExternalStreamService] keeps the process in the foreground,
 * or a low-memory TV would end TMPlayer with the other app still reading. It ends when the viewer
 * stops it from the notification, when TMPlayer is back in front and nothing has read for a while
 * ([ExternalPlay.streamShouldClose]), when it closes itself after [LoopbackStream.SESSION_IDLE_MS],
 * or when it fails for good.
 */
object ExternalStreams {

    private const val TAG = "ExternalStreams"

    @Volatile
    private var stream: LoopbackStream? = null

    @Volatile
    private var returnedAt = 0L

    @Volatile
    var title: String = ""
        private set

    /** The session while it is up, null after it closed itself or was closed. */
    fun current(): LoopbackStream? = stream?.takeIf { it.running }

    /**
     * Starts serving and returns the address to hand the other app. [downloaded] is a finished
     * file read directly; otherwise [fileId] is streamed from TDLib. Throws [IOException] when
     * there is nothing to serve (the caller shows the built-in player instead).
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    suspend fun open(
        context: Context,
        fileId: Int,
        sizeBytes: Long,
        mime: String,
        name: String,
        title: String,
        downloaded: File?,
    ): String {
        close(context)
        val streaming = if (downloaded == null && fileId > 0 && sizeBytes > 0) {
            val client = Td.awaitAuthorizedSession().client
            DataSourceCastSource(TdDataSource.Factory(client), tdFileUri(fileId), sizeBytes)
        } else {
            null
        }
        val session = LoopbackStream.start(
            streaming = streaming,
            downloaded = downloaded,
            mime = mime.ifBlank { "video/*" },
            name = name.ifBlank { "video" },
            config = LoopbackStream.Config(onFailure = { Log.w(TAG, "A read failed: ${it.message}") }),
        )
        stream = session
        returnedAt = 0L
        this.title = title
        runCatching { ContextCompat.startForegroundService(context.applicationContext, ExternalStreamService.intent(context)) }
            .onFailure { Log.w(TAG, "No foreground service; the stream lives while the app is open", it) }
        return session.url
    }

    /** TMPlayer is in front again: the stream may now be let go once nothing reads it. */
    fun markReturned(now: Long = android.os.SystemClock.elapsedRealtime()) {
        if (stream != null && returnedAt == 0L) returnedAt = now
    }

    /** Whether the stream was handed over and TMPlayer has come back since. */
    val hasReturned: Boolean get() = returnedAt != 0L

    /** Closes the stream when the rule says so; true when it did (or there was none). */
    fun closeIfDone(context: Context): Boolean {
        val s = stream ?: return true
        if (!s.running) {
            close(context)
            return true
        }
        if (ExternalPlay.streamShouldClose(returned = hasReturned, running = true, holdsWindow = s.holdsWindow)) {
            close(context)
            return true
        }
        return false
    }

    fun close(context: Context) {
        val s = stream
        stream = null
        returnedAt = 0L
        if (s != null) runCatching { s.close() }
        runCatching { context.applicationContext.stopService(Intent(context.applicationContext, ExternalStreamService::class.java)) }
    }
}

/**
 * Keeps the process in the foreground while [ExternalStreams] serves a video to another app, as
 * [com.tmplayer.cast.DlnaCastService] does for a cast: a media playback service with one
 * notification that says what is happening and has a Stop button. Stops itself when the stream is
 * gone, checked every few seconds, and sees to it that a stream nobody reads after TMPlayer came
 * back is closed. Not exported.
 */
class ExternalStreamService : Service() {

    /** The profile whose video streams (HX88); a switch closes the stream, its notification with it. */
    private val owner = com.tmplayer.data.Accounts.stamp
    private val switches = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    init {
        com.tmplayer.data.ProfileJobs.stopOnSwitch(switches, owner) {
            ExternalStreams.close(this)
            stopSelf()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val check = object : Runnable {
        override fun run() {
            if (ExternalStreams.closeIfDone(this@ExternalStreamService) || ExternalStreams.current() == null) {
                stopSelf()
            } else {
                handler.postDelayed(this, CHECK_MS)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ExternalStreams.close(this)
            stopSelf()
            return START_NOT_STICKY
        }
        PlaybackService.ensureChannel(this)
        val foreground = runCatching {
            val note = notification()
            com.tmplayer.data.ProfileNotifications.foreground(this, NOTIFICATION_ID, note, com.tmplayer.data.ProfileNotifications.Kind.Ongoing, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, owner)
        }.onFailure { Log.w(TAG, "Not allowed into the foreground", it) }.isSuccess
        if (!foreground) {
            // startForegroundService() promised a foreground within seconds; leaving instead of
            // idling keeps the platform from killing the app over the broken promise.
            stopSelf()
            return START_NOT_STICKY
        }
        handler.removeCallbacks(check)
        handler.postDelayed(check, CHECK_MS)
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(
            this,
            ACTION_STOP.hashCode(),
            Intent(this, ExternalStreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val tap = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return notificationBuilder(this, PlaybackService.CHANNEL_ID, android.app.NotificationManager.IMPORTANCE_LOW)
            .setSmallIcon(R.drawable.ic_notify_resume)
            .setContentTitle(ExternalStreams.title.ifBlank { L.externalStreamingTitle })
            .setContentText(L.externalStreaming)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(tap)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_notify_cancel), L.externalStop, stop).build())
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(check)
        switches.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        stopForegroundRemoving()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ExternalStreamService"
        private const val NOTIFICATION_ID = 0x7A4
        private const val CHECK_MS = 15_000L
        private const val ACTION_STOP = "com.tmplayer.player.EXTERNAL_STOP"

        fun intent(context: Context): Intent = Intent(context, ExternalStreamService::class.java)
    }
}
