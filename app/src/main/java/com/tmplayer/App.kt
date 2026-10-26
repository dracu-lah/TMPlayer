package com.tmplayer

import android.app.Application
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.tmplayer.data.AndroidLogSink
import com.tmplayer.data.AppLockSession
import com.tmplayer.data.ChatGate
import com.tmplayer.data.AppLocales
import com.tmplayer.data.CrashReports
import com.tmplayer.data.LocalDownloads
import com.tmplayer.data.NetworkMonitor
import com.tmplayer.data.RemoveAfterWatching
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.Td
import com.tmplayer.data.start
import com.tmplayer.data.Thumbnails
import com.tmplayer.data.trim
import com.tmplayer.data.SupportReminder
import com.tmplayer.data.Updates
import com.tmplayer.data.configureForAndroid
import com.tmplayer.data.WatchCache
import com.tmplayer.data.WatchedStore
import com.tmplayer.i18n.Translator
import com.tmplayer.ui.onboarding.SetupChoice
import com.tmplayer.ui.onboarding.SetupImages
import com.tmplayer.ui.onboarding.TourPage
import com.tmplayer.online.MetadataCache
import com.tmplayer.online.MetadataStore
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.platform.Logger
import com.tmplayer.player.PlayerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.tmplayer.online.OnlineSubtitles
import com.tmplayer.online.OnlineSubtitlesStore
import com.tmplayer.online.SubtitleCache

class App : Application() {

