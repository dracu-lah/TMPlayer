package com.tmplayer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.MediaMapper
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.Td
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onLoggedOut: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsStore(context) }

    var limitGb by remember { mutableStateOf(SettingsStore.DEFAULT_CACHE_LIMIT_GB) }
    var usedBytes by remember { mutableStateOf(0L) }
    var busy by remember { mutableStateOf<String?>(null) }
    val firstButton = remember { FocusRequester() }

    suspend fun refresh() {
        limitGb = settings.cacheLimitBytes().let { (it / SettingsStore.BYTES_PER_GB).toInt() }
        usedBytes = runCatching { Td.storageUsedBytes() }.getOrDefault(0L)
    }

    LaunchedEffect(Unit) {
        refresh()
        runCatching { firstButton.requestFocus() }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineLarge)

        Text("Cached video", style = MaterialTheme.typography.titleLarge)
        Text(
            "TMPlayer streams, but Telegram keeps what it has downloaded so re-watching is instant. " +
                "Using ${MediaMapper.formatSize(usedBytes).ifEmpty { "nothing" }} right now.",
            style = MaterialTheme.typography.bodyLarge,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsStore.CACHE_LIMIT_CHOICES.forEachIndexed { index, choice ->
                Button(
                    onClick = {
                        scope.launch {
                            settings.setCacheLimitGb(choice)
                            busy = "Trimming cache…"
                            runCatching { Td.trimCache(limitBytes = choice * SettingsStore.BYTES_PER_GB) }
                            refresh()
                            busy = null
                        }
                    },
                    modifier = if (index == 0) Modifier.focusRequester(firstButton) else Modifier,
                ) {
                    Text(if (choice == limitGb) "✓  $choice GB" else "$choice GB")
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            scope.launch {
                busy = "Clearing…"
                runCatching { Td.trimCache(limitBytes = 0) }
                refresh()
                busy = null
            }
        }) {
            Text("Clear cached video now")
        }

        Spacer(Modifier.height(24.dp))
        Text("Account", style = MaterialTheme.typography.titleLarge)
        Button(onClick = {
            scope.launch {
                busy = "Signing out…"
                Td.logOut()
                onLoggedOut()
            }
        }) {
            Text("Sign out of Telegram")
        }

        busy?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyLarge)
        }

        Spacer(Modifier.fillMaxWidth().height(8.dp))
        Text(
            "TMPlayer is open source and talks only to Telegram's own servers.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
