package com.tmplayer.ui.settings

import android.content.Context
import com.tmplayer.data.BookmarkStore
import com.tmplayer.data.CrashReports
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
}

/**
 * A session Telegram ended (HX23): the viewer chose nothing, so their watched list, Watch later,
 * bookmarks and preferences stay for when they sign in again (user, 2026-10-28). Only what was
 * the old session's own goes: TDLib's media cache, and the cold start snapshot of its chats.
 */
suspend fun forgetSession(settings: SettingsStore) {
    runCatching { Td.clearMediaCache() }
    runCatching { settings.clearColdStartSnapshots() }
}