    // The player activity is Media3 unstable API; priming its orientation is only a static call.
    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // First of all, so a crash anywhere below is written down for the next start to offer.
        CrashReports.watch(this)
        // Before anything reads them: a television trades looks for speed (see [Motion.television]).
        com.tmplayer.ui.theme.Motion.television = com.tmplayer.data.FormFactor.isTv(this)
        com.tmplayer.data.SettingsDefaults.evenVolume = !com.tmplayer.data.FormFactor.isTv(this)
        // The shared code logs through a facade; on Android it goes to logcat as it always has.
        Logger.sink = AndroidLogSink
        // The clock Home's first paint is timed against (adb logcat -s tm-perf).
        com.tmplayer.data.PerfLog.processStart
        Updates.configureForAndroid()
        Updates.enabled = BuildConfig.UPDATE_CHECK
        SupportReminder.enabled = BuildConfig.SUPPORT_LINKS
        // The UI language: the Settings choice, else the system's list, else English. Before the
        // promo return, so the screenshot fixture speaks whatever the emulator is set to. Only a
        // debug build may pick the en-XA pseudo-locale.
        Translator.pseudoEnabled = BuildConfig.DEBUG
        // Android 13 and later also keep a per-app language of their own, which AppLocales holds
        // in step with this one in both directions.
        backgroundScope.launch {
            val store = SettingsStore(this@App)
            AppLocales.adopt(this@App, store.language.first())?.let { store.setLanguage(it) }
            store.language.collect { saved ->
                Translator.select(saved, AppLocales.system(this@App))
                AppLocales.apply(this@App, saved)
                // The chosen language's locale data, loaded here rather than in a frame.
                runCatching { Translator.messages.formatter.warmUp() }
            }
        }
        // The system's first load of a locale's data (numbers, dates) is heavy, and on a 1 GB
        // television a frame that did it on the main thread was caught waiting on a garbage
        // collection long enough for an ANR (Sentry 156073936, HX2). The device's own locale (what
        // String.format and the platform's views use) and the UI language are loaded here, off
        // the main thread, once; the first frames then find them in the platform's cache.
        backgroundScope.launch {
            runCatching {
                val began = System.nanoTime()
                String.format(java.util.Locale.getDefault(), "%d %.1f", 1, 1.5)
                java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date())
                Translator.messages.formatter.warmUp()
                // Next to Home's first paint in the same log (adb logcat -s tm-perf).
                com.tmplayer.data.PerfLog.mark("locale data warmed", began)
                com.tmplayer.data.PerfLog.sinceStart("locale data warmed")
            }
        }
        // A card in focus or under the finger warms its stream, unless the file is whole here
        // already (a download, the watch cache) or is pieces played joined. Not on a television:
        // moving the focus along a row there started two video downloads ahead of every poster
        // and thumbnail, and on the TV sticks and boxes the pictures stopped arriving (1.28.0).
        // Pressing Play still warms its own video at once ([MainActivity]'s playNow).
        val focusWarms = !com.tmplayer.data.FormFactor.isTv(this)
        // The cards then leave out their focus watcher and timer altogether (warmUpOnIntent).
        com.tmplayer.data.PlaybackWarmup.onIntent = focusWarms
        com.tmplayer.data.PlaybackWarmup.warmer = com.tmplayer.data.PlaybackWarmup.Warmer { item ->
            if (!focusWarms || item.isJoined || item.fileId <= 0 || item.locality == com.tmplayer.data.MediaItem.Locality.Downloaded) return@Warmer
            backgroundScope.launch {
                val local = runCatching { com.tmplayer.data.Td.localFileAvailability(item.fileId) }.getOrNull()
                // From its saved place, if it has one: where Continue watching opens it.
                if (local != com.tmplayer.data.LocalFileAvailability.Complete) {
                    com.tmplayer.player.StreamWarmup.warm(item) { SettingsStore(this@App).resumeStartFor(item) }
                }
            }
        }
        // The stream warm-up stays out of "Save mobile data" on a metered line, whatever asks for
        // it, and in Low storage mode brings the head and the index only
        // ([com.tmplayer.player.StreamWarmup]).
        backgroundScope.launch {
            val store = SettingsStore(this@App)
            val tv = com.tmplayer.data.FormFactor.isTv(this@App)
            kotlinx.coroutines.flow.combine(store.lowStorageMode, store.saveMobileData) { low, save -> low to save }
                .collect { (low, save) ->
                    val saving = com.tmplayer.data.MeteredPolicy.saveMobileDataInForce(save, tv)
                    com.tmplayer.player.StreamWarmup.lowStorage = { low }
                    com.tmplayer.player.StreamWarmup.allowed = { !(saving && NetworkMonitor.metered.value) }
                }
        }
        // Lighter pictures and Reduce motion (CP68), from the stored choice and whenever it changes.
        // Before the promo return, so the fixture shows a screen as a low-end device draws it.
        backgroundScope.launch {
            val store = SettingsStore(this@App)
            // First, so the first frame finds the interface size without reading the file itself.
            launch { runCatching { com.tmplayer.ui.theme.primeUiScale(store.uiScale.first()) } }
            launch { store.lighterPictures.collect { Thumbnails.light = it } }
            launch { store.reduceMotion.collect { com.tmplayer.ui.theme.Motion.reduced = it } }
            // "Vibrate on tap" covers the app's toggles as well as the player's.
            launch { store.touchPrefs.collect { com.tmplayer.ui.components.Micro.haptics = it.haptics } }
        }
        // The setup's pictures are this app's drawables, which the shared :ui code cannot name.
        SetupImages.drawable = ::setupDrawable
        SetupImages.tour = ::tourDrawable
        // The PIN's waits run on time since boot, deep sleep included, which no date setting moves.
        com.tmplayer.data.PinClock.source = { android.os.SystemClock.elapsedRealtime() }
        // Before anything else that could throw: a crash reporter that starts after the crash is
        // worth nothing. It reads one flag off the disk on a background thread and stays inert
        // unless that flag is true, so no SDK is initialised and no endpoint is contacted on a
        // device where the viewer has not asked for this. Before the promo return too, which has
        // no Telegram in it, so the crash question can be tried in the fixture (CP102c), and the
        // review always runs, since MainActivity may be holding its shell back for its answer.
        if (CrashReports.available) {
            backgroundScope.launch {
                val enabled = runCatching { SettingsStore(this@App).crashReportsNow() }.getOrDefault(false)
                runCatching { CrashReports.start(this@App, enabled) }
                runCatching { CrashReports.review(this@App, enabled) }
            }
        }
        // The isolated screenshot fixture must never open TDLib or touch a Telegram account.
        // BuildConfig is variant-specific, and the promo package is never part of a release APK.
        if (BuildConfig.APPLICATION_ID.endsWith(".promo")) return
        // The kids lock (CP57), read once here for every list of chats in the process.
        ChatGate.follow(SettingsStore(this))
        com.tmplayer.data.HiddenChats.follow(SettingsStore(this))
        // The viewer's corrections to names and groups, read once here for every screen that
        // arranges a chat.
        com.tmplayer.data.VideoCorrections.follow(SettingsStore(this))
        // "Ask when the app opens": the app is opened again once its last activity has left the
        // screen. Counted across activities, so the player starting in front of the browse screen
        // (and the browse screen stopping behind it) does not count as leaving.
        registerActivityLifecycleCallbacks(LockOnLeave())
        // Online subtitles: the build's OpenSubtitles key, the viewer's account in private files,
        // and the cache under the app's cache. A build without a key gets an instance that says
        // so (inBuild false), and nothing of the feature is drawn.
        OnlineSubtitles.current = OnlineSubtitles(
            apiKey = BuildConfig.OPENSUBTITLES_API_KEY,
            store = OnlineSubtitlesStore(java.io.File(filesDir, "online/account.properties")),
            cache = SubtitleCache(java.io.File(cacheDir, "online-subtitles")),
            appVersion = BuildConfig.VERSION_NAME,
        ).also { online -> backgroundScope.launch { runCatching { online.cache.prune() } } }
        // Sized against this device's heap before anything is cached. A stick and a phone are an
        // order of magnitude apart, and one fixed figure is wrong for both.
        val memory = (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).memoryClass
        Thumbnails.sizeFor(memory)
        // Posters and overviews: the build's TMDB key, the switches in private files, the answers
        // and pictures under the app's cache. Off until the viewer turns it on in Settings.
        // Home as it was last built, read now so its first frame finds it in memory (HomeCache).
        com.tmplayer.data.HomeCache.file = java.io.File(filesDir, "home/home-cache.json")
        backgroundScope.launch { runCatching { com.tmplayer.data.HomeCache.preload() } }
        // And the chats opened most recently, so a cold start opens one of them at once (PageQueries).
        com.tmplayer.data.PageQueries.store = com.tmplayer.data.ChatGridDisk(java.io.File(filesDir, "home/chat-grids.json"))
        backgroundScope.launch { runCatching { com.tmplayer.data.PageQueries.preload() } }
        OnlineMetadata.current = OnlineMetadata(
            buildKey = BuildConfig.TMDB_API_KEY,
            store = MetadataStore(java.io.File(filesDir, "online/metadata.properties")),
            cache = MetadataCache(java.io.File(cacheDir, "online-metadata")),
            appVersion = BuildConfig.VERSION_NAME,
            omdbKey = BuildConfig.OMDB_API_KEY,
        ).also { metadata -> backgroundScope.launch { runCatching { metadata.warmUp() }; runCatching { metadata.cache.prune() } } }
        Thumbnails.connectivity = NetworkMonitor
        NetworkMonitor.start(this)
        // TDLib takes a moment to open its database; starting here means the login screen is
        // already showing a QR code by the time the user has finished reading the first line.
        Td.start(this)
        // Which pictures this device plays smoothly (HX2): the copy chooser and the player ask it.
        com.tmplayer.player.DeviceDecode.start(this)
        // Which way up the player opens, fetched now so it is in hand before anybody reaches a
        // video. The player has to set its orientation before it lays anything out, which leaves
        // no room for a disk read there. Anyone quick enough to beat this gets the default.
        backgroundScope.launch {
            runCatching {
                PlayerActivity.primeOrientation(SettingsStore(this@App).screenOrientationNow())
            }
        }
        // "Remove after watching". Here, for the life of the process, rather than on a screen or in
        // the player: a video is marked watched from the grid, from the player's own button and by
        // the player reaching the end, and all three write the one list this reads.
        backgroundScope.launch {
            val settings = SettingsStore(this@App)
            RemoveAfterWatching(
                enabled = settings.removeAfterWatching,
                watched = WatchedStore(this@App).watched,
                downloads = { settings.downloadsNow() },
                delete = { LocalDownloads.deleteAnyKind(settings, it) },
            ).run()
        }
        // The checks a phone makes while the app is closed (new videos in starred chats, the
        // sponsor reminder), scheduled or cancelled to match the settings.
        backgroundScope.launch { runCatching { com.tmplayer.data.BackgroundChecks.schedule(this@App) } }
        // Kept off the launch path: the storage ceiling is about tomorrow's disk, not this frame,
        // and TDLib has a database to open first. Waits for an authorized session, so on a device
        // nobody has signed into yet it never runs.
        backgroundScope.launch {
            Td.awaitAuthorizedSession()
            delay(CACHE_TRIM_DELAY_MS)
            Td.trimStorage()
            // The videos, which the trim above cannot touch because it cannot tell a download
            // from a cache. This can. See [WatchCache.sweep].
            runCatching { WatchCache.sweep(this@App) }
        }
    }

    /** Locks [AppLockSession] when no activity of this app is started any more. */
    private class LockOnLeave : ActivityLifecycleCallbacks {
        private var started = 0

        override fun onActivityStarted(activity: android.app.Activity) {
            started++
            inFront = true
            // Back in view: TDLib reopens a line it lost in the background at once (Td).
            Td.onForegroundChanged(true)
        }

        override fun onActivityStopped(activity: android.app.Activity) {
            started = (started - 1).coerceAtLeast(0)
            inFront = started > 0
            // A rotation stops and starts the same activity; it is not the app being left.
            if (started == 0 && !activity.isChangingConfigurations) {
                AppLockSession.lock()
                Td.onForegroundChanged(false)
            }
        }

        override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = Unit
        override fun onActivityResumed(activity: android.app.Activity) = Unit
        override fun onActivityPaused(activity: android.app.Activity) = Unit
        override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
        override fun onActivityDestroyed(activity: android.app.Activity) = Unit
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The bitmap cache is the only thing in this process holding memory it can give back
        // without losing anything; everything else is either TDLib's or on screen.
        Thumbnails.trim(level)
        // The system running short while the app is in front is the struggle the one-time hint
        // is for (CP68); the shell decides whether there is anything to offer.
        if (level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL) {
            com.tmplayer.data.DeviceFitHint.raise()
        }
    }

    companion object {
        /**
         * For work that has to finish even as a screen is going away (saving where playback
         * stopped, trimming the cache), where a lifecycle scope would be cancelled too early.
         */
        val backgroundScope: CoroutineScope = com.tmplayer.platform.Background.scope

        /**
         * Whether one of this app's activities is on screen, for the background checks: a new
         * videos or sponsor notification is not posted over the app itself.
         */
        @Volatile
        var inFront: Boolean = false
            private set

        /** Long enough to be past the first screen and whatever it wanted off the disk. */
        private const val CACHE_TRIM_DELAY_MS = 20_000L
    }
}

