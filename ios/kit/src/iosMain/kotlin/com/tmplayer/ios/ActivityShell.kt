package com.tmplayer.ios

import android.app.Application
import android.content.ActivityHost
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import com.tmplayer.data.Td
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.ComposeUIViewController
import com.tmplayer.App
import com.tmplayer.MainActivity
import com.tmplayer.platform.Logger
import com.tmplayer.player.PlayerActivity
import com.tmplayer.ui.nav.BackStack
import com.tmplayer.ui.nav.LocalBackStack
import com.tmplayer.ui.nav.backInput
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIViewController

/**
 * Runs Android's App and MainActivity as upstream wrote them (docs/IOS.md, "Android's
 * phone screens on iOS"): the process starts once, the one activity is created, and iOS's app
 * states move it through Android's lifecycle. What the activity hands to setContent is drawn in a
 * ComposeUIViewController, the same on iPhone and iPad; the layout follows the window's size.
 *
 * The player's intents come here through ActivityHost and start upstream's PlayerActivity, the
 * app's one player, as Android starts it.
 */
internal object ActivityShell {
    private const val TAG = "ActivityShell"

    private var app: App? = null
    /** MainActivity, or in a promo build launched with `-promo` the promo fixture ([promoForShots]). */
    private var activity: androidx.activity.ComponentActivity? = null
    private var main: ActivityRun? = null

    /** PlayerActivity while it is up, over the app's screens. */
    private var playerScreen: ActivityScreen? = null

    /** The activity in front, which the app's states move: the player's, or the screens'. */
    private fun front(): ActivityRun? = playerScreen?.top ?: main

    fun controller(launcher: PlayerLauncher): UIViewController {
        com.tmplayer.platform.HeapMarks.mark("shell start")
        // From the start, so the app's own start (App.onCreate) is sampled too.
        if (app == null) {
            PerfSampler.startIfAsked()
            AllocProfile.startIfAsked()
        }
        // :app's R.drawable ids to file names, for painterResource (ui/src/iosStandIns).
        androidx.compose.ui.res.DrawableNames.lookup = { com.tmplayer.R.drawableNames[it] }
        val app = app ?: App().also {
            // Before anything reads a download's record: an update gave the app a new folder.
            com.tmplayer.data.MovedContainer.atLaunch(it)
            // Android's DownloadService intents, run in process (DownloadsOnIos.kt).
            com.tmplayer.data.IosDownloads.install(it)
            com.tmplayer.platform.HeapMarks.mark("app made")
            it.onCreate()
            com.tmplayer.platform.HeapMarks.mark("app created")
            app = it
        }
        val activity = activity ?: (promoForShots() ?: MainActivity()).also {
            ActivityHost.register(PlayerActivity::class.simpleName.orEmpty()) { intent ->
                openActivity(app, launcher, intent)
            }
            com.tmplayer.platform.HeapMarks.mark("activity made")
            it.attachBaseContext(app)
            val run = ActivityRun(app, it)
            run.create()
            com.tmplayer.platform.HeapMarks.mark("activity created")
            activity = it
            main = run
            follow(app)
            // What iOS lets the app post, for upstream's checks of Android's permission.
            android.app.IosNotifications.refresh()
            run.start()
            com.tmplayer.platform.HeapMarks.mark("activity started")
            com.tmplayer.platform.HeapMarks.markLater(listOf(200, 500, 1000, 2000, 4000, 8000))
            if (UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive) run.resume()
            if (it is MainActivity) {
                playRecentForCheck(it)
                playContinueForCheck(it)
                playFileForCheck(it)
                openScreenForCheck(it)
                noticeForCheck(it)
            }
            NetCheck.startIfAsked()
            PerfProbe.startIfAsked()
            OwnKeyWatch.start()
        }
        val back = BackStack()
        // Esc on an iPad's keyboard, as MainActivity's own key handling sends it to Back.
        activity.onBackPressedDispatcher.onBack = { back.handleBack() }
        // iOS's swipe from the leading edge, as Android's back gesture: the player's Back while
        // it is up, the screens' otherwise.
        val swipe = SystemBack { playerScreen?.back() ?: back.handleBack() }
        // Over the player, the swipe leaves alone what the player keeps for itself (the scrub
        // bar's strip), and taking it cancels the player's own touch, as Android's system does.
        swipe.excluded = { x, y -> playerScreen?.excludes(x, y) == true }
        swipe.began = { playerScreen?.cancelTouches() }
        lateinit var controller: UIViewController
        controller = ComposeUIViewController {
            CompositionLocalProvider(
                LocalContext provides activity,
                LocalActivity provides activity,
                LocalBackStack provides back,
            ) {
                Box(Modifier.fillMaxSize().backInput(back)) {
                    activity.content?.invoke()
                }
                // The window exists once the screens are in it.
                LaunchedEffect(Unit) {
                    while (controller.view.window == null) delay(100)
                    swipe.attach(controller.view.window)
                }
            }
        }
        return controller
    }

