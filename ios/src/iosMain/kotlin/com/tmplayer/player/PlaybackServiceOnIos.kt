package com.tmplayer.player

import android.content.Context
import androidx.media3.session.MediaSession

/**
 * The player's background playback on iOS, in place of upstream's PlaybackService.kt, an Android
 * foreground service with its media notification. The names are upstream's, so PlayerActivity
 * calls them unchanged. iOS keeps the sound playing in the background through the app's audio
 * session and its audio background mode (Info.plist), so a start always succeeds; the lock
 * screen's and Control Center's player is the session's Now Playing (media3.NowPlaying), shown for
 * as long as the session lives, as iOS shows it, rather than only while this runs. The "Playback
 * stopped, tap to resume" notice is iOS's own quiet notification, with upstream's words and no
 * video's name, a tap opening the app as it was left (the player on top), as Android's does.
 */
object PlaybackService {
    private var pending: MediaSession? = null
    private var onStopRequested: (() -> Unit)? = null

    val running: Boolean get() = pending != null

    @Suppress("UNUSED_PARAMETER")
    fun start(
        context: Context,
        session: MediaSession,
        playWhenReady: Boolean,
        onStop: () -> Unit,
        onCannotContinue: () -> Unit,
    ): Boolean {
        pending = session
        onStopRequested = onStop
        // Playing unseen again: the notice that it had stopped is out of date, as on Android.
        clearStopped(context)
        return true
    }

    @Suppress("UNUSED_PARAMETER")
    fun stop(context: Context) {
        pending = null
        onStopRequested = null
    }

    @Suppress("UNUSED_PARAMETER")
    fun setPlayWhenReady(playWhenReady: Boolean) = Unit

    /**
     * "Playback stopped, tap to resume", for sound that ended out of sight for a reason that was
     * not the viewer's (upstream's `stopUnseen`), when iOS lets the app post.
     */
    @Suppress("UNUSED_PARAMETER")
    fun notifyStopped(context: Context) =
        android.app.IosNotifications.postQuiet(STOPPED_ID, com.tmplayer.i18n.L.notifyPlaybackStopped, com.tmplayer.i18n.L.notifyTapToResume)

    /** Takes [notifyStopped]'s notice down: the player is back, or playing unseen again. */
    @Suppress("UNUSED_PARAMETER")
    fun clearStopped(context: Context) = android.app.IosNotifications.remove(STOPPED_ID)

    private const val STOPPED_ID = "playback-stopped"
}