/**
 * The picture of a setup answer: a cropped, marked shot of the part of the app it changes. A
 * television gets its own (dark, as a television mostly is); a phone gets light or dark to match
 * the theme. The storage step draws its own picture, so it has none.
 */
private fun setupDrawable(choice: SetupChoice, tv: Boolean, dark: Boolean): Int = when (choice) {
    SetupChoice.PostersOn -> when {
        tv -> R.drawable.setup_posters_on_tv
        dark -> R.drawable.setup_posters_on_dark
        else -> R.drawable.setup_posters_on
    }
    SetupChoice.PostersOff -> when {
        tv -> R.drawable.setup_posters_off_tv
        dark -> R.drawable.setup_posters_off_dark
        else -> R.drawable.setup_posters_off
    }
    SetupChoice.KeepWhilePlaying, SetupChoice.LowStorage, SetupChoice.SaveData, SetupChoice.NoLimits,
    SetupChoice.ApplyRecommended, SetupChoice.NotNow, SetupChoice.CrashReportsOff, SetupChoice.CrashReportsOn -> 0
    SetupChoice.FullQuality -> when {
        tv -> R.drawable.setup_performance_full_tv
        dark -> R.drawable.setup_performance_full_dark
        else -> R.drawable.setup_performance_full
    }
    SetupChoice.Lighter -> when {
        tv -> R.drawable.setup_performance_lighter_tv
        dark -> R.drawable.setup_performance_lighter_dark
        else -> R.drawable.setup_performance_lighter
    }
}

