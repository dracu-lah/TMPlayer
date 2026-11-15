package com.tmplayer.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.tmplayer.data.AddedChats
import com.tmplayer.data.ChatGate
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.ChatsGate
import com.tmplayer.data.KidsLock
import com.tmplayer.data.Td
import com.tmplayer.ui.components.UiState
import com.tmplayer.ui.onboarding.Setup
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformLatest

/**
 * Whether the chat list is whole: the sync has answered, Telegram is reachable, and no page is
 * still on its way or missing. The picker's Gone band and the mandatory chat choice both read
 * this one rule, so the two never drift apart.
 */
fun listComplete(everything: List<ChatSummary>, connected: Boolean, refreshing: Boolean, paging: ChatPaging): Boolean =
    everything.isNotEmpty() && connected && !refreshing && !paging.loading && !paging.more && !paging.partial

/**
 * The mandatory chat choice's next move ([ChatsGate.decide]) for a shell, from the stored answer,
 * the kids lock and [chatsViewModel]'s list, on the phone, the television and the desktop alike.
 *
 * Its own wait ([Setup.WAIT_FOR_CHATS_MS]) starts when the gate can first show (signed in and the
 * setup done, ON-6), not at launch: after a first sign in and the setup the list is what the
 * viewer waits for, and a timer that ran out minutes ago would let Home show every chat
 * unfiltered. A list slow to come never holds the app behind a spinner for longer than that; once
 * it has run out, the gate still shows the moment the list arrives. An answer still being read from the store counts as no move
 * at all, so a viewer who already chose never sees the picker flash up on a cold start.
 */
@Composable
fun rememberChatsGate(chatsViewModel: ChatListViewModel, setupPending: Boolean, signedIn: Boolean = true): ChatsGate.Move {
    val choice by AddedChats.choice.collectAsState()
    val loaded by AddedChats.loaded.collectAsState()
    val kids by ChatGate.allowed.collectAsState()
    val state by chatsViewModel.state.collectAsState()
    val everything by chatsViewModel.everything.collectAsState()
    val paging by chatsViewModel.paging.collectAsState()
    val refreshing by chatsViewModel.refreshing.collectAsState()
    val connected by Td.connected.collectAsState()
    val armed by rememberUpdatedState(signedIn && !setupPending)
    val waitedOut by remember {
        waitedOutFlow(snapshotFlow { armed }, Setup.WAIT_FOR_CHATS_MS)
    }.collectAsState(initial = false)
    if (!loaded || !signedIn) return ChatsGate.Move.None
    val total = remember(everything, kids) { KidsLock.chats(everything, kids).size }
    return ChatsGate.decide(
        mode = choice.mode,
        setupPending = setupPending,
        kidsLocked = kids != null,
        chatsLoaded = state is UiState.Content || everything.isNotEmpty(),
        listComplete = listComplete(everything, connected, refreshing, paging),
        total = total,
        waitedOut = waitedOut,
    )
}

/**
 * False until [armed] has been true for [ms], and false again the moment it turns false, so the
 * gate's wait (ON-6) counts from the time the chat list is first needed, again after each setup.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun waitedOutFlow(armed: Flow<Boolean>, ms: Long): Flow<Boolean> = armed.transformLatest { on ->
    emit(false)
    if (on) {
        delay(ms)
        emit(true)
    }
}
