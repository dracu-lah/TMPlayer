package com.tmplayer.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.graphics.drawable.Icon
import com.tmplayer.MainActivity
import com.tmplayer.R
import com.tmplayer.i18n.L
import kotlinx.coroutines.flow.first

/**
 * The phone's notifications that help (CP76), one Android channel each ([Notice]), drawn here so
 * the download service and the background checks post them alike. None on a TV: its launcher
 * rows do that job, and its shade is out of sight. Each is posted only while its switch is on and
 * notifications would be seen ([BackgroundChecks.canNotify]); the permission itself is asked for
 * in Settings when a switch is turned on, never here.
 *
 * Play opens the player through the same launch the TV home screen uses
 * ([WatchNextPublisher.intentFor]), which checks the kids lock again before it plays.
 */
object AppNotices {

    const val CHANNEL_RESULTS = "download_results"
    const val CHANNEL_EPISODES = "new_episodes"
    const val CHANNEL_LOW_STORAGE = "low_storage"
    const val CHANNEL_PIECES = "missing_pieces"
    const val CHANNEL_UPDATES = "updates"

    private const val ID_EPISODES = 0x200003
    private const val ID_LOW_STORAGE = 0x200004
    private const val ID_PIECES = 0x200005
    private const val ID_UPDATE = 0x200006
    private const val ID_UPDATED = 0x200007

    /** Whether a notification of [notice] may go out at all on this device. */
    suspend fun allowed(context: Context, notice: Notice): Boolean =
        !FormFactor.isTv(context) && SettingsStore(context).noticeOnNow(notice) && BackgroundChecks.canNotify(context)

