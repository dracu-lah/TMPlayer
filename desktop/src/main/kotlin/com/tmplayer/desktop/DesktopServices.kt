package com.tmplayer.desktop

import com.tmplayer.data.SettingsStore

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

    val downloads: DesktopDownloadRunner by lazy { DesktopDownloadRunner(settings) }

    /** Volume, decoder, wheel and update choices: the settings the phone does not have. */
    /** What streaming leaves on the disk: claimed by the player, swept at launch. */
    val watchCache: DesktopWatchCache by lazy {
        DesktopWatchCache(settings, DesktopPaths.filesDir, playing = { com.tmplayer.desktop.player.ActiveStreams.openIds() })
    }

    val prefs: DesktopPrefs by lazy { DesktopPrefs(java.io.File(DesktopPaths.configDir, "desktop.properties")) }
}
