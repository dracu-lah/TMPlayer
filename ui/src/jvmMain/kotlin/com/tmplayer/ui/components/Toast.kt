package com.tmplayer.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.tmplayer.platform.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Where a short notice is shown on the desktop: the window's snackbar host, which the shell
 * provides. Android's toast has no desktop counterpart, and a snackbar is the Material answer.
 */
val LocalToastHost = staticCompositionLocalOf<SnackbarHostState?> { null }

/**
 * A short confirmation, for an action whose result the viewer cannot see happen.
 *
 * The same call the shared screens make on Android, where it raises a toast. Here the notice is
 * shown on a scope of its own rather than the screen's, so, like a toast, it survives the screen
 * that raised it going away. A different notice waits for the one showing to time out (one waits at
 * most; [ToastQueue]), and the same one again replaces it.
 */
@Composable
fun rememberToast(): (String) -> Unit {
    val host = LocalToastHost.current
    return remember(host) {
        { message: String ->
            if (host == null) {
                Logger.i("Toast", message)
            } else {
                shelf(host).post(message, Notice(message, null, null))
            }
        }
    }
}

/**
 * [rememberToast] with one action on the snackbar, for a change that is easy to make by mistake
 * (a chat hidden from its menu): Undo, which runs [onAction] when pressed.
 */
@Composable
fun rememberActionToast(): (message: String, action: String, onAction: () -> Unit) -> Unit {
    val host = LocalToastHost.current
    return remember(host) {
        { message: String, action: String, onAction: () -> Unit ->
            if (host == null) {
                Logger.i("Toast", message)
            } else {
                shelf(host).post("undo:$message", Notice(message, action, onAction))
            }
        }
    }
}

/** One notice: its words, and an action (Undo) that keeps the long length. */
private class Notice(val message: String, val action: String?, val onAction: (() -> Unit)?)

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
                if (notice.action == null) {
                    host.showSnackbar(notice.message)
                } else {
                    val answer = host.showSnackbar(notice.message, actionLabel = notice.action, duration = androidx.compose.material3.SnackbarDuration.Long)
                    if (answer == androidx.compose.material3.SnackbarResult.ActionPerformed) notice.onAction?.invoke()
                }
            } finally {
                queue.finished(entry)?.let { run(it) }
            }
        }
    }
}

private val shelves = java.util.WeakHashMap<SnackbarHostState, Shelf>()

private fun shelf(host: SnackbarHostState): Shelf = shelves.getOrPut(host) { Shelf(host) }

private val noticeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + com.tmplayer.platform.JobFailures.handler("Toast"))
