package com.tmplayer.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.i18n.L
import com.tmplayer.ui.components.TmButton
import com.tmplayer.ui.theme.Tone

/**
 * The foot of a chat search that could not ask Telegram's word search for all of it: what was
 * searched ("only the newest 500 messages", "only what is on this device") and, while the chat goes
 * on, Keep looking. A full width line of the grid, so the remote reaches its button with Down from
 * the last row, as it reaches the archives under it.
 */
@Composable
internal fun SearchNoteRow(note: String, onDeeper: (() -> Unit)?, modifier: Modifier = Modifier) {
    // A flow, not a row: the words take the width they need and the button wraps under them when it
    // would squeeze them to a column (a large font, a narrow phone).
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(
        modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(note, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
        if (onDeeper != null) {
            TmButton(onClick = onDeeper) { Text(L.commonKeepLooking) }
        }
    }
}

/** [SearchNoteRow] as a grid line; nothing without a [note]. */
internal fun LazyGridScope.searchNoteItem(note: String?, onDeeper: (() -> Unit)?) {
    if (note == null) return
    item(key = "search-note", span = { GridItemSpan(maxLineSpan) }, contentType = "search-note") {
        SearchNoteRow(note, onDeeper)
    }
}
