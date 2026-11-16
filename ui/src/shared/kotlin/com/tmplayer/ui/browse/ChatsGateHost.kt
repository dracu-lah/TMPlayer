package com.tmplayer.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.launch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
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
import com.tmplayer.data.SetupFlow
import com.tmplayer.data.Td
import com.tmplayer.ui.components.UiState
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.platform.Background
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.onboarding.QuietButton
import com.tmplayer.ui.onboarding.Setup
import com.tmplayer.ui.onboarding.SetupButton
import com.tmplayer.ui.theme.Tone
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
    val waived by ChatsGate.waived.collectAsState()
    // A new account never meets the last one's waiver.
    LaunchedEffect(signedIn) { if (!signedIn) ChatsGate.clearWaiver() }
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
        waived = waived,
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

/**
 * What the gate shows during [ChatsGate.Move.Wait], the same on the phone, the television and the
 * desktop: the spinner with "Loading your chats" under it (ON-7), and after
 * [ChatsGate.WAY_OUT_MS] a quiet Continue without choosing, which waives the gate for this visit
 * (nothing is written, it asks again next start). The remote lands on the button as it appears.
 */
@Composable
fun ChatsGateWaiting(modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    var wayOut by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(ChatsGate.WAY_OUT_MS)
        wayOut = true
    }
    LaunchedEffect(wayOut) {
        if (wayOut) runCatching { focus.requestFocus() }
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(color = Tone.accent)
            Text(s.pickerLoading, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
            if (wayOut) {
                QuietButton(s.pickerContinueWithout, modifier = Modifier.focusRequester(focus), onClick = ChatsGate::waive)
            }
        }
    }
}

/**
 * The setup's next move ([SetupFlow.next]) for a shell, from the stored answer, the kids lock and
 * [chatsViewModel]'s list, on the phone, the television and the desktop alike. [replay] is Settings'
 * "Run the setup again". Unlike [rememberChatsGate] it does not wait for the chat list before
 * ending a setup whose account already answered (a sign in on a device that had one), and it asks
 * about even one chat, so a new account with only Saved Messages is told to forward a video there.
 *
 * The offline card's three seconds start when the setup is first needed, and start again after
 * each setup, so the moment of connecting after a sign in does not flash it.
 */
@Composable
fun rememberSetupFlow(
    chatsViewModel: ChatListViewModel,
    setupPending: Boolean,
    replay: Boolean,
    signedIn: Boolean = true,
): SetupFlow.Next {
    val choice by AddedChats.choice.collectAsState()
    val loaded by AddedChats.loaded.collectAsState()
    val kids by ChatGate.allowed.collectAsState()
    val state by chatsViewModel.state.collectAsState()
    val everything by chatsViewModel.everything.collectAsState()
    val connected by Td.connected.collectAsState()
    val waived by ChatsGate.waived.collectAsState()
    val armed by rememberUpdatedState(signedIn && setupPending)
    val offlineSure by remember {
        waitedOutFlow(snapshotFlow { armed }, SetupFlow.OFFLINE_AFTER_MS)
    }.collectAsState(initial = false)
    val total = remember(everything, kids) { KidsLock.chats(everything, kids).size }
    return SetupFlow.next(
        SetupFlow.Inputs(
            pending = signedIn && setupPending,
            answerLoaded = loaded,
            mode = choice.mode,
            replay = replay,
            kidsLocked = kids != null,
            waived = waived,
            chatsLoaded = state is UiState.Content || everything.isNotEmpty(),
            total = total,
            connected = connected,
            offlineSure = offlineSure,
        ),
    )
}

/**
 * What the setup shows while there is no chat list and no network ([SetupFlow.Next.Offline]), the
 * same on every platform: an icon, "You are offline" with one sentence, Try again (the connections
 * to Telegram reopened) and, as on every wait, Continue without choosing. It goes by itself the
 * moment the list arrives. The remote lands on Try again.
 */
@Composable
fun SetupOffline(modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(TmIcons.WifiOff, contentDescription = null, tint = Tone.muted, modifier = Modifier.size(40.dp))
            Text(
                s.setupOfflineTitle,
                style = MaterialTheme.typography.titleLarge,
                color = Tone.text,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(s.setupOfflineBody, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, textAlign = TextAlign.Center)
            SetupButton(
                s.commonTryAgain,
                primary = true,
                modifier = Modifier.focusRequester(focus),
                onClick = { Background.scope.launch { runCatching { Td.retryConnecting() } } },
            )
            QuietButton(s.pickerContinueWithout, onClick = ChatsGate::waive)
        }
    }
}
