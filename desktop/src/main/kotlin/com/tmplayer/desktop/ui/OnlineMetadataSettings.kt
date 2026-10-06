package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tmplayer.i18n.Translator
import com.tmplayer.online.MetaWords
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.online.TmdbState
import com.tmplayer.ui.components.TmAlertDialog
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings, Posters and details, on the desktop: the same rows as phone and TV (the switch, the
 * TMDB key with the viewer's own as an override, the cache with its purge), drawn as this page's
 * own rows.
 */
@Composable
internal fun OnlineMetadataGroup() {
    val s = LocalStrings.current
    val online = OnlineMetadata.current ?: return
    val settings by online.settings.collectAsState()
    val state = online.tmdbState(settings)
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var editingKey by remember { mutableStateOf(false) }
    var bytes by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { bytes = withContext(Dispatchers.IO) { online.cacheBytes() } }

    Group(s.metadataTitle)
    Setting(s.metadataToggle, if (settings.enabled) s.metadataToggleOn else s.metadataToggleOff) {
        Switch(checked = settings.enabled, onCheckedChange = { online.setEnabled(it) })
    }
    Setting(
        s.metadataKey,
        MetaWords.key(state),
        titleColor = if (state == TmdbState.AppKey || state == TmdbState.OwnKey) Color.Unspecified else Tone.caution,
    ) {
        OutlinedButton(onClick = { editingKey = true }) { Text(s.commonChange) }
    }
    Setting(
        s.metadataClearCache,
        if (bytes > 0) s.metadataCacheDetail(Translator.messages.formatter.size(bytes)) else s.metadataCacheEmpty,
    ) {
        OutlinedButton(enabled = bytes > 0, onClick = {
            scope.launch {
                withContext(Dispatchers.IO) { online.purge() }
                bytes = 0
                toast(s.metadataCacheCleared)
            }
        }) { Text(s.commonClear) }
    }

    if (editingKey) MetadataKeyDialog(onClose = { editingKey = false })
}

@Composable
internal fun MetadataKeyDialog(onClose: () -> Unit) {
    val s = LocalStrings.current
    val online = OnlineMetadata.current ?: return
    var key by remember { mutableStateOf(online.store.now.ownKey) }
    TmAlertDialog(
        onDismissRequest = onClose,
        title = { Text(s.metadataKeyTitle) },
        text = {
            Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(MetaWords.keyMessage(online.buildHasKey))
                OutlinedTextField(key, { key = it }, label = { Text(s.metadataKey) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(MetaWords.key(online.tmdbState()), style = MaterialTheme.typography.bodySmall, color = Tone.muted)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                online.setOwnKey(key)
                onClose()
            }) { Text(s.commonOk) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(s.commonCancel) } },
    )
}
