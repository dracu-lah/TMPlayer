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
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.Td
import com.tmplayer.data.runCatchingCancellable

/**
 * Gives Home [LocalOfflineDownloads] while the device is offline: one read of the download index
 * when the line drops (and none while online), so the Continue watching and Watch later cards of
 * videos in Downloads can say Downloaded. It does not poll and does no work per card.
 */
@Composable
internal fun OfflineDownloadsHost(content: @Composable () -> Unit) {
    val context: Context = LocalContext.current.applicationContext
    val network by NetworkMonitor.status.collectAsStateWithLifecycle()
    val connected by Td.connected.collectAsStateWithLifecycle()
    val offline = network == NetworkStatus.Offline && !connected
    var keys by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(offline) {
        keys = if (offline) {
            runCatchingCancellable { LocalDownloads.presentIds(SettingsStore(context)) }
                .getOrDefault(emptySet())
                // [LocalDownloads.presentIds] writes MediaItem.id ("chat:message"); the cards key by progressKey.
                .mapTo(HashSet()) { it.replace(':', '_') }
        } else {
            emptySet()
        }
    }
    CompositionLocalProvider(LocalOfflineDownloads provides keys, content = content)
}
