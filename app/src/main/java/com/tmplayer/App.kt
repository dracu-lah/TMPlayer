package com.tmplayer

import android.app.Application
import com.tmplayer.data.CrashReports
import com.tmplayer.data.NetworkMonitor
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.Td
import com.tmplayer.data.Thumbnails
import com.tmplayer.data.WatchCache
import com.tmplayer.player.PlayerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // The isolated screenshot fixture must never open TDLib or touch a Telegram account.
        // BuildConfig is variant-specific, and the promo package is never part of a release APK.
        if (BuildConfig.APPLICATION_ID.endsWith(".promo")) return
        // Before anything else that could throw: a crash reporter that starts after the crash is
        // worth nothing. It reads one flag off the disk on a background thread and stays inert
        // unless that flag is true, so no SDK is initialised and no endpoint is contacted on a
        // device where the viewer has not asked for this.
        if (CrashReports.available) {
            backgroundScope.launch {
                runCatching {
                    CrashReports.start(this@App, SettingsStore(this@App).crashReportsNow())
                }
            }
        }
        // Sized against this device's heap before anything is cached. A stick and a phone are an
        // order of magnitude apart, and one fixed figure is wrong for both.
        val memory = (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).memoryClass
        Thumbnails.sizeFor(memory)
        NetworkMonitor.start(this)
        // TDLib takes a moment to open its database; starting here means the login screen is
        // already showing a QR code by the time the user has finished reading the first line.
        Td.start(this)
        // Which way up the player opens, fetched now so it is in hand before anybody reaches a
        // video. The player has to set its orientation before it lays anything out, which leaves
        // no room for a disk read there. Anyone quick enough to beat this gets the default.
        backgroundScope.launch {
            runCatching {
                PlayerActivity.primeOrientation(SettingsStore(this@App).screenOrientationNow())
            }
        }
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

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The bitmap cache is the only thing in this process holding memory it can give back
        // without losing anything; everything else is either TDLib's or on screen.
        Thumbnails.trim(level)
    }

    companion object {
        /**
         * For work that has to finish even as a screen is going away (saving where playback
         * stopped, trimming the cache), where a lifecycle scope would be cancelled too early.
         */
        val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Long enough to be past the first screen and whatever it wanted off the disk. */
        private const val CACHE_TRIM_DELAY_MS = 20_000L
    }
}
