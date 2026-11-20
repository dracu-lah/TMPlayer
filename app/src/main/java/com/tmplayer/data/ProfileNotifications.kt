package com.tmplayer.data

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.os.Build
import com.tmplayer.platform.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * The one way the app posts a notification or names what plays in a media session (HX88, the
 * policy test `ProfileNotificationsPolicyTest` keeps it that way). Each post gives the real
 * notification and what it is ([Kind]); while the open profile is hidden behind its PIN
 * ([ProfileUnlock.hiddenNow]) the shade gets a neutral one instead (the app's name, nothing of the
 * profile), and when it hides or shows again everything still up is posted again ([republish]):
 * ongoing ones neutral or real, results of the profile taken down.
 */
object ProfileNotifications {

    /** What a notification shows. */
    enum class Kind {
        /** An ongoing one with the profile's titles (a download, a cast, an export). */
        Ongoing,

        /** A result or an alert with the profile's titles: taken down when the profile hides. */
        Result,

        /** The device's (an update, low storage, the sponsor card): never changed. */
        Device,
    }

    enum class Choice { Real, Neutral }

    /** The pure choice: the device's always real, the profile's neutral while it is hidden. */
    fun choose(hidden: Boolean, kind: Kind): Choice = if (kind == Kind.Device || !hidden) Choice.Real else Choice.Neutral

    /** What a change of [hidden] does to a notification of [kind] still up: post it again ([Choice]) or take it down (null). */
    fun onFlip(hidden: Boolean, kind: Kind): Choice? = when {
        kind == Kind.Device -> Choice.Real
        hidden && kind == Kind.Result -> null
        else -> choose(hidden, kind)
    }

    /** What was posted, and for which profile: the [owner] the job that posted it started under. */
    private class Posted(val real: Notification, val kind: Kind, val owner: AccountStamp) {
        val folder: String get() = owner.folder.orEmpty()
    }

    private val posted = ConcurrentHashMap<Int, Posted>()

    /** The extra every profile notification carries: which profile's it is, so a later process can find it. */
    internal const val PROFILE_EXTRA = "com.tmplayer.profile"

    /**
     * Whether a post of [kind] made for [owner] may go up now: the device's always; a profile's
     * only while [owner] is the open profile's turn ([Accounts.stamp]). A job that outlived a
     * switch never posts its profile's titles into the next one's shade.
     */
    fun mayPost(kind: Kind, owner: AccountStamp, current: AccountStamp = Accounts.stamp): Boolean =
        kind == Kind.Device || owner == current

    /**
     * Posts [notification] for [owner], the profile the job started under (taken when it started,
     * not now). Dropped when [owner] is no longer the open profile's turn.
     */
    fun post(context: Context, id: Int, notification: Notification, kind: Kind, owner: AccountStamp = Accounts.stamp) {
        val app = context.applicationContext
        if (!mayPost(kind, owner)) {
            Logger.i(TAG, "a notification of a profile no longer open was dropped")
            return
        }
        if (kind != Kind.Device) {
            posted[id] = Posted(notification, kind, owner)
            notification.extras.putString(PROFILE_EXTRA, owner.folder.orEmpty())
        }
        runCatching { manager(app).notify(id, shown(app, notification, kind)) }
            .onFailure { Logger.w(TAG, "a notification was not posted", it) }
    }