    /** Every channel, created (or renamed into the current language) at once. */
    fun ensureChannels(context: Context) {
        // Android 7 and older have no channels: each notification carries its own priority there.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        fun channel(id: String, name: String, description: String, importance: Int) =
            NotificationChannel(id, name, importance).apply { this.description = description }
        val channels = listOf(
            channel(CHANNEL_RESULTS, L.notifyChannelDownloadResults, L.notifyChannelDownloadResultsDescription, NotificationManager.IMPORTANCE_DEFAULT),
            channel(CHANNEL_EPISODES, L.notifyChannelEpisodes, L.notifyChannelEpisodesDescription, NotificationManager.IMPORTANCE_DEFAULT),
            channel(CHANNEL_LOW_STORAGE, L.notifyChannelLowStorage, L.notifyChannelLowStorageDescription, NotificationManager.IMPORTANCE_DEFAULT),
            channel(CHANNEL_PIECES, L.notifyChannelPieces, L.notifyChannelPiecesDescription, NotificationManager.IMPORTANCE_DEFAULT),
            // Quiet: news of a version is not worth a sound.
            channel(CHANNEL_UPDATES, L.notifyChannelUpdates, L.notifyChannelUpdatesDescription, NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        manager(context).createNotificationChannels(channels)
    }

    // ---- finished downloads --------------------------------------------------------------------

    /**
     * One finished (or failed) download, season or cut video, with Play and Try again. Called by
     * the download service once [DownloadResults.record] has a result. [reason] is a lone failure's
     * own sentence.
     */
    suspend fun postDownloadResult(context: Context, result: DownloadResults.Result, reason: String?) {
        if (!allowed(context, Notice.Downloads)) return
        val anyChat = (result.done + result.failed).first().chatId
        val private = DownloadResults.isPrivate(anyChat, ChatGate.awaitReady(), SettingsStore(context).appLock.first())
        val words = DownloadResults.words(result, private, reason)
        val id = DownloadResults.notificationId(result.group)
        ensureChannels(context)
        val builder = notificationBuilder(context, CHANNEL_RESULTS, OldAndroid.IMPORTANCE_DEFAULT)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(words.title)
            .setContentIntent(AndroidTransferNotifier.openDownloads(context))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(
                notificationBuilder(context, CHANNEL_RESULTS, OldAndroid.IMPORTANCE_DEFAULT)
                    .setSmallIcon(R.drawable.ic_stat_download)
                    .setContentTitle(if (result.failed.isEmpty()) L.notifyDownloadDonePrivate else L.notifyDownloadFailedPrivate)
                    .build(),
            )
            .setAutoCancel(true)
        words.body?.let { builder.setContentText(it).setStyle(Notification.BigTextStyle().bigText(it)) }
        words.play?.let { play ->
            builder.addAction(action(context, R.drawable.ic_notify_resume, L.commonPlay, playIntent(context, play.item(), play.chatTitle, id)))
        }
        if (words.retry.isNotEmpty()) {
            builder.addAction(action(context, R.drawable.ic_notify_resume, L.commonTryAgain, retryIntent(context, words.retry, id)))
        }
        runCatching { manager(context).notify(id, builder.build()) }
    }

    /** Asks the download service for [requests] again, as Try again on their rows does. */
    private fun retryIntent(context: Context, requests: List<DownloadRequest>, code: Int): PendingIntent {
        val intent = Intent(context, DownloadService::class.java).apply {
            action = DownloadService.ACTION_RESUME
            putExtra(DownloadService.EXTRA_FILE_IDS, requests.map { it.fileId }.toIntArray())
            putExtra(EXTRA_DISMISS, code)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(context, code, intent, flags)
        } else {
            PendingIntent.getService(context, code, intent, flags)
        }
    }

    // ---- new episodes, missing pieces ----------------------------------------------------------

    fun postEpisode(context: Context, alert: EpisodeAlerts.Alert) {
        ensureChannels(context)
        val builder = notificationBuilder(context, CHANNEL_EPISODES, OldAndroid.IMPORTANCE_DEFAULT)
            .setSmallIcon(R.drawable.ic_stat_new_videos)
            .setContentTitle(alert.title)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(
                notificationBuilder(context, CHANNEL_EPISODES, OldAndroid.IMPORTANCE_DEFAULT)
                    .setSmallIcon(R.drawable.ic_stat_new_videos)
                    .setContentTitle(L.notifyEpisodesPrivate)
                    .build(),
            )
            .setContentIntent(openChat(context, ID_EPISODES, alert.chatId))
            .setAutoCancel(true)
        alert.body?.let { builder.setContentText(it) }
        alert.play?.let { item ->
            builder.addAction(action(context, R.drawable.ic_notify_resume, L.commonPlay, playIntent(context, item, alert.chatTitle, ID_EPISODES)))
        }
        runCatching { manager(context).notify(ID_EPISODES, builder.build()) }
    }

    fun postPieces(context: Context, alert: PieceAlerts.Alert, index: Int) {
        ensureChannels(context)
        val id = ID_PIECES + index * 0x10
        val builder = notificationBuilder(context, CHANNEL_PIECES, OldAndroid.IMPORTANCE_DEFAULT)
            .setSmallIcon(R.drawable.ic_stat_new_videos)
            .setContentTitle(alert.title)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(
                notificationBuilder(context, CHANNEL_PIECES, OldAndroid.IMPORTANCE_DEFAULT)
                    .setSmallIcon(R.drawable.ic_stat_new_videos)
                    .setContentTitle(L.notifyPiecesPrivate)
                    .build(),
            )
            .setContentIntent(openChat(context, id, alert.play?.chatId))
            .setAutoCancel(true)
        alert.body?.let { builder.setContentText(it) }
        alert.play?.let { item ->
            builder.addAction(action(context, R.drawable.ic_notify_resume, L.commonPlay, playIntent(context, item, alert.chatTitle, id)))
        }
        runCatching { manager(context).notify(id, builder.build()) }
    }

    // ---- low storage, update ready -------------------------------------------------------------

    /** Low storage, whose press and button open Downloads with Free up space already up. */
    fun postLowStorage(context: Context, title: String, body: String) {
        ensureChannels(context)
        val freeUp = open(context, ID_LOW_STORAGE) { it.putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_FREE_UP_SPACE) }
        val notification = notificationBuilder(context, CHANNEL_LOW_STORAGE, OldAndroid.IMPORTANCE_DEFAULT)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(freeUp)
            .addAction(action(context, R.drawable.ic_notify_cancel, L.downloadsFreeUpSpace, freeUp))
            .setAutoCancel(true)
            .build()
        runCatching { manager(context).notify(ID_LOW_STORAGE, notification) }
    }

    /** An update, whose press opens the app, where the launch check shows the update window. */
    fun postUpdate(context: Context, release: Release) {
        ensureChannels(context)
        val (title, body) = UpdateAlerts.words(release)
        val notification = notificationBuilder(context, CHANNEL_UPDATES, OldAndroid.IMPORTANCE_LOW)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(open(context, ID_UPDATE) {})
            .setAutoCancel(true)
            .build()
        runCatching { manager(context).notify(ID_UPDATE, notification) }
    }

    /**
     * "TMPlayer updated to 1.29.0, tap to open it" (HX5): after an update Android installed in a
     * session, which closed the app and has no Open button of its own. Posted on a TV too, where
     * the launcher may show it; quiet, on the updates channel.
     */
    fun postUpdated(context: Context, version: String) {
        ensureChannels(context)
        val notification = notificationBuilder(context, CHANNEL_UPDATES, OldAndroid.IMPORTANCE_LOW)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(L.notifyUpdatedTitle(version = version))
            .setContentText(L.notifyUpdatedBody)
            .setContentIntent(open(context, ID_UPDATED) {})
            .setAutoCancel(true)
            .build()
        runCatching { manager(context).notify(ID_UPDATED, notification) }
    }

    /** Takes [postUpdated]'s notification down once the app is open again. */
    fun cancelUpdated(context: Context) {
        runCatching { manager(context).cancel(ID_UPDATED) }
    }

    // ---- shared --------------------------------------------------------------------------------

    /** Passed on an action's intent: the notification to take down once the action is done. */
    const val EXTRA_DISMISS = "com.tmplayer.extra.DISMISS_NOTIFICATION"

    /** Plays [item] through the home screen's launch, which checks the kids lock first. */
    private fun playIntent(context: Context, item: MediaItem, chatTitle: String, code: Int): PendingIntent {
        val video = WatchNext.Video(
            chatId = item.chatId,
            messageId = item.messageId,
            fileId = item.fileId,
            title = item.title,
            chatTitle = chatTitle,
            sizeBytes = item.sizeBytes,
            durationSec = item.durationSec,
        )
        val intent = WatchNextPublisher.intentFor(context, video)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            code + 1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openChat(context: Context, code: Int, chatId: Long?): PendingIntent =
        open(context, code) { it.putExtra(BackgroundChecks.EXTRA_OPEN_CHAT, chatId ?: 0L) }

    fun open(context: Context, code: Int, extras: (Intent) -> Unit): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .also(extras)
        return PendingIntent.getActivity(
            context,
            code,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun action(context: Context, icon: Int, label: String, intent: PendingIntent): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(context, icon), label, intent).build()

    fun manager(context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
