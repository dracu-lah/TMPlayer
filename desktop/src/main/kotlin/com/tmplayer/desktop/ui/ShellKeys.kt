package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tmplayer.i18n.L
import com.tmplayer.ui.components.TmAlertDialog
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Tone

/**
 * The shell's keys, for the "?" and F1 sheet and the Settings row "Keyboard shortcuts" (WP-51),
 * spelled for this OS: Cmd where a Mac's command key is, Ctrl elsewhere. The player has its own
 * sheet over the video ([com.tmplayer.desktop.player.PlayerKeys.sheetGroups]). Every row here is
 * a key [ShellState.onPreviewKey] or [ShellState.onKey] answers; ShellKeysTest reads the table.
 */
internal object ShellKeys {

    fun groups(mac: Boolean): List<Pair<String, List<Pair<String, String>>>> {
        val cmd = if (mac) L.keysCmd else L.keysCtrl
        return listOf(
            L.keysShellGroupGo to listOf(
                L.keysShellRowHomeChats to "$cmd+1, $cmd+2",
                L.keysShellRowSettings to "$cmd+,",
                L.keysShellRowDownloads to "$cmd+J",
                L.keysShellRowChooseChats to "$cmd+Shift+A",
                L.keysRowBack to L.keysBackKeys,
            ),
            L.keysShellGroupFind to listOf(
                L.keysShellRowSearchPage to "$cmd+F, /",
                L.keysShellRowSearchChats to "$cmd+K",
            ),
            L.keysShellGroupLists to listOf(
                L.keysShellRowDelete to if (mac) "Delete, $cmd+Backspace" else "Delete",
                L.keysShellRowRename to "F2",
            ),
            L.keysGroupWindow to listOf(
                L.keysRowFullscreen to "F11",
                L.keysShellRowRefresh to "F5, $cmd+R",
                L.keysShellRowInterfaceSize to "$cmd+=, $cmd+-, $cmd+0",
                L.keysRowQuit to "$cmd+Q",
                L.keysRowThisSheet to "?, F1",
            ),
        )
    }
}

/** The sheet itself, a dialog like the others, scrolling when the window is short. */
@Composable
internal fun ShellShortcutsDialog(onClose: () -> Unit, mac: Boolean = com.tmplayer.desktop.os.OsInfo.isMac) {
    val s = LocalStrings.current
    TmAlertDialog(
        onDismissRequest = onClose,
        title = { Text(s.keysShellTitle) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ShellKeys.groups(mac).forEach { (name, rows) ->
                    Column {
                        Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp).semantics { heading() })
                        rows.forEach { (what, keys) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Text(what, modifier = Modifier.weight(1f).padding(end = 12.dp))
                                Text(keys, color = Tone.muted)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(s.commonClose) } },
    )
}
