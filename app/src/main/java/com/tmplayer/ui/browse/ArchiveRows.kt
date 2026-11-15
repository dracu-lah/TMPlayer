package com.tmplayer.ui.browse

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.ArchiveFile
import com.tmplayer.i18n.L
import com.tmplayer.i18n.Translator
import com.tmplayer.ui.components.TvChoiceRow
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Tone

/*
 * A chat's compressed files at the foot of its listing: what TMPlayer met among the chat's
 * documents and cannot play, each with why, so a chat of archives is not a chat that looks empty.
 * Only archives named like a video or large enough to be one (Archives.worthListing): a chat that
 * lists its videos (user, 2026-10-19) is not buried under backups and mod packs. Shown again from
 * 2026-11-15 (U10), greyed, with the reason and what to do.
 */

/** The heading over the archives: how many, and, in a chat with no videos at all, that too. */
@Composable
private fun ArchivesHeading(count: Int, noVideos: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = if (noVideos) 8.dp else 24.dp, bottom = 4.dp)) {
        if (noVideos) {
            Text(L.browseNoPlayableVideos, style = MaterialTheme.typography.titleMedium, color = Tone.text)
        }
        Text(L.gridArchivesHeading(count), style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
    }
}

/**
 * One archive: its name, its kind and size, and the reason it is not played. A press says the
 * reason again as a message, so a press on a television is answered, never a dead key.
 */
@Composable
private fun ArchiveRow(file: ArchiveFile, onExplain: (String) -> Unit, modifier: Modifier = Modifier) {
    val size = file.sizeBytes.takeIf { it > 0 }?.let { Translator.messages.formatter.bytes(it) }
    TvChoiceRow(
        title = file.fileName.ifBlank { file.kind.label },
        detail = file.reason,
        trailing = listOfNotNull(file.kind.label, size).joinToString(" · "),
        icon = TmIcons.Archive,
        // Greyed: it is listed so a chat of archives is not read as empty, but it cannot be played.
        modifier = modifier.alpha(0.78f),
        onClick = { onExplain(file.reason) },
    )
}

/** The archives as full width grid lines; [first] goes on the first when there is nothing above. */
internal fun LazyGridScope.archiveItems(
    archives: List<ArchiveFile>,
    noVideos: Boolean,
    first: FocusRequester?,
    onExplain: (String) -> Unit,
) {
    if (archives.isEmpty()) return
    item(key = "archives-heading", span = { GridItemSpan(maxLineSpan) }) { ArchivesHeading(archives.size, noVideos) }
    archives.forEachIndexed { index, file ->
        item(key = "archive-${file.messageId}", span = { GridItemSpan(maxLineSpan) }) {
            ArchiveRow(file, onExplain, if (index == 0 && first != null) Modifier.focusRequester(first) else Modifier)
        }
    }
}

/** The same in a list of rows. */
internal fun LazyListScope.archiveItems(
    archives: List<ArchiveFile>,
    noVideos: Boolean,
    first: FocusRequester?,
    onExplain: (String) -> Unit,
) {
    if (archives.isEmpty()) return
    item(key = "archives-heading") { ArchivesHeading(archives.size, noVideos) }
    archives.forEachIndexed { index, file ->
        item(key = "archive-${file.messageId}") {
            ArchiveRow(file, onExplain, if (index == 0 && first != null) Modifier.focusRequester(first) else Modifier)
        }
    }
}
