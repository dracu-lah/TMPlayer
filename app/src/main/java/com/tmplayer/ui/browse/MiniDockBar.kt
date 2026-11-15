package com.tmplayer.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.tmplayer.player.DockedPlayer
import com.tmplayer.player.MiniDock
import com.tmplayer.player.MiniPlayerState
import com.tmplayer.ui.components.MiniPlayerBar
import kotlinx.coroutines.delay

/** How often the bar reads the docked player's place: a thin line needs no more. */
internal const val MINI_DOCK_TICK_MS = 1_000L

/** The bar's height (its 56 dp row and the progress line), for the room lists keep under it. */
internal val MINI_DOCK_HEIGHT = 60.dp

/**
 * The phone's mini player bar (U8): the shared [MiniPlayerBar] over the docked [player]
 * ([MiniDock]). The title, the pause state and the place are read once a second, and only while
 * the shell is resumed, so nothing runs with the bar gone or the app out of sight. A tap brings
 * the player back at the same place, the X or a swipe down closes it.
 */
@Composable
internal fun MiniDockBar(player: DockedPlayer, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var title by remember(player) { mutableStateOf(runCatching { player.title }.getOrDefault("")) }
    var playing by remember(player) { mutableStateOf(runCatching { player.playing }.getOrDefault(false)) }
    var progress by remember(player) { mutableFloatStateOf(progressOf(player)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(player, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                runCatching {
                    title = player.title
                    playing = player.playing
                    progress = progressOf(player)
                }
                delay(MINI_DOCK_TICK_MS)
            }
        }
    }
    MiniPlayerBar(
        title = title,
        playing = playing,
        progress = progress,
        onExpand = { MiniDock.expand(context) },
        onTogglePlay = {
            MiniDock.togglePlay()
            // The icon answers the press now, not at the next read.
            playing = runCatching { player.playing }.getOrDefault(playing)
        },
        onDismiss = { MiniDock.dismiss() },
        modifier = modifier,
    )
}

private fun progressOf(player: DockedPlayer): Float =
    runCatching { MiniPlayerState.progress(player.positionMs, player.durationMs) }.getOrDefault(0f)
