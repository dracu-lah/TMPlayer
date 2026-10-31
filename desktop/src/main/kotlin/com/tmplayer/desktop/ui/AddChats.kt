package com.tmplayer.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.tmplayer.ui.browse.AddChatsFed
import com.tmplayer.ui.browse.AddChatsRequest
import com.tmplayer.ui.browse.AddChatsState
import com.tmplayer.ui.browse.ChatListViewModel
import com.tmplayer.ui.browse.newAddChatsViewModel

/**
 * The Add chats picker (HX27) over the desktop window while [AddChatsState.open]: a full page like
 * the Devices page, reached from Settings, an empty Chats page ([AddChatsRequest]) or Home. Esc
 * closes it through the picker's own Discard question, and Ctrl+A (Cmd+A) ticks every row shown.
 */
@Composable
internal fun AddChatsPage(state: ShellState, chats: ChatListViewModel) {
    // Asked for from a screen that cannot open it: opened here, once.
    val asked by AddChatsRequest.pending.collectAsState()
    LaunchedEffect(asked) {
        if (AddChatsRequest.take()) AddChatsState.open = true
    }
    // The window moving to another account or closing takes the page with it.
    DisposableEffect(Unit) { onDispose { AddChatsState.open = false } }
    if (!AddChatsState.open) return
    // One view model per opening, so a closed picker never comes back with its old ticks.
    val model = rememberViewModel(Unit) { newAddChatsViewModel(state.settings) }
    CoverWindowControls()
    AddChatsFed(
        settings = state.settings,
        chatsViewModel = chats,
        model = model,
        onClose = { AddChatsState.open = false },
        sendLogs = { SendLogsActions() },
    )
}
