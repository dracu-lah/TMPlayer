package com.tmplayer.ui.components

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.ui.graphics.TransformOrigin
import com.tmplayer.ui.theme.Motion
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * A short confirmation, for an action whose result the viewer cannot see happen.
 *
 * Deliberately rare. Most of this app answers for itself: a card that vanishes, a pill that
 * changes its label, a list that redraws. A message about something already on screen is just
 * noise. What earns one is the opposite case: the effect lands somewhere the viewer is not
 * looking, on a screen they are about to leave, or nowhere visible at all.
 *
 * Shown at the top of the window by [TopMessageHost], so it never covers the floating bar or a
 * page's buttons at the foot. The system's own toast, which sits at the bottom and cannot be moved
 * on Android 11 and later, is only the fallback for a message raised while no host is on screen.
 */
@Composable
fun rememberToast(): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) { { message: String -> showMessage(context, message) } }
}

/** [rememberToast] for code outside composition: at the top while a host is showing, else the system's toast. */
fun showMessage(context: Context, message: String) {
    if (TopMessages.hosts > 0 && TopMessages.flow.tryEmit(TopMessage(message))) return
    systemToast(context, message)
}

/**
 * The system's toast, made on the main thread whatever thread asks. A coroutine that wrote to the
 * disk can resume on the writer's thread, and Toast there throws (no Looper): Forget all in the
 * remembered languages crashed so when the settings store answered from its own thread.
 */
private fun systemToast(context: Context, message: String) {
    val main = Looper.getMainLooper()
    if (Looper.myLooper() == main) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    } else {
        Handler(main).post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }
}

/**
 * [rememberToast] with one action on the pill, for a change that is easy to make by mistake (a
 * chat hidden from its menu): "Undo" beside the words on a touch screen, held a little longer.
 * Under a remote the action is a pill the remote can walk to (it takes no focus on its own, so a
 * message never steals the viewer's place), held 12 s, and Back closes the message.
 */
@Composable
fun rememberActionToast(): (message: String, action: String, onAction: () -> Unit) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { message: String, action: String, onAction: () -> Unit ->
            if (!(TopMessages.hosts > 0 && TopMessages.flow.tryEmit(TopMessage(message, action, onAction)))) {
                systemToast(context, message)
            }
        }
    }
}

internal class TopMessage(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null)

private object TopMessages {
    val flow = MutableSharedFlow<TopMessage>(extraBufferCapacity = 8)

    @Volatile
    var hosts = 0
}

/**
 * Where [showMessage] draws: a small pill at the top centre, under the status bar, for about two
 * and a half seconds, a newer message replacing the one showing. Put once over the whole app.
 */
