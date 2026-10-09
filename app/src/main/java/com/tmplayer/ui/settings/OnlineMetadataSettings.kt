package com.tmplayer.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.ui.text.input.KeyboardType
import com.tmplayer.i18n.Translator
import com.tmplayer.online.MetaWords
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.online.TmdbState
import com.tmplayer.ui.auth.PaneField
import com.tmplayer.ui.components.TmIcons
import com.tmplayer.ui.components.TvConfirm
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings, Posters and details: the switch (off until the viewer turns it on), the TMDB key with
 * the viewer's own as an override, and the cache with its purge. Drawn in every build that set the
 * feature up; without a TMDB key of the build's own, the key row says to add one.
 */
internal fun LazyListScope.onlineMetadataSection(onEditKey: () -> Unit) {
    if (OnlineMetadata.current == null) return
    item { SectionTitle(LocalStrings.current.metadataTitle) }
    item { MetadataToggleRow() }
    item { MetadataKeyRow(onEditKey) }
    item { MetadataCacheRow() }
}

@Composable
private fun MetadataToggleRow() {
    val s = LocalStrings.current
    val online = OnlineMetadata.current ?: return
    val settings by online.settings.collectAsState()
    ToggleRow(
        title = s.metadataToggle,
        subtitle = if (settings.enabled) s.metadataToggleOn else s.metadataToggleOff,
        icon = TmIcons.Image,
        checked = settings.enabled,
        onToggle = { online.setEnabled(!settings.enabled) },
    )
}

@Composable
private fun MetadataKeyRow(onEditKey: () -> Unit) {
    val s = LocalStrings.current
    val online = OnlineMetadata.current ?: return
    val settings by online.settings.collectAsState()
    val state = online.tmdbState(settings)
    val trouble = state == TmdbState.NoKey || state == TmdbState.AppKeyRefused || state == TmdbState.OwnKeyRefused
    ActionRow(
        title = s.metadataKey,
        subtitle = MetaWords.key(state),
        icon = Icons.Filled.Lock,
        tint = if (trouble) Tone.caution else Color.Unspecified,
        onClick = onEditKey,
    )
}

@Composable
private fun MetadataCacheRow() {
    val s = LocalStrings.current
    val online = OnlineMetadata.current ?: return
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var bytes by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { bytes = withContext(Dispatchers.IO) { online.cacheBytes() } }
    var asking by remember { mutableStateOf(false) }
    val clear: () -> Unit = {
        scope.launch {
            withContext(Dispatchers.IO) { online.purge() }
            bytes = 0
            toast(s.metadataCacheCleared)
        }
    }
    ActionRow(
        title = s.metadataClearCache,
        subtitle = if (bytes > 0) s.metadataCacheDetail(Translator.messages.formatter.size(bytes)) else s.metadataCacheEmpty,
        icon = Icons.Filled.Delete,
        onClick = { if (bytes > 0) asking = true else clear() },
    )
    if (asking) {
        TvConfirm(
            title = s.confirmMetadataCacheTitle,
            message = s.confirmMetadataCacheMessage(Translator.messages.formatter.size(bytes)),
            confirmLabel = s.commonClear,
            onConfirm = {
                asking = false
                clear()
            },
            onDismiss = { asking = false },
        )
    }
}

/** The viewer's own TMDB key, over Settings. Empty goes back to the build's. */
@Composable
fun MetadataKeyDialog(onClose: () -> Unit) {
    val s = LocalStrings.current
    val online = OnlineMetadata.current ?: return onClose()
    var key by remember { mutableStateOf(online.store.now.ownKey) }
    TvConfirm(
        title = s.metadataKeyTitle,
        message = MetaWords.keyMessage(online.buildHasKey),
        detail = MetaWords.key(online.tmdbState()),
        confirmLabel = s.commonOk,
        destructive = false,
        icon = Icons.Filled.Lock,
        onDismiss = onClose,
        onConfirm = {
            online.setOwnKey(key)
            onClose()
        },
        extra = {
            PaneField(
                value = key,
                onValueChange = { key = it },
                placeholder = s.metadataKey,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
            )
        },
    )
}