    /**
     * Upstream's PlayerActivity for [intent], as Android starts it over the screens: the screens
     * pause, the player is created and shown full screen, and once it is up the screens stop. One
     * started from a player (the next episode from another chat) goes over that one, as Android
     * stacks a standard activity.
     */
    private fun openActivity(app: Application, launcher: PlayerLauncher, intent: android.content.Intent) {
        // The audio session before the player that plays into it, off the main thread.
        if (playerScreen == null) launcher.audioReady { startActivity(app, launcher, intent) } else startActivity(app, launcher, intent)
    }

    private fun startActivity(app: Application, launcher: PlayerLauncher, intent: android.content.Intent) {
        val up = playerScreen
        AndroidViews.install()
        val player = PlayerActivity()
        player.intent = intent
        player.attachBaseContext(app)
        if (up == null) main?.pause()
        val run = ActivityRun(app, player)
        if (!run.create()) {
            run.destroy()
            if (up == null) main?.resume()
            return
        }
        PlayerSafeArea.keepClear(player)
        if (up != null) {
            up.push(run)
            return
        }
        val screen = ActivityScreen(
            first = run,
            whenShown = { main?.stop() },
            whenClosed = ::playerActivityClosed,
        )
        playerScreen = screen
        launcher.present(screen)
    }