@Composable
fun BoxScope.TopMessageHost(
    /** True while nothing of the open profile may show (its PIN, the picker): a message waiting goes unseen. */
    suppressed: Boolean = false,
) {
    var shown by remember { mutableStateOf<TopMessage?>(null) }
    LaunchedEffect(suppressed) { if (suppressed) shown = null }
    var serial by remember { mutableIntStateOf(0) }
    var last by remember { mutableStateOf(TopMessage("")) }
    val touch = isTouch()
    DisposableEffect(Unit) {
        TopMessages.hosts++
        onDispose { TopMessages.hosts-- }
    }
    LaunchedEffect(Unit) {
        TopMessages.flow.collect { message ->
            shown = message
            last = message
            serial++
        }
    }
    LaunchedEffect(serial) {
        val message = shown ?: return@LaunchedEffect
        delay(toastDurationMs(message.text, message.action != null, touch))
        shown = null
    }
    // A remote has no swipe to send a message away: Back closes it (the next Back leaves as usual).
    // Composed only while a message shows, so it is the newest handler and wins over the screen's:
    // registered once at start-up it would sit under every handler a screen adds later.
    if (!touch && shown != null && !suppressed) com.tmplayer.ui.nav.BackHandler(enabled = true) { shown = null }
    // It drops in from the top on a soft spring, grows the last bit from its top edge as it lands,
    // and goes back up the way it came; with less motion asked for it only fades.
    val still = rememberLessMotion()
    AnimatedVisibility(
        visible = shown != null && !suppressed,
        // A television has no status bar but crops its edges: the pill keeps inside the overscan.
        modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding()
            .padding(top = if (isTouch()) 8.dp else com.tmplayer.ui.theme.Tv.SafeV, start = 24.dp, end = 24.dp),
        enter = if (still) {
            fadeIn(Motion.standard(Motion.Fast))
        } else {
            fadeIn(Motion.standard(Motion.Fast)) +
                slideInVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { -it } +
                scaleIn(Motion.standard(Motion.Normal), initialScale = 0.9f, transformOrigin = TransformOrigin(0.5f, 0f))
        },
        exit = if (still) {
            fadeOut(Motion.standard(Motion.Fast))
        } else {
            fadeOut(Motion.standard(Motion.Fast)) + slideOutVertically(Motion.standard(Motion.Normal)) { -it / 2 }
        },
    ) {
        val shape = RoundedCornerShape(50)
        Box(
            Modifier
                .widthIn(max = 480.dp)
                .shadow(6.dp, shape)
                .clip(shape)
                .background(FloatingTone.menu)
                .padding(horizontal = 18.dp, vertical = 10.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            // A newer message replacing one on screen cross-fades into it, the pill staying put.
            Crossfade(targetState = last, animationSpec = Motion.standard(Motion.Fast), label = "topMessage") { message ->
                val action = message.action
                val onAction = message.onAction
                if (action == null || onAction == null) {
                    Text(message.text, style = MaterialTheme.typography.bodyMedium, color = Tone.text, textAlign = TextAlign.Center)
                } else {
                    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(message.text, style = MaterialTheme.typography.bodyMedium, color = Tone.text, modifier = Modifier.weight(1f, fill = false))
                        if (touch) {
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    onAction()
                                    shown = null
                                },
                                modifier = Modifier.padding(start = 8.dp),
                            ) { Text(action, color = Tone.accent, style = MaterialTheme.typography.labelLarge) }
                        } else {
                            // The remote reaches it like any button (Up from the top bar, or Down
                            // from nothing: it sits in the focus order at the top of the screen), and
                            // it takes no focus on its own, so a message never steals the viewer's place.
                            ToastAction(action) {
                                onAction()
                                shown = null
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * How long a message stays. A touch screen reads it at arm's length in a moment; a television is read
 * from the sofa, so a message there stays for its length (a short one 4 s, a long one up to 9 s) and
 * a line with an action holds 12 s, so the remote has time to walk to the action (2026-11-23).
 */
fun toastDurationMs(text: String, hasAction: Boolean, touch: Boolean): Long = when {
    hasAction -> if (touch) SHOWN_WITH_ACTION_MS else SHOWN_WITH_ACTION_TV_MS
    touch -> SHOWN_MS
    else -> (TV_BASE_MS + text.length * TV_PER_CHAR_MS).coerceIn(SHOWN_TV_MIN_MS, SHOWN_TV_MAX_MS)
}

/** The action of a message on a television: a pill that fills under the remote, as every pill does. */
@Composable
private fun ToastAction(label: String, onClick: () -> Unit) {
    val interactions = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    Box(
        Modifier
            .padding(start = 12.dp)
            .clip(RoundedCornerShape(50))
            .background(if (focused) Tone.focusFill else Tone.accent.copy(alpha = 0.22f))
            .clickable(interactionSource = interactions, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 6.dp),
    ) {
        Text(label, color = if (focused) Tone.onFocusFill else Tone.accent, style = MaterialTheme.typography.labelLarge)
    }
}

private const val SHOWN_MS = 2_500L
private const val SHOWN_WITH_ACTION_TV_MS = 12_000L
private const val SHOWN_TV_MIN_MS = 4_000L
private const val SHOWN_TV_MAX_MS = 9_000L
private const val TV_BASE_MS = 2_000L
private const val TV_PER_CHAR_MS = 60L
/**
 * A line with Undo stays long enough to reach for it: five seconds was gone before a viewer who
 * read it first got there (2026-10-27), and Gmail and Google Photos hold theirs about as long as this.
 */
private const val SHOWN_WITH_ACTION_MS = 8_000L
