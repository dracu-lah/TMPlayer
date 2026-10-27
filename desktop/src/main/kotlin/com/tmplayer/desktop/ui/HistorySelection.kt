package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.data.HistoryEdits
import com.tmplayer.data.HistoryUndo
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.WatchedStore
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * History's removals on the desktop (Remove from history, Clear history, Remove from Watch later),
 * each done at once and said with Undo in the window's snackbar. Undo puts back exactly what
 * went, places and order included ([HistoryEdits]).
 */
internal fun runHistoryEdit(
    scope: CoroutineScope,
    state: ShellState,
    undoToast: (message: String, action: String, onAction: () -> Unit) -> Unit,
    toast: (String) -> Unit,
    said: (Int) -> String,
    undoLabel: String,
    work: suspend (SettingsStore, WatchedStore) -> HistoryUndo,
) {
    // The process's scope: the page, and the tile the menu was on, may leave with the card.
    com.tmplayer.platform.Background.scope.launch {
        val undo = runCatching { work(state.settings, state.watched) }.getOrElse { e ->
            toast(com.tmplayer.data.Failures.humanise(e))
            return@launch
        }
        if (undo.isEmpty) return@launch
        undoToast(said(undo.count), undoLabel) {
            scope.launch { runCatching { HistoryEdits.undo(state.settings, state.watched, undo) }.onFailure { toast(com.tmplayer.data.Failures.humanise(it)) } }
        }
    }
}

/**
 * Ctrl+A (Cmd+A on a Mac) ticks every card on a History page, Delete removes the ticked ones.
 * Either is left to whatever has focus when it does not apply.
 */
internal fun Modifier.historyKeys(selection: GridSelection, allIds: () -> List<String>, onRemove: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val chord = event.isCtrlPressed || event.isMetaPressed
        when {
            chord && event.key == Key.A -> {
                val all = allIds()
                if (all.isEmpty()) return@onPreviewKeyEvent false
                selection.selectAll(all)
                true
            }
            !chord && (event.key == Key.Delete || event.key == Key.Backspace && event.isMetaPressed) && selection.active -> {
                onRemove()
                true
            }
            else -> false
        }
    }

/**
 * The bar over a History page while cards are ticked: how many, Select all (Select none once all
 * are), Remove (n) and Done.
 */
@Composable
internal fun HistorySelectionBar(
    selection: GridSelection,
    allIds: List<String>,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val count = selection.ids.count { it in allIds }
    val all = count == allIds.size
    Surface(shape = MaterialTheme.shapes.large, color = Tone.surfaceHigh, tonalElevation = 6.dp, modifier = modifier) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(s.browseSelectedCount(count), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { if (all) selection.clear() else selection.selectAll(allIds) }) {
                Text(if (all) s.browseSelectNone else s.browseSelectAll)
            }
            TextButton(onClick = { selection.clear() }) { Text(s.commonDone) }
            Button(onClick = onRemove, enabled = count > 0) { Text(s.browseRemoveCount(count)) }
        }
    }
}

/** A History card's tick box, round at the art's top start corner: filled when [ticked]. */
@Composable
internal fun TickBox(ticked: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val s = LocalStrings.current
    androidx.compose.foundation.layout.Box(
        modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(if (ticked) Tone.accent else Color.Black.copy(alpha = 0.55f))
            .border(2.dp, Color.White, CircleShape)
            .semantics { role = Role.Checkbox; stateDescription = if (ticked) s.browseBadgeSelected else "" }
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (ticked) Icon(TmIcons.Check, contentDescription = s.browseBadgeSelected, tint = Tone.onAccent, modifier = Modifier.size(16.dp))
    }
}
