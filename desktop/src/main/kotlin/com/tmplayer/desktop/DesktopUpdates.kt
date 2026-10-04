package com.tmplayer.desktop

import com.tmplayer.data.SettingsStore
import com.tmplayer.data.UpdateFeed
import com.tmplayer.data.Updates
import com.tmplayer.desktop.os.OsInfo

/**
 * The desktop's part in the shared update check: what this build is, which releases are news on
 * this OS, and moving the old desktop only settings across. The feed, the version order, the state
 * and the schedule are `:core`'s ([UpdateFeed], [Updates], [com.tmplayer.data.UpdateScheduler]);
 * installing is [SelfUpdate]'s.
 */
object DesktopUpdates {

    /** Once, before anything reads [Updates.state]. */
    fun configure(os: String = OsInfo.osTag) = Updates.configure(
        installedVersion = BuildInfo.VERSION,
        userAgent = "TMPlayer-desktop/${BuildInfo.VERSION}",
        // A release made for the phone alone is no news on a computer.
        offers = { UpdateFeed.hasDesktopPackage(it, os) },
    )

    /**
     * 1.19 kept the update settings in `desktop.properties`; they now live in the shared store.
     * A release the old notice was closed for becomes a skipped one, which is what it meant.
     */
    suspend fun migrate(prefs: DesktopPrefs, settings: SettingsStore) {
        prefs.migrateUpdatePrefs { legacy ->
            legacy.notify?.let { settings.setUpdateNotify(it) }
            if (legacy.lastCheck > 0) settings.updatePrefs.setLastCheck(legacy.lastCheck)
            if (legacy.dismissed.isNotBlank()) settings.updatePrefs.setSkippedVersion(legacy.dismissed)
        }
    }
}
