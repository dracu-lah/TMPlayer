package com.tmplayer.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import androidx.core.content.ContextCompat
import com.tmplayer.R
import com.tmplayer.i18n.L

/**
 * The corner window's own buttons on Android 8 to 10 (WP-36): back, play or pause, forward.
 *
 * From Android 11 the system draws play, pause and the seeks from the media session, so nothing is
 * offered there. Before that the window had no buttons at all. Each is a [RemoteAction] whose
 * [PendingIntent] is a package scoped, immutable broadcast; the activity listens for it with a
 * receiver it registers itself and that is not exported ([register]).
 */
object PipActions {

    const val ACTION = "com.tmplayer.player.PIP_ACTION"
    private const val EXTRA_KIND = "kind"

    enum class Kind { Back, PlayPause, Forward }

    /** Whether this Android draws no buttons of its own in the corner window: 8, 9 and 10. */
    fun wanted(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= Build.VERSION_CODES.O && sdk < Build.VERSION_CODES.R

    /**
     * Puts the three buttons on [builder] where [wanted]; [seekMs] is each seek's size (the row's
     * own buttons, [Skip.BUTTON_MS]) and [playing] picks the middle one's face.
     */
    fun apply(
        builder: PictureInPictureParams.Builder,
        context: Context,
        playing: Boolean,
        seekMs: Long = Skip.BUTTON_MS,
        sdk: Int = Build.VERSION.SDK_INT,
    ) {
        if (!wanted(sdk)) return
        runCatching { builder.setActions(actions(context, playing, seekMs)) }
    }

    fun actions(context: Context, playing: Boolean, seekMs: Long): List<RemoteAction> {
        val back = L.notifySeekBack(seekMs / 1000)
        val forward = L.notifySeekForward(seekMs / 1000)
        val middle = if (playing) L.commonPause else L.commonPlay
        return listOf(
            action(context, Kind.Back, R.drawable.ic_seek_back, back),
            action(context, Kind.PlayPause, if (playing) R.drawable.ic_notify_pause else R.drawable.ic_notify_resume, middle),
            action(context, Kind.Forward, R.drawable.ic_seek_forward, forward),
        )
    }

    private fun action(context: Context, kind: Kind, icon: Int, label: String): RemoteAction {
        // Package scoped and immutable: nothing outside the app can fire it or change what it carries.
        val intent = Intent(ACTION).setPackage(context.packageName).putExtra(EXTRA_KIND, kind.ordinal)
        val pending = PendingIntent.getBroadcast(
            context, kind.ordinal, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return RemoteAction(Icon.createWithResource(context, icon), label, label, pending)
    }

    /** Which button a broadcast says was pressed, or null for anything else (a bad extra, another action). */
    fun kindOf(intent: Intent?): Kind? {
        if (intent?.action != ACTION) return null
        return Kind.entries.getOrNull(intent.getIntExtra(EXTRA_KIND, -1))
    }

    /** Registers a receiver for the buttons, not exported (Android 13 and later insist on the flag). */
    fun register(context: Context, onPressed: (Kind) -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                kindOf(intent)?.let(onPressed)
            }
        }
        // Android 13 and later want the flag named (and a target of 34 refuses a missing one);
        // ContextCompat gives the older ones the same guarantee through a signature permission.
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        return receiver
    }
}