    /**
     * A developer's check on a real device, where nothing can tap for us: launched with
     * `-playRecent <n>`, the app opens the n-th newest video of the account (Home's own query,
     * newest first) in the player, as a tap on Play would, so its screens can be photographed from
     * the Mac (`xcrun devicectl device capture screenshot`).
     */
    private fun playRecentForCheck(activity: MainActivity) {
        val defaults = platform.Foundation.NSUserDefaults.standardUserDefaults
        if (defaults.objectForKey("playRecent") == null) return
        val index = defaults.integerForKey("playRecent").toInt().coerceAtLeast(0)
        com.tmplayer.App.backgroundScope.launch {
            val item = runCatching {
                val session = Td.awaitAuthorizedSession()
                val found = com.tmplayer.data.ChatRepository(session.client).recentMedia(limit = index + 20)
                Logger.i(TAG, "check: ${found.size} recent videos, opening #$index")
                found.getOrNull(index)
            }.onFailure { Logger.w(TAG, "no recent video for the check", it) }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main) {
                runCatching { activity.startActivity(PlayerActivity.intent(activity, item)) }
                    .onFailure { Logger.w(TAG, "the check could not open the player", it) }
            }
        }
    }

    /**
     * A developer's check on a real device: launched with `-playContinue <n>`, the app opens the
     * n-th video of Continue watching in the player, as a tap on its tile would, streamed from
     * Telegram where it is not downloaded. It plays on from its saved place, which moves on.
     */
    private fun playContinueForCheck(activity: MainActivity) {
        val defaults = platform.Foundation.NSUserDefaults.standardUserDefaults
        if (defaults.objectForKey("playContinue") == null) return
        val index = defaults.integerForKey("playContinue").toInt().coerceAtLeast(0)
        com.tmplayer.App.backgroundScope.launch {
            val record = runCatching {
                Td.awaitAuthorizedSession()
                val list = com.tmplayer.data.SettingsStore(activity).continueWatching.first()
                Logger.i(TAG, "check: ${list.size} videos in Continue watching, opening #$index")
                list.getOrNull(index)
            }.onFailure { Logger.w(TAG, "no Continue watching video for the check", it) }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main) {
                runCatching { activity.startActivity(PlayerActivity.intent(activity, record.toMediaItem())) }
                    .onFailure { Logger.w(TAG, "the check could not open the player", it) }
            }
        }
    }

    /**
     * A developer's check of the player on a file of one's own (a video with test subtitle tracks,
     * say), on a simulator or a device: launched with `-playFile <name>`, a file in the app's
     * Documents (where `xcrun devicectl device copy to` puts it on a device) or a full path, the app
     * records it as a finished download of a message and a file that do not exist ([CHECK_CHAT],
     * [CHECK_FILE]) and opens it in PlayerActivity, which plays a download from its file as on
     * Android. The record stays in Downloads until removed there.
     */
    /**
     * The README's screenshots (iosApp/screenshots): in a promo build, launched with
     * `-promo "screen=media;theme=light"`, the app starts upstream's promo fixture instead of
     * MainActivity. It opens the app's real screens over an invented account, as upstream's Android
     * promo build does, with these as its launch extras (`true` and `false` as yes or no, whole
     * numbers as numbers, the rest as words). Null without the argument, or in any other build.
     */
    private fun promoForShots(): androidx.activity.ComponentActivity? {
        val asked = platform.Foundation.NSUserDefaults.standardUserDefaults.stringForKey("promo") ?: return null
        val fixture = PromoEntry.activity() ?: return null
        val intent = android.content.Intent()
        asked.split(';').map { it.trim() }.filter { '=' in it }.forEach { pair ->
            val name = pair.substringBefore('=').trim()
            val value = pair.substringAfter('=').trim()
            when {
                value == "true" || value == "false" -> intent.putExtra(name, value == "true")
                value.toIntOrNull() != null -> intent.putExtra(name, value.toIntOrNull() ?: 0)
                else -> intent.putExtra(name, value)
            }
        }
        fixture.intent = intent
        Logger.i(TAG, "promo fixture: $asked")
        return fixture
    }

    /**
     * A developer's check on a real device: launched with `-openScreen <name>`, the app opens that
     * screen once signed in, as a notification asking for it would (MainActivity's EXTRA_OPEN):
     * `downloads`, `free_up_space`, or `support` for the support card, the ones upstream opens
     * that way. Its pictures are taken from the Mac (`xcrun devicectl device capture screenshot`).
     */
    private fun openScreenForCheck(activity: MainActivity) {
        val name = platform.Foundation.NSUserDefaults.standardUserDefaults.stringForKey("openScreen") ?: return
        com.tmplayer.App.backgroundScope.launch {
            runCatching { Td.awaitAuthorizedSession() }.onFailure {
                Logger.w(TAG, "check: not signed in, $name stays closed", it)
                return@launch
            }
            // Home first, as after a tap on a notification.
            delay(3_000)
            withContext(Dispatchers.Main) {
                runCatching {
                    activity.onNewIntent(android.content.Intent().putExtra(MainActivity.EXTRA_OPEN, name))
                }.onFailure { Logger.w(TAG, "the check could not open $name", it) }
            }
        }
    }

    private fun playFileForCheck(activity: MainActivity) {
        val name = platform.Foundation.NSUserDefaults.standardUserDefaults.stringForKey("playFile") ?: return
        val documents = platform.Foundation.NSSearchPathForDirectoriesInDomains(
            platform.Foundation.NSDocumentDirectory, platform.Foundation.NSUserDomainMask, true,
        ).firstOrNull() as? String ?: return
        val file = if (name.startsWith("/")) java.io.File(name) else java.io.File("$documents/${name.substringAfterLast('/')}")
        if (!file.isFile) {
            Logger.w(TAG, "check: no file $name")
            return
        }
        val item = com.tmplayer.data.MediaItem(
            chatId = CHECK_CHAT, messageId = 1, fileId = CHECK_FILE, title = file.name, sizeBytes = file.length(),
            durationSec = 0, mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null, date = 0,
            fileName = file.name,
        )
        // `-playFileAfter <s>`: the file opens that many seconds after the start, in an app already
        // running, so its cost is measured apart from the app's own start.
        val after = platform.Foundation.NSUserDefaults.standardUserDefaults.integerForKey("playFileAfter")
        com.tmplayer.App.backgroundScope.launch {
            if (after > 0) kotlinx.coroutines.delay(after * 1000L)
            runCatching { com.tmplayer.data.SettingsStore(activity).noteDownload(item, "Check", file.path) }
                .onFailure { Logger.w(TAG, "check: could not record ${file.name}", it) }
            withContext(Dispatchers.Main) {
                runCatching { activity.startActivity(PlayerActivity.intent(activity, item)) }
                    .onFailure { Logger.w(TAG, "the check could not open the player", it) }
            }
        }
    }

    /**
     * A developer's check of the "Playback stopped, tap to resume" notice on a device, launched
     * with `-noticeCheck YES`: the app asks for iOS's permission as upstream asks for Android's, and
     * each time it goes to the background it posts the notice the player posts when the sound
     * stops out of sight ([com.tmplayer.player.PlaybackService.notifyStopped]).
     */
    private fun noticeForCheck(activity: MainActivity) {
        if (!platform.Foundation.NSUserDefaults.standardUserDefaults.boolForKey("noticeCheck")) return
        android.app.IosNotifications.ask { granted -> Logger.i(TAG, "check: notifications allowed: $granted") }
        NSNotificationCenter.defaultCenter.addObserverForName(UIApplicationDidEnterBackgroundNotification, `object` = null, queue = NSOperationQueue.mainQueue) { _ ->
            com.tmplayer.player.PlaybackService.notifyStopped(activity)
            Logger.i(TAG, "check: stopped notice posted, notifications allowed: ${android.app.IosNotifications.allowed}")
            // What iOS holds two seconds on, and where it lets the notice show.
            platform.darwin.dispatch_after(
                platform.darwin.dispatch_time(platform.darwin.DISPATCH_TIME_NOW, 2_000_000_000L),
                platform.darwin.dispatch_get_main_queue(),
            ) { android.app.IosNotifications.describe { Logger.i(TAG, "check: $it") } }
        }
    }

    /** A chat id no Telegram chat has, for [playFileForCheck]'s record. */
    private const val CHECK_CHAT = -999_999_999L

    /** A file id TDLib gives no file (the player refuses 0); the download's file plays instead. */
    private const val CHECK_FILE = 999_999_999

    /** PlayerActivity has gone: the screens come back, unless the whole app is in the background. */
    private fun playerActivityClosed() {
        playerScreen = null
        val run = main ?: return
        val state = UIApplication.sharedApplication.applicationState
        if (state == UIApplicationState.UIApplicationStateBackground) return
        run.start()
        if (state == UIApplicationState.UIApplicationStateActive) run.resume()
    }

    /** iOS's app states, as Android's activity callbacks, for the activity in front. Observed for the life of the process. */
    private fun follow(app: Application) {
        val center = NSNotificationCenter.defaultCenter
        val main = NSOperationQueue.mainQueue
        fun on(name: String?, action: () -> Unit) {
            center.addObserverForName(name, `object` = null, queue = main) { _ ->
                runCatching(action).onFailure { Logger.w(TAG, "lifecycle step failed", it) }
            }
        }
        on(UIApplicationWillEnterForegroundNotification) {
            com.tmplayer.data.IosDownloads.stopKeepingGoing()
            front()?.start()
        }
        on(UIApplicationDidBecomeActiveNotification) {
            // The app switcher's cover of a PIN profile comes off (HX88, SnapshotCover).
            SnapshotCover.uncover()
            // The viewer may have changed it in Settings while away.
            android.app.IosNotifications.refresh()
            val run = front() ?: return@on
            run.start()
            run.resume()
        }
        on(UIApplicationWillResignActiveNotification) {
            front()?.pause()
            // Inactive already shows in the app switcher's live card: every window of every scene
            // is covered now, not at the background (HX88), while a PIN profile is open.
            SnapshotCover.cover(SnapshotCover.appWindows())
        }
        on(UIApplicationDidEnterBackgroundNotification) {
            com.tmplayer.data.IosDownloads.keepGoingBriefly()
            front()?.stop()
            // Before iOS takes the app switcher's picture: covered already, unless a window came since.
            SnapshotCover.cover(SnapshotCover.appWindows())
        }

        on(UIApplicationDidReceiveMemoryWarningNotification) {
            // TMPlayer itself using half or more of what iOS allows it is the struggle upstream's
            // "struggling on this device" hint is for: the critical level raises it, as Android's
            // running-low levels do. iOS also warns the app in front when other apps fill the
            // device (an iPhone 13 with Telegram, X and Music holding about 1 GB, 2026-11-14): the
            // pictures are handed back all the same, and no hint blames TMPlayer.
            val memory = PerfProbe.memory()
            val ours = memory == null || memory.first >= memory.second
            Logger.w(TAG, "memory warning from iOS: ${PerfProbe.memoryNow()}${if (ours) "" else ", the device's, not TMPlayer's"}")
            if (ours) {
                app.onTrimMemory(Application.TRIM_MEMORY_RUNNING_CRITICAL)
            } else {
                app.onTrimMemory(Application.TRIM_MEMORY_RUNNING_MODERATE)
                com.tmplayer.data.Thumbnails.trim(everything = true)
            }
        }
    }
}
