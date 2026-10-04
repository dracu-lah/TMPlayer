package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.StorageRelocationPlan
import com.tmplayer.data.Td
import com.tmplayer.desktop.DesktopPaths
import com.tmplayer.desktop.DownloadIndex
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Signing out (B6, Decision E5). Downloads are files in the viewer's own folder now, not cache, so
 * they stay unless the box is ticked; favourites, history and the cache go as before.
 */
@Composable
internal fun SignOutDialog(state: ShellState, onDismiss: () -> Unit) {
    val settings = state.settings
    val scope = rememberCoroutineScope()
    var alsoDownloads by remember { mutableStateOf(false) }
    var size by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(Unit) {
        size = withContext(Dispatchers.IO) { DownloadIndex.bytesOnDisk(runCatching { settings.downloadsNow() }.getOrDefault(emptyList())) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign out of Telegram?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "You'll be signed out and taken back to the sign in screen. Your favourites and everything " +
                        "you were part way through go with it. Downloads stay in ${DesktopPaths.downloadsDir.path}.",
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = alsoDownloads, onCheckedChange = { alsoDownloads = it })
                    Text(if (size > 0) "Also delete my downloads (${StorageRelocationPlan.size(size)})" else "Also delete my downloads")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                val deleteDownloads = alsoDownloads
                scope.launch {
                    signOut(settings, deleteDownloads)
                    state.go(Destination.Chats)
                    Td.logOut()
                }
            }) { Text("Sign out", color = Tone.danger) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Everything this app knows about the account that is leaving goes, except the downloads that
 * have a file of their own, which are written back after the wipe unless [deleteDownloads].
 */
internal suspend fun signOut(settings: SettingsStore, deleteDownloads: Boolean) {
    val downloads = runCatching { settings.downloadsNow() }.getOrDefault(emptyList())
    if (deleteDownloads) downloads.forEach { runCatching { DownloadIndex.delete(settings, it) } }
    runCatching { Td.clearMediaCache() }
    runCatching { settings.clearEverything() }
    if (!deleteDownloads) {
        // Oldest first, so the list keeps its order: each write is stamped with the time.
        downloads.filter { it.localPath != null }.sortedBy { it.updatedAt }.forEach { record ->
            runCatching { settings.noteDownload(record.toMediaItem(), record.chatTitle, record.localPath) }
        }
    }
}
