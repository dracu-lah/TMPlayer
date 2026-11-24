package com.tmplayer.ui.components

import com.tmplayer.ui.components.ToastKind
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.math.max

/**
 * A short confirmation, for an action whose result the viewer cannot see happen.
 *
 * Deliberately rare. Most of this app answers for itself: a card that vanishes, a pill that
 * changes its label, a list that redraws. A message about something already on screen is just
 * noise. What earns one is the opposite case: the effect lands somewhere the viewer is not
 * looking, on a screen they are about to leave, or nowhere visible at all.
 *
 * Shown by [TopMessageHost] as a [ToastCard] standing [ToastLayout.Gap] above the floating bar
 * (by its measured top edge, [ToastFloor]), the keyboard or the system bar, so it never covers
 * any of them. Called with a [ToastKind] it takes that kind's colour and icon. The system's own
 * toast, which sits at the bottom and cannot be moved on Android 11 and later, is only the
 * fallback for a message raised while no host is on screen.
 */
@Composable
fun rememberToast(): Toaster {
    val context = LocalContext.current
    return remember(context) { Toaster { message: String, kind: ToastKind -> showMessage(context, message, kind) } }
}

/** [rememberToast] for code outside composition: in the card while a host is showing, else the system's toast. */
fun showMessage(context: Context, message: String, kind: ToastKind = ToastKind.Info) {
    if (TopMessages.hosts > 0 && TopMessages.flow.tryEmit(TopMessage(message, kind = kind))) return
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
 * [rememberToast] with one action on the card, for a change that is easy to make by mistake (a
 * chat hidden from its menu): "Undo" beside the words on a touch screen, held a little longer.
 * Under a remote the action is a pill the remote can walk to (it takes no focus on its own, so a
 * message never steals the viewer's place), held 12 s, and Back closes the message.
 */
@Composable
fun rememberActionToast(): ActionToaster {
    val context = LocalContext.current
    return remember(context) {
        ActionToaster { message: String, action: String, kind: ToastKind, onAction: () -> Unit ->
            if (!(TopMessages.hosts > 0 && TopMessages.flow.tryEmit(TopMessage(message, action, onAction, kind)))) {
                systemToast(context, message)
            }
        }
    }
}

internal class TopMessage(
    val text: String,
    val action: String? = null,
    val onAction: (() -> Unit)? = null,
    val kind: ToastKind = ToastKind.Info,
)

private object TopMessages {
    val flow = MutableSharedFlow<TopMessage>(extraBufferCapacity = 8)

    @Volatile
    var hosts = 0
}

/**
 * Where [showMessage] draws: a [ToastCard] at the foot, centred, [ToastLayout.Gap] over the
 * floating bar, the keyboard or the system bar, whichever stands highest. A phone's card fills the
 * width less 16 dp at each side up to 560 dp, and a swipe sends it away; a television's wraps its
 * words, 18 sp, inside the 5 per cent margin and Back closes it. About two and a half seconds
 * (longer with an action and on a television), a newer message replacing the one showing. Put
 * once over the whole app.
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
    val still = rememberLessMotion()
    val density = LocalDensity.current
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val inset = max(WindowInsets.ime.getBottom(density), WindowInsets.navigationBars.getBottom(density))
    // A television crops its edges: the card keeps inside the 5 per cent overscan margin.
    val safe = if (touch) 0 else (windowHeight * TV_SAFE).toInt()
    val bottom = with(density) { ToastLayout.clearancePx(ToastFloor.px, inset, safe, ToastLayout.Gap.roundToPx()).toDp() }
    AnimatedVisibility(
        visible = shown != null && !suppressed,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottom),
        enter = ToastMotion.enter(still),
        exit = ToastMotion.exit(),
    ) {
        val message = last
        val action = message.action
        val onAction = message.onAction
        ToastCard(
            kind = message.kind,
            message = message.text,
            modifier = if (touch) {
                Modifier.padding(horizontal = ToastLayout.PhoneMargin).widthIn(max = ToastLayout.PhoneMax).fillMaxWidth()
            } else {
                Modifier.padding(horizontal = com.tmplayer.ui.theme.Tv.SafeH).widthIn(max = ToastLayout.TvMax)
            },
            large = !touch,
            fillWidth = touch,
            animateIcon = !still,
            dismiss = if (touch) ToastDismiss.Swipe else ToastDismiss.None,
            onDismiss = { shown = null },
            action = if (action == null || onAction == null) {
                null
            } else if (touch) {
                {
                    ToastActionButton(action, message.kind) {
                        onAction()
                        shown = null
                    }
                }
            } else {
                // The remote reaches it like any button (Up from the top bar, or Down from nothing:
                // it sits in the focus order at the foot of the screen), and it takes no focus on its
                // own, so a message never steals the viewer's place.
                {
                    ToastAction(action, message.kind) {
                        onAction()
                        shown = null
                    }
                }
            },
        )
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
private fun ToastAction(label: String, kind: ToastKind, onClick: () -> Unit) {
    val interactions = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val tint = kind.tint()
    Box(
        Modifier
            .padding(start = 4.dp)
            .clip(RoundedCornerShape(50))
            .background(if (focused) Tone.focusFill else tint.copy(alpha = 0.22f))
            .clickable(interactionSource = interactions, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 6.dp),
    ) {
        Text(label, color = if (focused) Tone.onFocusFill else tint, style = MaterialTheme.typography.labelLarge.copy(fontSize = ToastLayout.TvText))
    }
}

private const val TV_SAFE = 0.05f
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
