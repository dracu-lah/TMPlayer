package com.tmplayer.player

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.tmplayer.R
import com.tmplayer.i18n.L

/**
 * "Keep playing audio with the screen off or in another app" (Settings, Playback; the phone only).
 *
 * The player, its media session and the TDLib stream all stay where they always were, in
 * [PlayerActivity]. When the viewer leaves the player with the setting on (Home, another app, the
 * power button) and picture in picture did not take over, the activity hands its existing session
 * to this service ([start]), which is what Android needs to let a backgrounded app keep sound
 * going: a foreground service of the media playback type, and Media3's media notification with
 * play, pause, the episode steps and, from Android 13, the seek bar (before it, seek back and
 * forward buttons: [BackgroundPlayback.notificationButtons]). Media3 drives the notification and
 * the foreground state from the session.
 *
 * The session outlives the players it carries: an episode that ends into the next, or a stream
 * that has to be opened again, puts a new player into the same session, so the service, its
 * notification and its foreground state carry on through the change ([PlayerActivity]'s
 * attachMediaSession). How long the foreground lasts once nothing plays follows what the player
 * means to do ([setPlayWhenReady]): a pause by the viewer lets go at once, so the paused
 * notification can be swiped away; a gap while the player still means to play keeps it.
 *
 * Coming back to the player takes the session back ([stop]); the activity was never gone. Swiping
 * the app away, or dismissing the paused notification (which Media3 sends as a stop to the
 * session's player), finishes the activity through [onStopRequested], and its onDestroy releases
 * the player and cancels the TDLib download as it always has.
 *
 * When Android will not let the service back into the foreground (a backgrounded app cannot
 * start one by itself, and the foreground lapsed during a long wait), the sound stops and
 * [notifyStopped] says so, with a tap that brings the player back to carry on.
 *
 * Not exported, and with no MediaSessionService intent filter: nothing outside the app binds it,
 * and the media button receiver never starts it with no video behind it.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var held: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        live = this
        ensureChannel(this)
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setNotificationId(NOTIFICATION_ID)
                .build()
                .apply { setSmallIcon(R.drawable.ic_notify_resume) },
        )
        setForegroundServiceTimeoutMs(BackgroundPlayback.foregroundGraceMs(meansToPlay))
        setListener(object : MediaSessionService.Listener {
            // Media3 has already logged it; the activity stops the sound and says so.
            override fun onForegroundServiceStartNotAllowedException() {
                Log.w("TMPlayer", "Background audio could not go back into the foreground")
                onCannotContinue?.invoke()
            }
        })
        take()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        take()
        return result
    }

    /** Adds the session the activity handed over, or stops when there is none (a sticky restart). */
    private fun take() {
        val session = pending
        if (session == null) {
            stopSelf()
            return
        }
        if (session === held) return
        held?.let { runCatching { removeSession(it) } }
        held = session
        runCatching { addSession(session) }.onFailure {
            Log.w("TMPlayer", "Background audio could not take the session", it)
            stopSelf()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = held

    /** Swiped away from Recents: the video goes with the app, as with background audio off. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        held?.player?.pause()
        onStopRequested?.invoke()
        stopSelf()
    }

    override fun onDestroy() {
        if (live === this) live = null
        clearListener()
        held?.let { runCatching { removeSession(it) } }
        held = null
        super.onDestroy()
    }

    companion object {
        internal const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 0x7A1

        /** "Playback stopped": its own id, so it outlasts the media notification it replaces. */
        private const val STOPPED_ID = 0x7A2

        @Volatile
        private var pending: MediaSession? = null

        @Volatile
        private var onStopRequested: (() -> Unit)? = null

        @Volatile
        private var onCannotContinue: (() -> Unit)? = null

        @Volatile
        private var live: PlaybackService? = null

        /** What the player last meant to do, for the foreground's grace ([setPlayWhenReady]). */
        @Volatile
        private var meansToPlay = true

        /** Whether a session is in the service's hands now. */
        val running: Boolean get() = pending != null

        /**
         * Hands [session] over as the viewer leaves the player. Called from onStop, inside the
         * seconds after leaving the foreground in which Android still lets an app start a
         * foreground service. [onStop] is how the notification's stop, or a swipe from Recents,
         * ends playback; [onCannotContinue] is Android refusing the foreground later on.
         *
         * @return false when the service could not be started, in which case nothing was handed
         *   over and the caller pauses.
         */
        fun start(
            context: Context,
            session: MediaSession,
            playWhenReady: Boolean,
            onStop: () -> Unit,
            onCannotContinue: () -> Unit,
        ): Boolean {
            pending = session
            onStopRequested = onStop
            this.onCannotContinue = onCannotContinue
            setPlayWhenReady(playWhenReady)
            ensureChannel(context)
            clearStopped(context)
            // The notification's tap brings the app back as it was left, the player on top.
            launchIntent(context)?.let { tap ->
                runCatching { session.setSessionActivity(tap) }
            }
            return runCatching { context.startService(Intent(context, PlaybackService::class.java)) }
                .onFailure {
                    Log.w("TMPlayer", "Background audio could not start", it)
                    pending = null
                    onStopRequested = null
                    this.onCannotContinue = null
                }
                .isSuccess
        }

        /** Takes the session back: the player is in front again, or going away. */
        fun stop(context: Context) {
            if (pending == null) return
            pending = null
            onStopRequested = null
            onCannotContinue = null
            runCatching { context.stopService(Intent(context, PlaybackService::class.java)) }
        }

        /**
         * Follows the player's intent to play, from its own listener, which hears of a change
         * before Media3's notification does: a pause lets the foreground go at once, anything
         * else (an end into the next episode, an error being retried) keeps it for
         * [BackgroundPlayback.GAP_GRACE_MS].
         */
        fun setPlayWhenReady(playWhenReady: Boolean) {
            meansToPlay = playWhenReady
            live?.let { service ->
                runCatching { service.setForegroundServiceTimeoutMs(BackgroundPlayback.foregroundGraceMs(playWhenReady)) }
            }
        }

        /**
         * "Playback stopped, tap to resume", for sound that ended out of sight for any reason
         * other than the viewer's own: a failure no retry got past, an answer the next episode
         * needs (mobile data, room on the disk), or Android refusing the foreground. Quiet, on the
         * playback channel, and naming no video, so it says nothing with the app lock on either.
         */
        fun notifyStopped(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            ensureChannel(context)
            val notification = com.tmplayer.data.notificationBuilder(context, CHANNEL_ID, com.tmplayer.data.OldAndroid.IMPORTANCE_LOW)
                .setSmallIcon(R.drawable.ic_notify_resume)
                .setContentTitle(L.notifyPlaybackStopped)
                .setContentText(L.notifyTapToResume)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setAutoCancel(true)
                .apply { launchIntent(context)?.let(::setContentIntent) }
                .build()
            runCatching { manager(context).notify(STOPPED_ID, notification) }
        }

        /** Takes [notifyStopped]'s notice down: the player is back, or playing unseen again. */
        fun clearStopped(context: Context) {
            runCatching { manager(context).cancel(STOPPED_ID) }
        }

        /** Brings the app's task back as it was left, which has the player on top. */
        private fun launchIntent(context: Context): PendingIntent? {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private fun manager(context: Context): NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        /** One channel, quiet: a media notification is a control, not news. */
        fun ensureChannel(context: Context) {
            // Android 7 and older have no channels: each notification carries its own priority there.
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
            val channel = NotificationChannel(CHANNEL_ID, L.notifyChannelPlayback, NotificationManager.IMPORTANCE_LOW).apply {
                description = L.notifyChannelPlaybackDescription
                setShowBadge(false)
            }
            manager(context).createNotificationChannel(channel)
        }
    }
}
