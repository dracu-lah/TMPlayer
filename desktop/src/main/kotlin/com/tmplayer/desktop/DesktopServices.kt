package com.tmplayer.desktop

import com.tmplayer.data.CacheRule
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.UpdateScheduler
import com.tmplayer.data.WatchedStore
import java.io.File

/**
 * The desktop's process-wide services, each opened exactly once.
 *
 * DataStore refuses two instances over one file in a process, so the settings store is a single
 * lazy value that every screen and the download runner share.
 */
object DesktopServices {
    val settings: SettingsStore by lazy {
        SettingsStore(SettingsStore.openDataStore(DesktopPaths.settingsFile))
    }

    /**
     * What has been watched to the end, in a file of its own beside the settings file: the list
     * grows with use, and clearing it must never be one stray edit away from the preferences.
     */
    val watched: WatchedStore by lazy {
        WatchedStore(WatchedStore.openDataStore(File(DesktopPaths.settingsFile.parentFile, WatchedStore.FILE_NAME)))
    }

    val downloads: DesktopDownloadRunner by lazy { DesktopDownloadRunner(settings, connectivity = DesktopConnectivity) }

    /** Volume, decoder, wheel and update choices: the settings the phone does not have. */
    /**
     * What streaming leaves on the disk: claimed by the player, swept at launch, kept under the
     * cache limit with the least recently played going first.
     */
    val watchCache: DesktopWatchCache by lazy {
        DesktopWatchCache(
            settings = settings,
            filesRoot = { DesktopPaths.filesDir },
            playing = { com.tmplayer.desktop.player.ActiveStreams.openIds() },
            rule = { CacheRule.UnderCap(prefs.now.cacheLimitBytes) },
        )
    }

    val prefs: DesktopPrefs by lazy { DesktopPrefs(DesktopPaths.prefsFile) }

    /** When to ask whether a newer version is out, and the popup's once per version memory. */
    val updates: UpdateScheduler by lazy { UpdateScheduler(settings.updatePrefs) }

    /** Fetches and installs a release over this copy, where the way it was installed allows. */
    val selfUpdate: SelfUpdate by lazy { SelfUpdate() }
}