    /**
     * A service's foreground notification, as [post] would show it, for [owner]. Android wants one
     * however late the service starts, so for a profile no longer open it is the neutral one.
     * [type] is the service type on Android 10 and later.
     */
    fun foreground(service: Service, id: Int, notification: Notification, kind: Kind, type: Int, owner: AccountStamp = Accounts.stamp) {
        val stale = !mayPost(kind, owner)
        if (kind != Kind.Device) {
            posted[id] = Posted(notification, kind, owner)
            notification.extras.putString(PROFILE_EXTRA, owner.folder.orEmpty())
        }
        val shown = if (stale) neutral(service, notification) else shown(service, notification, kind)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) service.startForeground(id, shown, type) else service.startForeground(id, shown)
    }

    fun cancel(context: Context, id: Int) {
        posted.remove(id)
        runCatching { manager(context.applicationContext).cancel(id) }
    }

    /** [notification] as the shade may show it now. */
    fun shown(context: Context, notification: Notification, kind: Kind): Notification =
        when (choose(ProfileUnlock.hiddenNow(), kind)) {
            Choice.Real -> notification
            Choice.Neutral -> neutral(context, notification)
        }

    /** The same channel, icon, tap and ongoing flag, the app's name for a title, and nothing else. */
    fun neutral(context: Context, real: Notification): Notification {
        val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) real.channelId.orEmpty() else ""
        val builder = notificationBuilder(context, channel, NotificationManager.IMPORTANCE_LOW)
        builder.setSmallIcon(real.smallIcon ?: Icon.createWithResource(context, context.applicationInfo.icon))
        builder.setContentTitle(appName(context))
        builder.setOnlyAlertOnce(true)
        builder.setVisibility(Notification.VISIBILITY_PRIVATE)
        real.contentIntent?.let(builder::setContentIntent)
        builder.setOngoing(real.flags and Notification.FLAG_ONGOING_EVENT != 0)
        // Still dismissed on a tap, and still telling its owner when it is swiped away.
        builder.setAutoCancel(real.flags and Notification.FLAG_AUTO_CANCEL != 0)
        real.deleteIntent?.let(builder::setDeleteIntent)
        // Still the profile's, so a later process can find and clear it.
        real.extras?.getString(PROFILE_EXTRA)?.let { owner -> builder.addExtras(android.os.Bundle().apply { putString(PROFILE_EXTRA, owner) }) }
        return builder.build()
    }

    /**
     * Posts again what is still up whenever the open profile hides or shows, the first value too;
     * takes down every profile notification of the profile left on a switch; and at a cold start
     * with the open profile hidden, takes down what an earlier process left in the shade, before
     * anything of this one posts.
     */
    fun follow(context: Context, scope: CoroutineScope) {
        val app = context.applicationContext
        if (ProfileUnlock.hiddenNow()) clearLeftBehind(app)
        scope.launch { ProfileUnlock.hidden().collect { hidden -> republish(app, hidden) } }
        scope.launch { Accounts.activeFolder.drop(1).collect { folder -> leftProfile(app, folder.orEmpty()) } }
    }

    /** A switch: every notification of another profile than [open] comes down (the device's stay). */
    internal fun leftProfile(context: Context, open: String) {
        for ((id, entry) in posted.entries.toList()) {
            if (entry.kind != Kind.Device && entry.folder != open) cancel(context, id)
        }
    }

    /** The open profile signs out (its wipe): all its notifications come down. */
    fun clearProfile(context: Context) {
        for ((id, entry) in posted.entries.toList()) if (entry.kind != Kind.Device) cancel(context, id)
    }

    /**
     * A cold start with the open profile hidden: what an earlier process posted is unknown here,
     * so every profile notification (each carries [PROFILE_EXTRA]) comes down; the device's stay.
     */
    internal fun clearLeftBehind(context: Context) {
        // Only the profile's (they carry PROFILE_EXTRA): an update or low storage notice stays.
        runCatching {
            val manager = manager(context)
            for (each in manager.activeNotifications) {
                if (each.notification.extras.containsKey(PROFILE_EXTRA)) manager.cancel(each.tag, each.id)
            }
        }
    }

    internal fun republish(context: Context, hidden: Boolean) {
        val manager = manager(context)
        val up = runCatching { manager.activeNotifications.map { it.id }.toSet() }.getOrDefault(posted.keys.toSet())
        for ((id, entry) in posted.entries.toList()) {
            if (id !in up) {
                posted.remove(id)
                continue
            }
            runCatching {
                when (onFlip(hidden, entry.kind)) {
                    null -> cancel(context, id)
                    Choice.Real -> manager.notify(id, entry.real)
                    Choice.Neutral -> manager.notify(id, neutral(context, entry.real))
                }
            }
        }
        ProfileMedia.reapply(context, hidden)
    }

    internal fun appName(context: Context): String =
        runCatching { context.applicationInfo.loadLabel(context.packageManager).toString() }.getOrDefault("TMPlayer")

    private fun manager(context: Context) = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** For tests. */
    internal fun forgetAll() = posted.clear()

    private const val TAG = "ProfileNotifications"
}

/**
 * What a framework media session (the cast's) names, through the same choice: the app's name
 * alone while the open profile is hidden, the real metadata again when it shows. The player's
 * Media3 session has its own private metadata ([com.tmplayer.player.PlayerActivity]).
 */
object ProfileMedia {
    private val sessions = WeakHashMap<MediaSession, MediaMetadata>()

    fun setMetadata(context: Context, session: MediaSession, real: MediaMetadata) {
        synchronized(sessions) { sessions[session] = real }
        runCatching { session.setMetadata(shown(context, real, ProfileUnlock.hiddenNow())) }
    }

    fun shown(context: Context, real: MediaMetadata, hidden: Boolean): MediaMetadata =
        if (!hidden) real else MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, ProfileNotifications.appName(context)).build()

    internal fun reapply(context: Context, hidden: Boolean) {
        val each = synchronized(sessions) { sessions.entries.map { it.key to it.value } }
        for ((session, real) in each) runCatching { session.setMetadata(shown(context, real, hidden)) }
    }
}
