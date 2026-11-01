package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.StorageRelocationPlan
import com.tmplayer.data.Td
import com.tmplayer.data.WatchedStore
import com.tmplayer.data.BookmarkStore
import com.tmplayer.desktop.DesktopPaths
import com.tmplayer.desktop.DownloadIndex
import com.tmplayer.ui.i18n.LocalStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Signing out (B6, Decision E5). Downloads are files in the viewer's own folder now, not cache, so
 * they stay unless the box is ticked; favourites, history, the watched list and the cache go as before.
 */
@Composable
internal fun SignOutDialog(state: ShellState, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    val settings = state.settings
    var alsoDownloads by remember { mutableStateOf(false) }
    var size by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(Unit) {
        size = withContext(Dispatchers.IO) { DownloadIndex.bytesOnDisk(runCatching { settings.downloadsNow() }.getOrDefault(emptyList())) }
    }
    // The shared prompt, so Cancel holds focus here as it does before every other deletion.
    ConfirmDialog(
        title = s.signoutTitle,
        message = s.signoutBody(DesktopPaths.downloadsDir.path),
        confirmLabel = s.signoutConfirm,
        onDismiss = onDismiss,
        onConfirm = {
            onDismiss()
            val deleteDownloads = alsoDownloads
            // On the process's scope, not this dialog's: closing the dialog cancelled its scope
            // part way, every step's runCatching swallowed the cancel, nothing was cleared and the
            // logout itself was never sent, so the window sat on Connecting for good.
            com.tmplayer.platform.Background.scope.launch {
                signOut(settings, deleteDownloads, state.watched, state.bookmarks)
                withContext(Dispatchers.Main) {
                    state.showChats(com.tmplayer.ui.browse.BrowseSection.of(com.tmplayer.ui.browse.BrowseTab.Chats))
                }
                Td.logOut()
            }
        },
        extra = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = alsoDownloads, onCheckedChange = { alsoDownloads = it })
                Text(if (size > 0) s.commonWithDetail(s.signoutDeleteDownloads, StorageRelocationPlan.size(size)) else s.signoutDeleteDownloads)
            }
        },
    )
}

/**
 * Everything this app knows about the account that is leaving goes, except the downloads that
 * have a file of their own, which stay in the index unless [deleteDownloads].
 */
internal suspend fun signOut(
    settings: SettingsStore,
    deleteDownloads: Boolean,
    watched: WatchedStore? = null,
    bookmarks: BookmarkStore? = null,
) {
    val downloads = runCatching { settings.downloadsNow() }.getOrDefault(emptyList())
    if (deleteDownloads) downloads.forEach { runCatching { DownloadIndex.delete(settings, it) } }
    // TDLib's logOut destroys its own files as well, so a large cache is not waited on for long.
    runCatching { withTimeoutOrNull(CLEAR_CACHE_MS) { Td.clearMediaCache() } }
    // Consent was the leaving account's to give: reporting stops, and a crash waiting to be asked
    // about goes, as on the phone. clearEverything turns the switch itself off.
    runCatching { com.tmplayer.desktop.DesktopCrashReports.stop() }
    runCatching { com.tmplayer.desktop.DesktopCrashReports.discardPending() }
    runCatching { settings.clearEverything(keepDownloads = !deleteDownloads) }
    // What this account watched is its own as much as the preferences are, and it lives in a
    // file of its own, so it is cleared on its own, and so is Watch later, which shares it.
    watched?.let {
        runCatching { it.clear() }
        runCatching { it.watchLater.clear() }
    }
    // The bookmarks go with it, for the same reason.
    bookmarks?.let { runCatching { it.clear() } }
}

/** How long signing out waits for the video cache to be cleared before logging out regardless. */
private const val CLEAR_CACHE_MS = 5_000L
