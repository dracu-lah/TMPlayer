package com.tmplayer.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.tmplayer.platform.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Where a short notice is queued on the desktop: the window's snackbar state, which the shell
 * provides and [ToastHost] draws as a [ToastCard]. Android's toast has no desktop counterpart.
 */
val LocalToastHost = staticCompositionLocalOf<SnackbarHostState?> { null }

/**
 * A short confirmation, for an action whose result the viewer cannot see happen.
 *
 * The same call the shared screens make on Android, where it raises a toast. Here the notice is
 * shown on a scope of its own rather than the screen's, so, like a toast, it survives the screen
 * that raised it going away. A different notice waits for the one showing to time out (one waits at
 * most; [ToastQueue]), and the same one again replaces it. Called with a [ToastKind] it is drawn in
 * that kind's colour and icon.
 */
@Composable
fun rememberToast(): Toaster {
    val host = LocalToastHost.current
    return remember(host) {
        Toaster { message: String, kind: ToastKind ->
            if (host == null) {
                Logger.i("Toast", message)
            } else {
                shelf(host).post(toastKey(message, null), Notice(message, null, null, kind))
            }
        }
    }
}

/**
 * [rememberToast] with one action on the toast, for a change that is easy to make by mistake
 * (a chat hidden from its menu): Undo, which runs [onAction] when pressed.
 */
@Composable
fun rememberActionToast(): ActionToaster {
    val host = LocalToastHost.current
    return remember(host) {
        ActionToaster { message: String, action: String, kind: ToastKind, onAction: () -> Unit ->
            if (host == null) {
                Logger.i("Toast", message)
            } else {
                shelf(host).post(toastKey(message, action), Notice(message, action, onAction, kind))
            }
        }
    }
}

/** One notice: its words, its kind, and an action (Undo) that keeps the long length. */
private class Notice(val message: String, val action: String?, val onAction: (() -> Unit)?, val kind: ToastKind)

/** What [SnackbarHostState] shows for a [Notice]: the words, and the kind [ToastHost] draws. */
private class ToastVisuals(
    override val message: String,
    override val actionLabel: String?,
    val kind: ToastKind,
    override val duration: SnackbarDuration,
) : SnackbarVisuals {
    override val withDismissAction: Boolean get() = false
}

/** The queue and the snackbar host it feeds; one per host. */
private class Shelf(private val host: SnackbarHostState) {
    private val queue = ToastQueue<Notice>()

    fun post(kind: String, notice: Notice) {
        val offer = queue.offer(kind, notice)
        val show = offer.show ?: return
        if (offer.replacesCurrent) host.currentSnackbarData?.dismiss()
        run(show)
    }

    private fun run(entry: ToastQueue.Entry<Notice>) {
        val notice = entry.item
        // A waiting notice has no job yet: one starts when the one showing has ended.
        noticeScope.launch {
            try {
                val duration = if (notice.action == null) SnackbarDuration.Short else SnackbarDuration.Long
                val answer = host.showSnackbar(ToastVisuals(notice.message, notice.action, notice.kind, duration))
                if (answer == SnackbarResult.ActionPerformed) notice.onAction?.invoke()
            } finally {
                queue.finished(entry)?.let { run(it) }
            }
        }
    }
}

private val shelves = java.util.WeakHashMap<SnackbarHostState, Shelf>()

private fun shelf(host: SnackbarHostState): Shelf = shelves.getOrPut(host) { Shelf(host) }

private val noticeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + com.tmplayer.platform.JobFailures.handler("Toast"))

/**
 * Where the window's short messages show: a [ToastCard] centred over the window, [ToastLayout.Gap]
 * above the highest floating bar ([ToastFloor], measured from what is on screen) and never lower
 * than [safeBottom], wrapping its words between [ToastLayout.DesktopMin] and [ToastLayout.DesktopMax].
 * It comes up and in over 160 ms and fades out over 120; a click sends it away, Esc does while its
 * action has the keyboard, and the action is reached by Tab like any button. A newer message
 * replaces the one showing (the queue is [ToastQueue]'s).
 */
@Composable
fun BoxScope.ToastHost(host: SnackbarHostState, modifier: Modifier = Modifier, safeBottom: Dp = 0.dp) {
    val data = host.currentSnackbarData
    val held = remember { arrayOfNulls<SnackbarData>(1) }
    if (data != null) held[0] = data
    val shown = held[0]
    val density = LocalDensity.current
    val bottom = with(density) {
        ToastLayout.clearancePx(ToastFloor.px, 0, safeBottom.roundToPx(), ToastLayout.Gap.roundToPx()).toDp()
    }
    val still = rememberLessMotion()
    AnimatedVisibility(
        visible = data != null,
        modifier = modifier.align(Alignment.BottomCenter).padding(bottom = bottom, start = 24.dp, end = 24.dp),
        enter = ToastMotion.enter(still),
        exit = ToastMotion.exit(),
    ) {
        if (shown != null) {
            val visuals = shown.visuals
            val kind = (visuals as? ToastVisuals)?.kind ?: ToastKind.Info
            ToastCard(
                kind = kind,
                message = visuals.message,
                modifier = Modifier
                    .widthIn(min = ToastLayout.DesktopMin, max = ToastLayout.DesktopMax)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            shown.dismiss()
                            true
                        } else {
                            false
                        }
                    },
                animateIcon = !still,
                dismiss = ToastDismiss.Click,
                onDismiss = { shown.dismiss() },
                action = visuals.actionLabel?.let { label -> { ToastActionButton(label, kind) { shown.performAction() } } },
            )
        }
    }
}
