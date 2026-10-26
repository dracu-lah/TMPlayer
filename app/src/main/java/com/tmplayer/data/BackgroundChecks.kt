package com.tmplayer.data

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.tmplayer.App
import com.tmplayer.MainActivity
import com.tmplayer.R
import com.tmplayer.i18n.L
import com.tmplayer.platform.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What a phone checks while TMPlayer is closed, as platform jobs (JobScheduler, which needs no
 * library), each behind the switches it serves ([Notice]):
 *
 * - [JOB_NEW_VIDEOS], every [NEW_VIDEOS_EVERY_MS] while online with the battery not low: the chats.
 *   New videos in the starred chats ([NewVideoAlerts]), new episodes of shows under way
 *   ([EpisodeAlerts]) and the missing pieces of cut videos ([PieceAlerts]).
 * - [JOB_SPONSOR], once a day: the support ask ([SupportReminder.nudgeDue]) and low storage
 *   ([StorageAlerts]), neither of which needs a connection.
 * - [JOB_UPDATES], twice a day while online: a new version, said while the app is closed
 *   ([UpdateAlerts]), or installed by itself with "Install updates by itself" on ([AutoUpdater]).
 *
 * A TV schedules none: its notifications are mostly out of sight, and Home's rows already show
 * what is new. None asks for the notification permission: each switch asks when it is turned on,
 * and a check whose notification would not be seen stays quiet.
 *
 * **TDLib.** The process the job wakes starts TDLib in [App], as the download service's does, and
 * the chat checks wait for a connected session for at most [CONNECT_WAIT_MS]. TDLib is the
 * process's one client, shared with any screen that opens, so the check does not close it; it asks
 * for one page per chat, one chat at a time, and the system reclaims the idle process as it does
 * after a download.
 */
