package com.tmplayer.ui.components

import android.content.Context
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
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

/**
 * [rememberToast] with one action on the pill, for a change that is easy to make by mistake (a
 * chat hidden from its menu): "Undo" beside the words on a touch screen, held a little longer.
 * Under a remote the pill takes no focus, so a caller there says in the words where the change
 * is undone instead, and the action is not drawn.
 */
@Composable
fun rememberActionToast(): (message: String, action: String, onAction: () -> Unit) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { message: String, action: String, onAction: () -> Unit ->
            if (!(TopMessages.hosts > 0 && TopMessages.flow.tryEmit(TopMessage(message, action, onAction)))) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private class TopMessage(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null)

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
fun BoxScope.TopMessageHost() {
    var shown by remember { mutableStateOf<TopMessage?>(null) }
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
        delay(if (message.action != null && touch) SHOWN_WITH_ACTION_MS else SHOWN_MS)
        shown = null
    }
    // It drops in from the top on a soft spring, grows the last bit from its top edge as it lands,
    // and goes back up the way it came; with less motion asked for it only fades.
    val still = rememberLessMotion()
    AnimatedVisibility(
        visible = shown != null,
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
                val action = message.action?.takeIf { touch }
                val onAction = message.onAction
                if (action == null || onAction == null) {
                    Text(message.text, style = MaterialTheme.typography.bodyMedium, color = Tone.text, textAlign = TextAlign.Center)
                } else {
                    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(message.text, style = MaterialTheme.typography.bodyMedium, color = Tone.text, modifier = Modifier.weight(1f, fill = false))
                        androidx.compose.material3.TextButton(
                            onClick = {
                                onAction()
                                shown = null
                            },
                            modifier = Modifier.padding(start = 8.dp),
                        ) { Text(action, color = Tone.accent, style = MaterialTheme.typography.labelLarge) }
                    }
                }
            }
        }
    }
}

private const val SHOWN_MS = 2_500L
/**
 * A line with Undo stays long enough to reach for it: five seconds was gone before a viewer who
 * read it first got there (2026-10-27), and Gmail and Google Photos hold theirs about as long as this.
 */
private const val SHOWN_WITH_ACTION_MS = 8_000L
