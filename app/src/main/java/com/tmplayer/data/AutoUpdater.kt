package com.tmplayer.data

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import com.tmplayer.App
import com.tmplayer.cast.Cast
import com.tmplayer.cast.CastSessionState
import com.tmplayer.platform.Background
import com.tmplayer.platform.Logger
import com.tmplayer.player.PlaybackService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.io.File

/**
 * "Install updates by itself" (HX5): the GitHub build on Android 12 and later fetches and installs
 * a new version while nobody is using the app, without Android's question. The rules are
 * [AutoInstall]'s; this reads the device and runs them.
 *
 * Two things start a look, and neither repeats by itself:
 *
 * - the app going out of sight ([onBackground]): one look [AutoInstall.IDLE_MS] later, called off
 *   if the app comes back first ([onForeground]). This is what a TV has, since its jobs are not
 *   scheduled;
 * - the phone's twice daily update job ([BackgroundChecks]), scheduled while the switch is on.
 *
 * A look that decides to go records the try first, so each version is tried once however it ends
 * (a refusal, a failed download, the process killed part way), and the next try waits
 * [AutoInstall.BACKOFF_MS]. Android asking after all means the viewer is asked next time they tap
 * Update now; nobody is asked from the background.
 */
object AutoUpdater {

    private const val TAG = "AutoUpdater"

    /** The waiting look, which the app coming back calls off. Never the install itself. */
    @Volatile
    private var waiting: Job? = null

    /** When the app last went out of sight, on the clock since boot; 0 while it is in front. */
    @Volatile
    private var awaySince: Long = 0L

    private val running = Mutex()

    /** Whether this build and Android could ever do it: the GitHub build on Android 12 and later. */
    val supported: Boolean
        get() = Updates.enabled && Build.VERSION.SDK_INT >= SilentUpdateRule.ANDROID_12

    /** The app's last activity has left the screen. */
    fun onBackground(context: Context) {
        if (!supported) return
        val app = context.applicationContext
        awaySince = SystemClock.elapsedRealtime()
        waiting?.cancel()
        waiting = Background.scope.launch {
            delay(AutoInstall.IDLE_MS + 1_000L)
            // The look itself outlives this job: coming back mid-download must not cut it short.
            Background.scope.launch { runCatching { runIfDue(app) }.onFailure { Logger.w(TAG, "automatic update failed", it) } }
        }
    }

    /** One of the app's activities is on screen again. */
    fun onForeground() {
        awaySince = 0L
        waiting?.cancel()
        waiting = null
    }

    /**
     * One look: installs the offered release if [AutoInstall.decide] says so. Returns whether an
     * install was handed to Android (after which the process usually ends).
     */
    suspend fun runIfDue(context: Context): Boolean {
        if (!supported) return false
        val app = context.applicationContext
        val settings = SettingsStore(app)
        if (!settings.updateAutoInstallNow()) return false
        if (!running.tryLock()) return false
        try {
            // A fresh answer, under the update check's own six hour throttle.
            updateScheduler(app).checkIfDue(requireNotify = false)
            val offered = (Updates.state.value as? UpdateState.Available)?.release
            val now = System.currentTimeMillis()
            val decision = AutoInstall.decide(
                offered = offered?.version,
                installed = Updates.installedVersion,
                skipped = settings.updatePrefs.skippedVersion(),
                tried = AutoInstall.Tried.decode(settings.updateAutoTriedNow()),
                now = now,
                conditions = conditions(app, settings),
            )
            if (decision != AutoInstall.Decision.Go || offered == null) {
                Logger.i(TAG, "No automatic update: $decision")
                return false
            }
            settings.setUpdateAutoTried(AutoInstall.Tried(offered.version, now).encode())

            val size = Updates.apkFor(offered)?.size ?: 0L
            val dir = File(app.cacheDir, "updates")
            if (DiskSpace.read(app, app.cacheDir).freeBytes < AutoInstall.spaceNeeded(size)) {
                Logger.i(TAG, "No automatic update: not enough space")
                return false
            }
            val file = Updates.download(offered, dir, installing = true) ?: run {
                // Said nowhere: nobody is looking, and the popup offers it again.
                Updates.backToOffer()
                return false
            }
            // The viewer may have come back, or started something, while it downloaded.
            val again = AutoInstall.quiet(conditions(app, settings))
            if (again != null) {
                Logger.i(TAG, "Automatic update held back after the download: $again")
                file.delete()
                Updates.backToOffer()
                return false
            }
            return when (val result = SelfInstaller.install(app, file, offered, foreground = false)) {
                SelfInstaller.Result.Installed, SelfInstaller.Result.HandedOver -> true
                else -> {
                    Logger.i(TAG, "Automatic update did not install: $result")
                    Updates.backToOffer()
                    false
                }
            }
        } finally {
            running.unlock()
        }
    }

    private suspend fun conditions(context: Context, settings: SettingsStore): AutoInstall.Conditions {
        val away = awaySince
        return AutoInstall.Conditions(
            switchOn = settings.updateAutoInstallNow(),
            silentAllowed = SelfInstaller.canUpdateSilently(context),
            inFront = App.inFront,
            playing = WatchCache.anyPlaying() || PlaybackService.running,
            casting = Cast.existing()?.session?.value is CastSessionState.Connected,
            downloading = OfflineDownloads.active.value.values.any { it.busy } || FolderExport.busy,
            unmetered = NetworkMonitor.canTryInternet() && !NetworkMonitor.metered.value,
            powerOk = powerOk(context),
            // A look from the phone's job, with no process alive to have seen the app leave, finds
            // nothing in front, which is as idle as it gets.
            idleMs = when {
                App.inFront -> 0L
                away == 0L -> Long.MAX_VALUE
                else -> SystemClock.elapsedRealtime() - away
            },
        )
    }

    /** Charging, or the battery at [LOW_BATTERY_PERCENT] or more. A device without one is on mains. */
    private fun powerOk(context: Context): Boolean {
        val battery = context.getSystemService(BatteryManager::class.java) ?: return true
        if (battery.isCharging) return true
        val percent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        // A television reports no capacity at all (0 or Integer.MIN_VALUE).
        return percent <= 0 || percent >= LOW_BATTERY_PERCENT
    }

    private const val LOW_BATTERY_PERCENT = 30
}
