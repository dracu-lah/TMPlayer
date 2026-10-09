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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.StorageRelocationPlan
import com.tmplayer.data.Td
import com.tmplayer.data.WatchedStore
import com.tmplayer.desktop.DesktopPaths
import com.tmplayer.desktop.DownloadIndex
import com.tmplayer.ui.i18n.LocalStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Signing out (B6, Decision E5). Downloads are files in the viewer's own folder now, not cache, so
 * they stay unless the box is ticked; favourites, history, the watched list and the cache go as before.
 */
@Composable
internal fun SignOutDialog(state: ShellState, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    val settings = state.settings
    val scope = rememberCoroutineScope()
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
            scope.launch {
                signOut(settings, deleteDownloads, state.watched)
                state.showChats(com.tmplayer.ui.browse.BrowseSection.of(com.tmplayer.ui.browse.BrowseTab.All))
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
internal suspend fun signOut(settings: SettingsStore, deleteDownloads: Boolean, watched: WatchedStore? = null) {
    val downloads = runCatching { settings.downloadsNow() }.getOrDefault(emptyList())
    if (deleteDownloads) downloads.forEach { runCatching { DownloadIndex.delete(settings, it) } }
    runCatching { Td.clearMediaCache() }
    runCatching { settings.clearEverything(keepDownloads = !deleteDownloads) }
    // What this account watched is its own as much as the preferences are, and it lives in a
    // file of its own, so it is cleared on its own.
    watched?.let { runCatching { it.clear() } }
}
