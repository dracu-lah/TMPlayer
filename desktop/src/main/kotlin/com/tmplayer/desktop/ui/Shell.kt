package com.tmplayer.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.dp
import com.tmplayer.data.AuthState
import com.tmplayer.desktop.BuildInfo
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.tmplayer.desktop.InstallKind
import com.tmplayer.desktop.UpdateProgress
import com.tmplayer.ui.components.UiState
import com.tmplayer.desktop.os.OpenExternal
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import com.tmplayer.data.Td
import com.tmplayer.ui.browse.ChatListViewModel
import com.tmplayer.ui.components.AppLogo
import com.tmplayer.ui.components.LocalToastHost
import com.tmplayer.ui.components.TmIcons
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.nav.LocalBackStack
import com.tmplayer.ui.theme.Tone

/**
 * What the player draws for a [PlayRequest], given a way to close itself.
 *
 * The player lives in `com.tmplayer.desktop.player` and is passed in from `Main.kt`; until then
 * the shell draws [PlayerPlaceholder].
 */
typealias PlayerContent = @Composable (request: PlayRequest, onClose: () -> Unit) -> Unit

/**
 * The desktop window's content (B2.1): sign in until Telegram says Ready, then a sidebar above
 * 1000 dp of width (an icon rail below it), the page, and the player over the page when something
 * is playing.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DesktopShell(
    state: ShellState,
    player: PlayerContent = { request, onClose -> PlayerPlaceholder(request, onClose) },
) {
    val toasts = remember { SnackbarHostState() }
    CompositionLocalProvider(
        LocalBackStack provides state.backStack,
        LocalToastHost provides toasts,
    ) {
        Surface(
            Modifier
                .fillMaxSize()
                // The mouse's back button, wherever the pointer is.
                .onPointerEvent(PointerEventType.Press) { event ->
                    if (event.button == PointerButton.Back) state.backStack.handleBack()
                },
            color = Tone.background,
        ) {
            val auth by Td.auth.collectAsState()
            Box(Modifier.fillMaxSize()) {
                if (auth == AuthState.Ready) {
                    Browse(state, player)
                } else {
                    SignInScreen(auth)
                }
                SnackbarHost(toasts, Modifier.align(Alignment.BottomCenter).padding(16.dp))
            }
        }
    }
}

@Composable
private fun Browse(state: ShellState, player: PlayerContent) {
    // One chat list for the session, the same view model the phone uses. Its store is cleared when
    // the account goes, which cancels everything it was loading.
    val chats = rememberViewModel(Unit) { ChatListViewModel(state.settings) }
    DisposableEffect(chats) { onDispose { chats.reset() } }
    LaunchedEffect(Unit) { chats.refreshIfStale() }
    // "Open the last chat on launch": once the list has it, open it, once per sign in.
    LaunchedEffect(Unit) {
        val target = runCatching { state.settings.autoOpenTarget() }.getOrNull() ?: return@LaunchedEffect
        val chat = chats.state
            .mapNotNull { (it as? UiState.Content)?.value?.chats?.firstOrNull { chat -> chat.id == target } }
            .first()
        if (state.openChat == null && state.destination == Destination.Chats) state.openChat(chat)
    }

    // Housekeeping once signed in: the strays streaming left behind, after the first screen has
    // settled, and the once a day look for a newer version.
    LaunchedEffect(Unit) {
        delay(HOUSEKEEPING_DELAY_MS)
        runCatching { state.extras.watchCache?.sweep() }
    }
    LaunchedEffect(Unit) { runCatching { state.extras.updates?.checkIfDue() } }

    BackHandler(enabled = state.openChat != null) { state.closeChat() }
    BackHandler(enabled = state.openChat == null && state.destination != Destination.Chats) {
        state.go(Destination.Chats)
    }

    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= SIDEBAR_FROM
            Row(Modifier.fillMaxSize()) {
                if (wide) Sidebar(state) else Rail(state)
                VerticalDivider(color = Tone.outline)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    val open = state.openChat
                    when {
                        open != null -> MediaGridPage(state, open)
                        else -> when (state.destination) {
                            Destination.Chats -> ChatsPage(state, chats, favouritesOnly = false)
                            Destination.Favourites -> ChatsPage(state, chats, favouritesOnly = true)
                            Destination.Continue -> ContinuePage(state)
                            Destination.Downloads -> DownloadsPage(state)
                            Destination.Settings -> SettingsPage(state, BuildInfo.VERSION)
                        }
                    }
                }
            }
        }

        val updates = state.extras.updates
        val release = updates?.available?.collectAsState()?.value
        if (release != null && state.nowPlaying == null) {
            val selfUpdate = state.extras.selfUpdate
            val progress = selfUpdate?.progress?.collectAsState()?.value ?: UpdateProgress.Idle
            val scope = rememberCoroutineScope()
            UpdateNotice(
                release = release,
                canUpdate = selfUpdate?.canUpdateTo(release) == true,
                kind = selfUpdate?.kind ?: InstallKind.Manual,
                progress = progress,
                onUpdate = { scope.launch { selfUpdate?.update(release) } },
                onRestart = { selfUpdate?.restart() },
                onDownload = {
                    OpenExternal.browse(release.pageUrl)
                    updates.dismiss()
                },
                onDismiss = {
                    selfUpdate?.reset()
                    updates.dismiss()
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
            )
        }

        // Over the page rather than instead of it, so the page keeps its scroll position and its
        // search, and closing the player is simply taking this away.
        val playing = state.nowPlaying
        if (playing != null) {
            Box(Modifier.fillMaxSize().background(Tone.background)) {
                BackHandler { state.closePlayer() }
                player(playing) { state.closePlayer() }
            }
        }
    }
}

private val SIDEBAR_FROM = 1000.dp
private const val HOUSEKEEPING_DELAY_MS = 20_000L

private fun Destination.icon(): ImageVector = when (this) {
    Destination.Chats -> Icons.Filled.Home
    Destination.Favourites -> Icons.Filled.Star
    Destination.Continue -> Icons.Filled.PlayArrow
    Destination.Downloads -> TmIcons.Download
    Destination.Settings -> Icons.Filled.Settings
}

@Composable
internal fun Sidebar(state: ShellState) {
    Column(
        Modifier.width(240.dp).fillMaxHeight().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(AppLogo.Mark, contentDescription = null, modifier = Modifier.size(28.dp))
            Text("TMPlayer", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(8.dp))
        Destination.entries.forEach { destination ->
            NavigationDrawerItem(
                label = { Text(destination.label) },
                icon = { Icon(destination.icon(), contentDescription = null) },
                selected = state.destination == destination,
                onClick = { state.go(destination) },
            )
        }
    }
}

@Composable
private fun Rail(state: ShellState) {
    NavigationRail(containerColor = Tone.background) {
        Image(
            AppLogo.Mark,
            contentDescription = null,
            modifier = Modifier.padding(vertical = 12.dp).size(28.dp),
        )
        Destination.entries.forEach { destination ->
            NavigationRailItem(
                selected = state.destination == destination,
                onClick = { state.go(destination) },
                icon = { Icon(destination.icon(), contentDescription = destination.label) },
                label = { Text(destination.label.substringBefore(' ')) },
            )
        }
    }
}
