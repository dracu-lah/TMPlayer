package com.tmplayer.ui.settings

import android.content.Context
import com.tmplayer.data.BookmarkStore
import com.tmplayer.data.CrashReports
import com.tmplayer.data.DiagnosticFiles
import com.tmplayer.data.LocalDownloads
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.Td
import com.tmplayer.data.WatchedStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Everything this app knows about the account that is leaving goes: the cache and every
 * preference. TDLib clears its own database as it logs out. The downloads are files in their own
 * folder and stay, with their records, unless [deleteDownloads]: they are the viewer's, not the
 * account's. Sign out runs it; a session Telegram ended runs [forgetSession] instead.
 */
suspend fun forgetAccount(context: Context, settings: SettingsStore, watchedStore: WatchedStore, deleteDownloads: Boolean) {
    runCatching { Td.clearMediaCache() }
    // The next sign in is a new database: nothing stored with this one's file ids is resumed on it.
    runCatching { Td.renewDatabaseMarker() }
    if (deleteDownloads) {
        withContext(Dispatchers.IO) { runCatching { LocalDownloads.deleteAll(settings) } }
    }
    // Consent goes out with the preferences, so the SDK is shut down here rather than left running
    // until the next launch: somebody handing the television on must not leave a reporter switched
    // on behind them.
    runCatching { CrashReports.stop() }
    runCatching { CrashReports.discardPending() }
    runCatching { settings.clearEverything(keepDownloads = !deleteDownloads) }
    // What this account watched is its own as much as the preferences are, and it lives in a file
    // of its own, so it is cleared on its own, and so is Watch later, which shares that file.
    runCatching { watchedStore.clear() }
    runCatching { watchedStore.watchLater.clear() }
    // The bookmarks go with it, for the same reason.
    runCatching { BookmarkStore(context).clear() }
    // An upload of the backup before sign out that never finished: its copy of the lists goes too.
    runCatching { com.tmplayer.data.SavedMessagesBackup.forgetFiles() }
    // And what was kept to help with a bug report: the playback trace and its marks, the last
    // Playback test, the app log and a report waiting to be shared. They hold what was watched.
    withContext(Dispatchers.IO) { runCatching { DiagnosticFiles.forget(context) } }
    // The online subtitles picked for this account's videos, and their kept copies (HX25).
    withContext(Dispatchers.IO) {
        runCatching { com.tmplayer.online.OnlineSubtitles.current?.picks?.clear() }
        com.tmplayer.online.SubtitlePicks.wipe(java.io.File(context.filesDir, "online/picks.json"))
    }
}

/**
 * Signing out of one profile (HX88), run by `AccountSwitcher.signOutProfile` with that profile
 * open, so every store below is its own. The last profile goes as [forgetAccount] always did
 * (the device's crash report consent, logs and online subtitle picks too). With others left only
 * the account's own data goes: its keys ([SettingsStore.clearAccountData]: the theme, the player,
 * the app lock and the kids lock stay, as they are the device's), its watched list, Watch later,
 * bookmarks, TDLib's media cache, and its downloads when [deleteDownloads]. TDLib's database goes
 * with the profile's folders after the log out.
 */
suspend fun forgetProfile(
    context: Context,
    settings: SettingsStore,
    watchedStore: WatchedStore,
    deleteDownloads: Boolean,
    last: Boolean,
) {
    if (last) {
        forgetAccount(context, settings, watchedStore, deleteDownloads)
        return
    }
    runCatching { Td.clearMediaCache() }
    runCatching { Td.renewDatabaseMarker() }
    if (deleteDownloads) {
        withContext(Dispatchers.IO) { runCatching { LocalDownloads.deleteAll(settings) } }
    }
    runCatching { settings.clearAccountData(keepDownloads = !deleteDownloads) }
    runCatching { watchedStore.clear() }
    runCatching { watchedStore.watchLater.clear() }
    runCatching { BookmarkStore(context).clear() }
    runCatching { com.tmplayer.data.SavedMessagesBackup.forgetFiles() }
}

/**
 * A session Telegram ended (HX23): the viewer chose nothing, so their watched list, Watch later,
 * bookmarks and preferences stay for when they sign in again (user, 2026-10-28). Only what was
 * the old session's own goes: TDLib's media cache, and the cold start snapshot of its chats.
 */
suspend fun forgetSession(settings: SettingsStore) {
    runCatching { Td.clearMediaCache() }
    // Telegram ended the session: the database it signs in to next is a new one (HX88).
    runCatching { Td.renewDatabaseMarker() }
    runCatching { settings.clearColdStartSnapshots() }
}
