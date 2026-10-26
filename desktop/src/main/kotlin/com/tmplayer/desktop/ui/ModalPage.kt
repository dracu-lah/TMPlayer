package com.tmplayer.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.components.LocalBottomChrome
import com.tmplayer.ui.components.rememberLessMotion
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.floatingBorder

/** The panel's size inside a window; [full] when it takes the whole window. */
internal data class ModalSize(val width: Dp, val height: Dp, val full: Boolean)

private val MODAL_MAX_WIDTH = 1040.dp
private val MODAL_MAX_HEIGHT = 820.dp
private val MODAL_MARGIN = 40.dp

/** Below this window the panel would be a thin ring of scrim, so it fills the window (as the detail sheet does). */
private val MODAL_FULL_BELOW_WIDTH = 1100.dp
private val MODAL_FULL_BELOW_HEIGHT = 700.dp

internal fun modalSize(windowWidth: Dp, windowHeight: Dp): ModalSize =
    if (windowWidth < MODAL_FULL_BELOW_WIDTH || windowHeight < MODAL_FULL_BELOW_HEIGHT) {
        ModalSize(windowWidth, windowHeight, full = true)
    } else {
        ModalSize(
            minOf(MODAL_MAX_WIDTH, windowWidth - MODAL_MARGIN * 2),
            minOf(MODAL_MAX_HEIGHT, windowHeight - MODAL_MARGIN * 2),
            full = false,
        )
    }

/**
 * Settings and Downloads (the destinations [Destination.isModal] names) as a centred panel over a
 * scrim, with the page they were opened from still drawn under it. The routing is unchanged: the
 * panel is open exactly while [ShellState.destination] is one of them. Esc, Back, a click on the
 * scrim and the page's own Back close it ([ShellState.closeModal], through Shell's back handler),
 * Tab stays inside it, and focus goes back to the page on the way out. A video playing draws over
 * it and takes the keys; the panel (and what was picked in it) waits underneath.
 *
 * Not used in the big-screen mode, where the TV's bar must stay reachable ([ShellState.modalOpen]).
 */
@Composable
fun ModalPageHost(state: ShellState, content: @Composable () -> Unit) {
    val open = state.modalOpen
    val still = rememberLessMotion()
    val entry = remember { FocusRequester() }
    val fade = tween<Float>(Motion.Normal)

    // Focus back on the page the panel was opened from, once it has gone.
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (wasOpen && !open && state.openChat == null && state.nowPlaying == null) {
            androidx.compose.runtime.withFrameNanos { }
            state.focusPage()
        }
        wasOpen = open
    }

    AnimatedVisibility(
        visible = open,
        modifier = Modifier.fillMaxSize(),
        enter = fadeIn(fade) + if (still) EnterTransition.None else scaleIn(fade, initialScale = 0.97f),
        // No exit: the content (Settings behind its PIN, Downloads' lists) is gone with the destination.
        exit = ExitTransition.None,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val size = modalSize(maxWidth, maxHeight)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(FloatingTone.scrim)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { state.closeModal() },
            )
            Surface(
                shape = if (size.full) RoundedCornerShape(0.dp) else RoundedCornerShape(24.dp),
                color = Tone.background,
                border = if (size.full) null else floatingBorder(),
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(size.width)
                    .height(size.height)
                    .focusRequester(entry)
                    // Tab and the arrows stay in the panel; a video on top takes the keys, so then focus may leave.
                    .focusProperties { onExit = { if (state.nowPlaying == null) cancelFocusChange() } }
                    .focusGroup(),
            ) {
                // The pages clear the floating bar by this; the panel has no bar under it.
                CompositionLocalProvider(LocalBottomChrome provides 0.dp) { content() }
            }
            LaunchedEffect(Unit) {
                androidx.compose.runtime.withFrameNanos { }
                runCatching { entry.requestFocus(FocusDirection.Enter) }
            }
        }
    }
}
