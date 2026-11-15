package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.player.MiniContext
import com.tmplayer.ui.components.MiniPlayerBar

/** What the docked bar shows of the video, written by [com.tmplayer.desktop.PlayerHost] while docked. */
class MiniBarInfo(val title: String, val playing: Boolean, val progress: Float)

/**
 * What the player host needs of the shell for the mini player (U8): whether it is docked, the
 * way to dock or close on Back, to hand the bar its title and progress, and to answer the bar's
 * play and pause. The shell owns the state ([ShellState.mini]); the player stays where it is.
 */
class MiniLink(private val shell: ShellState) {
    val docked: Boolean get() = shell.mini.phase == com.tmplayer.player.MiniPhase.Docked

    fun leave(context: MiniContext) = shell.leavePlayer(context)

    /**
     * A docked video ended (the bar goes), or failed or asked a question ([needsAnswer]: the
     * player comes back up to show it).
     */
    fun stopped(needsAnswer: Boolean) {
        if (needsAnswer) shell.expandPlayer() else shell.closePlayer()
    }

    var info: MiniBarInfo?
        get() = shell.miniBar
        set(value) {
            shell.miniBar = value
        }

    var togglePlay: () -> Unit
        get() = shell.miniTogglePlay
        set(value) {
            shell.miniTogglePlay = value
        }
}

/** How much room the bar takes at the foot, for what else lives there (the notices) to step over. */
val MiniBarLift = 72.dp

/**
 * The mini player's bar over the page, above the floating bar ([NavFrame]) or a chat's own.
 * Drawn only while docked and not casting ([com.tmplayer.player.MiniPlayerState.barVisible]).
 */
@Composable
fun BoxScope.MiniBarHost(state: ShellState, foot: Dp) {
    if (!state.mini.barVisible) return
    val info = state.miniBar
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .padding(start = 24.dp, end = 24.dp, bottom = foot)
            .widthIn(max = 560.dp),
    ) {
        MiniPlayerBar(
            title = info?.title.orEmpty().ifBlank { state.nowPlaying?.item?.title.orEmpty() },
            playing = info?.playing ?: true,
            progress = info?.progress ?: 0f,
            onExpand = state::expandPlayer,
            onTogglePlay = { state.miniTogglePlay() },
            onDismiss = state::closePlayer,
        )
    }
}
