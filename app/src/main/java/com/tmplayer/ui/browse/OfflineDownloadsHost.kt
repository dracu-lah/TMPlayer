package com.tmplayer.ui.browse

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.data.LocalDownloads
import com.tmplayer.data.NetworkMonitor
import com.tmplayer.data.NetworkStatus
import com.tmplayer.data.OfflineHome
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.Td
import com.tmplayer.data.runCatchingCancellable

/**
 * Gives every screen [LocalOfflineHome] (R4): whether the line is down, held for 2 s each way so a
 * flaky link never flashes (see [com.tmplayer.data.OfflineSettle]), and while it is, one read of
 * the download index. It does not poll and does no work per card; Home adds the watch cache's
 * answer for its own cards ([OfflineHomeScope]).
 */
@Composable
internal fun OfflineDownloadsHost(content: @Composable () -> Unit) {
    val context: Context = LocalContext.current.applicationContext
    val network by NetworkMonitor.status.collectAsStateWithLifecycle()
    val connected by Td.connected.collectAsStateWithLifecycle()
    val offline = rememberSettledOffline(network == NetworkStatus.Offline && !connected)
    var downloads by remember { mutableStateOf<List<ResumeRecord>>(emptyList()) }
    // False until the index has been read for this offline stretch: Home holds its rows till then.
    var read by remember { mutableStateOf(false) }
    LaunchedEffect(offline) {
        read = false
        downloads = if (offline) {
            runCatchingCancellable { LocalDownloads.presentRecords(SettingsStore(context)) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        read = true
    }
    val home = remember(offline, downloads, read) { OfflineHome(offline, downloads, settled = !offline || read) }
    CompositionLocalProvider(LocalOfflineHome provides home, content = content)
}