/**
 * A quick tour page's picture (CP69), named in full so resource shrinking keeps each one. The phone
 * player has a dark look of its own, so its light and dark pictures are the same shot. The TV home
 * screen row is a television page only.
 */
private fun tourDrawable(page: TourPage, tv: Boolean, dark: Boolean): Int = when (page) {
    TourPage.Videos -> when {
        tv -> R.drawable.tour_videos_tv
        dark -> R.drawable.tour_videos_dark
        else -> R.drawable.tour_videos
    }
    TourPage.Home -> when {
        tv -> R.drawable.tour_home_tv
        dark -> R.drawable.tour_home_dark
        else -> R.drawable.tour_home
    }
    TourPage.Player -> when {
        tv -> R.drawable.tour_player_tv
        dark -> R.drawable.tour_player_dark
        else -> R.drawable.tour_player
    }
    TourPage.Controls -> when {
        tv -> R.drawable.tour_controls_tv
        dark -> R.drawable.tour_controls_dark
        else -> R.drawable.tour_controls
    }
    TourPage.Downloads -> when {
        tv -> R.drawable.tour_downloads_tv
        dark -> R.drawable.tour_downloads_dark
        else -> R.drawable.tour_downloads
    }
    TourPage.TvHome -> if (tv) R.drawable.tour_tvhome_tv else 0
    TourPage.Settings -> when {
        tv -> R.drawable.tour_settings_tv
        dark -> R.drawable.tour_settings_dark
        else -> R.drawable.tour_settings
    }
}
