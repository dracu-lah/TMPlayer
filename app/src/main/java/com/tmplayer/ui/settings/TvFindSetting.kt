package com.tmplayer.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.DeviceForm
import com.tmplayer.data.SettingsRow
import com.tmplayer.data.SettingsSearch
import com.tmplayer.ui.auth.PaneField
import com.tmplayer.ui.components.ChoiceLine
import com.tmplayer.ui.components.ChoiceList
import com.tmplayer.ui.components.ChoiceSheet
import com.tmplayer.ui.components.FocusOnOpen
import com.tmplayer.ui.components.rememberVoiceSearch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Tone

/**
 * Find a setting, on a television (WP-09): the panel the header's button opens. A field that takes
 * the system keyboard (Gboard for TV brings its own microphone) and, where a recogniser exists, a
 * "Search by voice" line first; under it the rows that match, each with the page it lives on. OK on
 * one opens its page on the row ([onPick]); Back closes the panel and the remote goes back to the
 * button. An empty query shows the rows people come for most ([SettingsSearch.suggestions]), so
 * the panel is never a blank page. [query] is kept by the caller while a result's page is open.
 */
@Composable
internal fun TvFindSettingPanel(
    query: String,
    onQuery: (String) -> Unit,
    rows: List<SettingsRow>,
    onPick: (SettingsRow) -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalStrings.current
    val field = remember { FocusRequester() }
    val text: (String) -> String = { key -> s.searchText(key, touch = false) }
    val hits = if (query.isBlank()) {
        SettingsSearch.suggestions(rows, DeviceForm.Tv, text)
    } else {
        SettingsSearch.match(query, rows, DeviceForm.Tv, text)
    }
    ChoiceSheet(title = s.settingsFindSetting, onDismiss = onClose) {
        PaneField(
            value = query,
            onValueChange = { onQuery(it.take(MAX_FIND_QUERY)) },
            placeholder = s.settingsSearch,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).focusRequester(field),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {}),
        )
        Results(query, hits, onQuery, onPick)
        FocusOnOpen(field)
    }
}

@Composable
private fun ColumnScope.Results(
    query: String,
    hits: List<SettingsSearch.Hit>,
    onQuery: (String) -> Unit,
    onPick: (SettingsRow) -> Unit,
) {
    val s = LocalStrings.current
    // Where a recogniser exists the mic is a line of its own; without one nothing is drawn.
    val voice = rememberVoiceSearch(s.settingsFindVoicePrompt, onKeyboard = null, onResult = { heard -> onQuery(heard.take(MAX_FIND_QUERY)) })
    ChoiceList {
        if (voice != null) {
            ChoiceLine(title = s.browseSearchByVoice, icon = TmIcons.Mic, onClick = voice)
        }
        if (query.isNotBlank() && hits.isEmpty()) {
            Text(
                s.settingsSearchNone(query.trim()),
                style = MaterialTheme.typography.bodyMedium,
                color = Tone.muted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        hits.forEach { hit ->
            ChoiceLine(title = hit.title, detail = hit.page, icon = TmIcons.Search, onClick = { onPick(hit.row) })
        }
    }
}

/** A query longer than any title is somebody's paste, not a search. */
private const val MAX_FIND_QUERY = 80

internal const val FIND_BUTTON_TAG = "settings-find"
