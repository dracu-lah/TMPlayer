package com.tmplayer

import android.app.Application
import com.tmplayer.data.NetworkMonitor
import com.tmplayer.data.Td
import com.tmplayer.data.Thumbnails
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
        // Sized against this device's heap before anything is cached. A stick and a phone are an
        // order of magnitude apart, and one fixed figure is wrong for both.
        val memory = (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).memoryClass
        Thumbnails.sizeFor(memory)
        NetworkMonitor.start(this)
        // TDLib takes a moment to open its database; starting here means the login screen is
        // already showing a QR code by the time the user has finished reading the first line.
        Td.start(this)
        // Kept off the launch path: the ceiling is about tomorrow's disk, not this frame, and
        // TDLib has a database to open first. It waits for an authorized session, so on a device
        // nobody has signed into yet it simply never runs.
        backgroundScope.launch {
            Td.awaitAuthorizedSession()
            delay(CACHE_TRIM_DELAY_MS)
            Td.trimStorage()
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
