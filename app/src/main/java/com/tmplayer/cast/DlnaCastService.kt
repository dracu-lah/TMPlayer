package com.tmplayer.cast

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.tmplayer.R
import com.tmplayer.data.notificationBuilder
import com.tmplayer.data.stopForegroundRemoving
import com.tmplayer.i18n.L
import com.tmplayer.platform.JobFailures
import com.tmplayer.player.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps a DLNA cast alive with the screen off (HX40). The cast server lives in the player
 * activity, and Google's Cast framework gives that activity a foreground service of its own; DLNA
 * has none, so without this Android would freeze the cached process and the TV would starve.
 *
 * Started when a video has loaded on the TV and stopped when the cast ends, is lost or is closed
 * for sitting paused, so nothing is held while nothing is cast. It holds, for that time only:
 * - a foreground service of the media playback type with a notification (Play or Pause, Stop
 *   casting) and a media session, so the lock screen and a headset's buttons reach the TV;
 * - a Wi-Fi lock, and a partial wake lock capped at four hours.
 * The notification's buttons and the session forward to the one [CastSupport].
 *
 * Not exported. A system that refuses the foreground start leaves the cast alive for as long as
 * the screen is on, which is what [AndroidDlnaPlatform] logs.
 */
class DlnaCastService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + JobFailures.handler(TAG))
    private var session: MediaSession? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var title = ""
    private var device = ""
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val support = Cast.existing()
        when (intent?.action) {
            ACTION_TOGGLE -> {
                if (support != null) {
                    if (playing(support)) support.pause() else support.play()
                }
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                support?.endSession()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        device = intent?.getStringExtra(EXTRA_DEVICE).orEmpty()
        PlaybackService.ensureChannel(this)
        runCatching {
            val first = notification(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, first)
            }
        }.onFailure { Log.w(TAG, "Not allowed into the foreground", it) }
        holdLocks()
        ensureSession()
        if (!watching && support != null) {
            watching = true
            scope.launch {
                support.remote.map { it.media?.state to it.durationMs }.distinctUntilChanged().collect { publish(support) }
            }
        }
        return START_NOT_STICKY
    }

    private fun playing(support: CastSupport): Boolean = when (support.remote.value.media?.state) {
        ReceiverState.Playing, ReceiverState.Buffering, ReceiverState.Loading -> true
        else -> false
    }

    private fun publish(support: CastSupport) {
        val isPlaying = playing(support)
        val media = support.remote.value
        session?.let { s ->
            val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
            s.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO)
                    .setState(state, media.media?.positionMs ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (isPlaying) 1f else 0f)
                    .build(),
            )
            s.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, L.castCastingTo(device))
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, media.durationMs)
                    .build(),
            )
        }
        runCatching { getSystemService(NOTIFICATION_SERVICE).let { (it as android.app.NotificationManager).notify(NOTIFICATION_ID, notification(isPlaying)) } }
    }

    private fun ensureSession() {
        if (session != null) return
        session = runCatching {
            MediaSession(this, "TMPlayerDlna").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() { Cast.existing()?.play() }
                    override fun onPause() { Cast.existing()?.pause() }
                    override fun onSeekTo(pos: Long) { Cast.existing()?.seek(pos) }
                    override fun onStop() { Cast.existing()?.endSession() }
                })
                isActive = true
            }
        }.onFailure { Log.w(TAG, "No media session", it) }.getOrNull()
    }

    private fun notification(isPlaying: Boolean): Notification {
        val toggle = action(if (isPlaying) R.drawable.ic_notify_pause else R.drawable.ic_notify_resume, if (isPlaying) L.commonPause else L.commonPlay, ACTION_TOGGLE)
        val stop = action(R.drawable.ic_notify_cancel, L.castStop, ACTION_STOP)
        val tap = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1)
        session?.let { style.setMediaSession(it.sessionToken) }
        return notificationBuilder(this, PlaybackService.CHANNEL_ID, android.app.NotificationManager.IMPORTANCE_LOW)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle(title.ifBlank { device })
            .setContentText(L.castCastingTo(device))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(tap)
            .addAction(toggle)
            .addAction(stop)
            .setStyle(style)
            .build()
    }

    private fun action(icon: Int, label: String, action: String): Notification.Action {
        val pi = PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, DlnaCastService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi).build()
    }

    private fun holdLocks() {
        runCatching {
            if (wifiLock?.isHeld != true) {
                val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = wifi.createWifiLock(mode, "tmplayer-dlna").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        }.onFailure { Log.w(TAG, "No Wi-Fi lock", it) }
        runCatching {
            if (wakeLock?.isHeld != true) {
                val power = getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tmplayer:dlna").apply {
                    setReferenceCounted(false)
                    // A film is not longer than this; a forgotten cast does not hold the CPU for ever.
                    acquire(MAX_HOLD_MS)
                }
            }
        }.onFailure { Log.w(TAG, "No wake lock", it) }
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
        runCatching { session?.release() }
        session = null
        stopForegroundRemoving()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TMPlayerDlna"
        private const val NOTIFICATION_ID = 0x7A3
        private const val MAX_HOLD_MS = 4 * 60 * 60_000L
        private const val ACTION_TOGGLE = "com.tmplayer.cast.TOGGLE"
        private const val ACTION_STOP = "com.tmplayer.cast.STOP"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_DEVICE = "device"

        fun intent(context: Context, title: String, device: String): Intent =
            Intent(context, DlnaCastService::class.java).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_DEVICE, device)
    }
}
