package com.tmplayer.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.tmplayer.i18n.Translator
import com.tmplayer.online.MetaWords
import com.tmplayer.online.OmdbState
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.online.TmdbState
import com.tmplayer.ui.auth.PaneField
import com.tmplayer.ui.components.TvConfirm
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings, Posters and details: the switch (off until the viewer turns it on), the TMDB key with
 * the viewer's own as an override, the OMDb key the same way, and the cache with its purge. Drawn in every build that set the
 * feature up; without a TMDB key of the build's own, the key row says to add one.
 */
internal fun LazyListScope.onlineMetadataSection(
    onEditKey: () -> Unit,
    onEditOmdbKey: () -> Unit,
    toggleModifier: Modifier = Modifier,
) {
    if (OnlineMetadata.current == null) return
    sectionTitle { LocalStrings.current.metadataTitle }
    // Keyed, so a row above that removes itself (Library's Clear history, WP-06) does not dispose
    // these with their positions and drop the remote it has just handed to the posters switch.
    item(key = "metadata_toggle") { MetadataToggleRow(toggleModifier) }
    item(key = "metadata_trailers") { TrailersInAppToggleRow() }
    item(key = "metadata_skip_times") { SkipTimesToggleRow() }
    // The four kinds of skip are one Skip buttons row in Playback now (WP-08); this switch and its
    // privacy line stay with the lookups they ride on.
    item(key = "metadata_tmdb_key") { MetadataKeyRow(onEditKey) }
    item(key = "metadata_omdb_key") { OmdbKeyRow(onEditOmdbKey) }
    item(key = "metadata_cache") { MetadataCacheRow() }
}

@Composable
private fun MetadataToggleRow(modifier: Modifier) {
    val s = LocalStrings.current
    val online = OnlineMetadata.current ?: return
    val settings by online.settings.collectAsState()
    ToggleRow(
        title = s.metadataToggle,
        modifier = modifier,
        subtitle = if (settings.enabled) s.metadataToggleOn else s.metadataToggleOff,
        icon = TmIcons.Image,
        checked = settings.enabled,
        onToggle = { online.setEnabled(!settings.enabled) },
    )
}

/**
 * Trailers in TMPlayer's own player (YouTube's embed, loaded only once a trailer is chosen), or
 * in the YouTube app and the browser. A trailer needs lookups on, but the switch stays there with
 * them off, as the skip buttons' does, so the choice is not lost while lookups are off.
 */
@Composable
private fun TrailersInAppToggleRow() {
    val s = LocalStrings.current
    val online = OnlineMetadata.current ?: return
    val settings by online.settings.collectAsState()
    ToggleRow(
        title = s.metadataTrailersInApp,
        subtitle = s.metadataTrailersInAppDetail,
        icon = TmIcons.Trailer,
        service = com.tmplayer.ui.components.Service.YouTube,
        checked = settings.trailersInApp,
        onToggle = { online.setTrailersInApp(!settings.trailersInApp) },
    )
}

/**
 * The player's skip buttons. The online lists ride on lookups (the title they are asked about is the
 * one the lookup matched), but chapters in the file and the viewer's own marks need no lookup, so
 * the switch is there with lookups off too; the one line under it says what is sent.
 */
@Composable
private fun SkipTimesToggleRow() {
    val s = LocalStrings.current
    val online = OnlineMetadata.current ?: return
    val settings by online.settings.collectAsState()
    ToggleRow(
        title = s.metadataSkipTimes,
        subtitle = s.metadataSkipTimesPrivacy,
        icon = TmIcons.Episodes,
        checked = settings.skipTimes,
        onToggle = { online.setSkipTimes(!settings.skipTimes) },
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
        icon = TmIcons.Lock,
        tint = if (trouble) Tone.caution else Color.Unspecified,
        // The caution tint is the signal while the key is in trouble, so the logo waits.
        service = if (trouble) null else com.tmplayer.ui.components.Service.Tmdb,
        onClick = onEditKey,
    )
}

/** The OMDb key, for IMDb's score: the build's, the viewer's own, or none (Wikidata's scores alone). */
@Composable
private fun OmdbKeyRow(onEdit: () -> Unit) {
    val s = LocalStrings.current
    val online = OnlineMetadata.current ?: return
    val settings by online.settings.collectAsState()
    val state = online.omdbState(settings)
    ActionRow(
        title = s.metadataOmdbKey,
        subtitle = MetaWords.omdbKey(state),
        icon = TmIcons.Lock,
        tint = if (state == OmdbState.OwnKeyRefused) Tone.caution else Color.Unspecified,
        service = if (state == OmdbState.OwnKeyRefused) null else com.tmplayer.ui.components.Service.Omdb,
        onClick = onEdit,
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
        icon = TmIcons.Delete,
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
    val field = remember { FocusRequester() }
    TvConfirm(
        title = s.metadataKeyTitle,
        message = MetaWords.keyMessage(online.buildHasKey),
        detail = MetaWords.key(online.tmdbState()),
        confirmLabel = s.commonOk,
        destructive = false,
        icon = TmIcons.Lock,
        focusField = field,
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
                modifier = Modifier.fillMaxWidth().focusRequester(field),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            )
        },
    )
}

/** The viewer's own OMDb key, over Settings. Empty goes back to the build's. */
@Composable
fun OmdbKeyDialog(onClose: () -> Unit) {
    val s = LocalStrings.current
    val online = OnlineMetadata.current ?: return onClose()
    var key by remember { mutableStateOf(online.store.now.omdbKey) }
    val field = remember { FocusRequester() }
    TvConfirm(
        title = s.metadataOmdbKeyTitle,
        message = MetaWords.omdbKeyMessage(online.buildHasOmdbKey),
        detail = MetaWords.omdbKey(online.omdbState()),
        confirmLabel = s.commonOk,
        destructive = false,
        icon = TmIcons.Lock,
        focusField = field,
        onDismiss = onClose,
        onConfirm = {
            online.setOmdbKey(key)
            onClose()
        },
        extra = {
            PaneField(
                value = key,
                onValueChange = { key = it },
                placeholder = s.metadataOmdbKey,
                modifier = Modifier.fillMaxWidth().focusRequester(field),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            )
        },
    )
}