class BackgroundChecks : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.tmplayer.platform.JobFailures.handler("BackgroundChecks"))
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        running = scope.launch {
            runCatching {
                when (params.jobId) {
                    JOB_NEW_VIDEOS -> checkChats(applicationContext)
                    JOB_SPONSOR -> {
                        checkSponsorNudge(applicationContext)
                        checkStorage(applicationContext)
                    }
                    JOB_UPDATES -> checkUpdate(applicationContext)
                }
            }.onFailure { Logger.w(TAG, "background check failed", it) }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        // Periodic either way; the next window comes round by itself.
        return false
    }

    companion object {
        private const val TAG = "BackgroundChecks"

        const val JOB_NEW_VIDEOS = 4301
        const val JOB_SPONSOR = 4302
        const val JOB_UPDATES = 4303

        const val CHANNEL_NEWS = "news"
        const val CHANNEL_NEW_VIDEOS = "new_videos"

        private const val NOTIFY_NEW_VIDEOS = 0x200001
        private const val NOTIFY_SPONSOR = 0x200002

        /** Every three hours, which the system may stretch to suit the battery. */
        const val NEW_VIDEOS_EVERY_MS = 3L * 60 * 60 * 1000
        private const val SPONSOR_EVERY_MS = 24L * 60 * 60 * 1000
        private const val UPDATES_EVERY_MS = 12L * 60 * 60 * 1000
        private const val CONNECT_WAIT_MS = 90_000L

        /** A launch from the sponsor reminder: the shell opens the Buy Me a Coffee window. */
        const val OPEN_SUPPORT = "support"

        /** A launch from a new videos notification: the chat to open, or 0 for the chat list. */
        const val EXTRA_OPEN_CHAT = "com.tmplayer.extra.OPEN_CHAT"

        /**
         * Makes the jobs match the settings: called when the app starts and whenever a switch
         * changes. Nothing at all on a TV, and no support job in a build without donation links.
         */
        suspend fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val tv = FormFactor.isTv(context)
            val settings = SettingsStore(context)
            val chats = !tv && (
                settings.noticeOnNow(Notice.NewVideos) ||
                    settings.noticeOnNow(Notice.NewEpisodes) ||
                    settings.noticeOnNow(Notice.MissingPieces)
                )
            val sponsor = SupportReminder.enabled && settings.sponsorRemindersNow() &&
                !settings.supportCountersNow().finished
            val daily = !tv && (sponsor || settings.noticeOnNow(Notice.LowStorage))
            val updates = !tv && Updates.enabled &&
                (settings.noticeOnNow(Notice.UpdateReady) || (AutoUpdater.supported && settings.updateAutoInstallNow()))
            // Waiting for the battery is Android 8's; on 6 and 7 the checks run on schedule, which
            // a television plugged into the wall does not notice.
            ensure(context, scheduler, JOB_NEW_VIDEOS, chats) { component ->
                JobInfo.Builder(JOB_NEW_VIDEOS, component)
                    .setPeriodic(NEW_VIDEOS_EVERY_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .apply { if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) setRequiresBatteryNotLow(true) }
                    .setPersisted(true)
                    .build()
            }
            ensure(context, scheduler, JOB_SPONSOR, daily) { component ->
                JobInfo.Builder(JOB_SPONSOR, component)
                    .setPeriodic(SPONSOR_EVERY_MS)
                    .apply { if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) setRequiresBatteryNotLow(true) }
                    .setPersisted(true)
                    .build()
            }
            ensure(context, scheduler, JOB_UPDATES, updates) { component ->
                JobInfo.Builder(JOB_UPDATES, component)
                    .setPeriodic(UPDATES_EVERY_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .apply { if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) setRequiresBatteryNotLow(true) }
                    .setPersisted(true)
                    .build()
            }
        }

        private fun ensure(
            context: Context,
            scheduler: JobScheduler,
            id: Int,
            wanted: Boolean,
            build: (ComponentName) -> JobInfo,
        ) {
            // Asking for one job by id is Android 7's; on 6 it is found in the whole list.
            val pending = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                scheduler.getPendingJob(id)
            } else {
                scheduler.allPendingJobs.firstOrNull { it.id == id }
            }
            when {
                wanted && pending == null -> runCatching {
                    scheduler.schedule(build(ComponentName(context, BackgroundChecks::class.java)))
                }.onFailure { Logger.w(TAG, "job $id not scheduled", it) }
                !wanted && pending != null -> scheduler.cancel(id)
            }
        }

        /** Whether a notification posted now would be seen: allowed, and on Android 13, granted. */
        fun canNotify(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            return manager(context).areNotificationsEnabled()
        }

        /**
         * One look at the chats: new videos in the starred ones, new episodes of shows under way,
         * and the missing pieces of cut videos, each behind its own switch. One connected session
         * for all three; nothing is posted over the app itself.
         */
        suspend fun checkChats(context: Context) {
            val settings = SettingsStore(context)
            val newVideos = settings.noticeOnNow(Notice.NewVideos)
            val episodes = settings.noticeOnNow(Notice.NewEpisodes)
            val pieces = settings.noticeOnNow(Notice.MissingPieces)
            if (!newVideos && !episodes && !pieces) return
            Td.start(context)
            val session = withTimeoutOrNull(CONNECT_WAIT_MS) { Td.awaitConnectedSession() } ?: return
            val allowed = ChatGate.awaitReady()
            if (newVideos) {
                val alert = NewVideoAlerts.check(session.client, settings, allowed)
                // With the app on screen the lists already show what came in.
                if (alert != null && !App.inFront) postNewVideos(context, alert)
            }
            if (episodes) {
                val alert = EpisodeAlerts.check(session.client, settings, WatchedStore(context), allowed)
                if (alert != null && !App.inFront && AppNotices.allowed(context, Notice.NewEpisodes)) {
                    AppNotices.postEpisode(context, alert)
                }
            }
            if (pieces) {
                val alerts = PieceAlerts.check(session.client, settings, allowed, System.currentTimeMillis())
                if (!App.inFront && AppNotices.allowed(context, Notice.MissingPieces)) {
                    alerts.forEachIndexed { index, alert -> AppNotices.postPieces(context, alert, index) }
                }
            }
        }

        /** Low storage, if it has just run low and its switch is on. */
        suspend fun checkStorage(context: Context) {
            if (!AppNotices.allowed(context, Notice.LowStorage)) return
            val words = StorageAlerts.check(SettingsStore(context), DiskSpace.read(context), System.currentTimeMillis()) ?: return
            AppNotices.postLowStorage(context, words.first, words.second)
        }

        /**
         * A new version: installed by itself where the viewer asked for that (HX5), which ends the
         * process when it does; else said once, while the app is closed.
         */
        suspend fun checkUpdate(context: Context) {
            if (App.inFront) return
            if (runCatching { AutoUpdater.runIfDue(context) }.getOrDefault(false)) return
            if (!AppNotices.allowed(context, Notice.UpdateReady)) return
            val release = UpdateAlerts.check(SettingsStore(context)) { App.inFront } ?: return
            AppNotices.postUpdate(context, release)
        }

        /**
         * The support notification, if it is due and would be seen: first a star on its own, then
         * "Don't forget to sponsor", with Buy me a coffee, Star on GitHub (until pressed) and Not
         * now. Pressing it opens the window.
         */
        suspend fun checkSponsorNudge(context: Context) {
            if (FormFactor.isTv(context) || App.inFront || !canNotify(context)) return
            val settings = SettingsStore(context)
            val rung = SupportReminder.claimNudgeRung(settings, System.currentTimeMillis()) ?: return
            val counters = settings.supportCountersNow()
            ensureChannels(context)
            val star = rung == SupportReminder.NudgeRung.Star
            val title = if (star) L.notifyStarTitle else L.notifySponsorTitle
            val body = if (star) L.notifyStarBody else L.notifySponsorBody
            val builder = notificationBuilder(context, CHANNEL_NEWS, OldAndroid.IMPORTANCE_LOW)
                .setSmallIcon(R.drawable.ic_stat_support)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setContentIntent(AppNotices.open(context, NOTIFY_SPONSOR) { it.putExtra(MainActivity.EXTRA_OPEN, OPEN_SUPPORT) })
                .setAutoCancel(true)
            if (!star) {
                builder.addAction(
                    AppNotices.action(context, R.drawable.ic_stat_support, L.supportBuyCoffee, NoticeActions.link(context, NoticeActions.LINK_COFFEE, NOTIFY_SPONSOR)),
                )
            }
            if (SupportReminder.offersStar(counters, rung)) {
                builder.addAction(
                    AppNotices.action(context, R.drawable.ic_stat_support, L.supportStarGithub, NoticeActions.link(context, NoticeActions.LINK_STAR, NOTIFY_SPONSOR)),
                )
            }
            builder.addAction(
                AppNotices.action(context, R.drawable.ic_notify_cancel, L.notifyActionNotNow, NoticeActions.dismiss(context, NOTIFY_SPONSOR)),
            )
            runCatching { manager(context).notify(NOTIFY_SPONSOR, builder.build()) }
        }

        private fun postNewVideos(context: Context, alert: NewVideoAlerts.Alert) {
            if (!canNotify(context)) return
            ensureChannels(context)
            val builder = notificationBuilder(context, CHANNEL_NEW_VIDEOS, OldAndroid.IMPORTANCE_DEFAULT)
                .setSmallIcon(R.drawable.ic_stat_new_videos)
                .setContentTitle(alert.title)
                // Kept off the lock screen whatever the alert says: a chat's name is private.
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    notificationBuilder(context, CHANNEL_NEW_VIDEOS, OldAndroid.IMPORTANCE_DEFAULT)
                        .setSmallIcon(R.drawable.ic_stat_new_videos)
                        .setContentTitle(L.notifyNewVideosPrivate)
                        .build(),
                )
                .setContentIntent(AppNotices.open(context, NOTIFY_NEW_VIDEOS) { it.putExtra(EXTRA_OPEN_CHAT, alert.chatId ?: 0L) })
                .setAutoCancel(true)
            alert.body?.let { builder.setContentText(it).setStyle(Notification.BigTextStyle().bigText(it)) }
            runCatching { manager(context).notify(NOTIFY_NEW_VIDEOS, builder.build()) }
        }

        /** "TMPlayer news", quiet, "New videos", which may make a sound, and the CP76 channels. */
        fun ensureChannels(context: Context) {
            // Android 7 and older have no channels: each notification carries its own priority there.
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
            val news = NotificationChannel(CHANNEL_NEWS, L.notifyChannelNews, NotificationManager.IMPORTANCE_LOW).apply {
                description = L.notifyChannelNewsDescription
                setShowBadge(false)
            }
            val videos = NotificationChannel(CHANNEL_NEW_VIDEOS, L.notifyChannelNewVideos, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = L.notifyChannelNewVideosDescription
            }
            manager(context).createNotificationChannels(listOf(news, videos))
            AppNotices.ensureChannels(context)
        }

        private fun manager(context: Context): NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
}
