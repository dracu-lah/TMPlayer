package com.tmplayer.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tmplayer.ui.i18n.LocalStrings

/**
 * The question before a quit that would cut downloads (WP-46): Close, Ctrl+Q (Cmd+Q), the window
 * buttons and Restart now all go through [request], so one rule holds for every way out.
 *
 * Nothing running: [request] runs the action at once. "Do not ask again" ticked once ([skip]): the
 * downloads are paused first ([pauseAll]), then the action runs, so a quit that skips the question
 * still leaves its downloads paused, not failed. Otherwise the action waits in [pending] for the
 * viewer's answer (Keep running drops it, Quit and pause runs it), and a second request while the
 * question is up changes nothing. The OS shutting the session down never reaches here: the JVM's
 * shutdown hooks pause as before, with no dialog possible.
 */
class QuitGuard(
    private val running: () -> Int,
    private val skip: () -> Boolean,
    private val rememberSkip: () -> Unit,
    private val pauseAll: () -> Unit,
) {
    /** The quit waiting for an answer, or null while nothing is asked. */
    var pending: (() -> Unit)? by mutableStateOf(null)
        private set

    /** How many downloads were running when the question was asked (drawn in its title). */
    var asked: Int by mutableStateOf(0)
        private set

    fun request(proceed: () -> Unit) {
        if (pending != null) return
        val count = running()
        when {
            count <= 0 -> proceed()
            skip() -> {
                pauseAll()
                proceed()
            }
            else -> {
                asked = count
                pending = proceed
            }
        }
    }

    /** Keep running: the window stays as it is and the downloads go on. */
    fun keep() {
        pending = null
    }

    /** Quit and pause, with the "Do not ask again" tick as it stood. */
    fun quit(neverAsk: Boolean) {
        val proceed = pending ?: return
        pending = null
        if (neverAsk) rememberSkip()
        pauseAll()
        proceed()
    }
}

/**
 * [QuitGuard]'s question, drawn over the window: Keep running (focused first, so Enter and Esc
 * return to the app), Quit and pause, and a "Do not ask again" tick box.
 */
@Composable
fun QuitQuestionHost(guard: QuitGuard) {
    if (guard.pending == null) return
    QuitQuestion(count = guard.asked, onKeep = guard::keep, onQuit = guard::quit)
}

/** The question itself, for [QuitQuestionHost] and its test. */
@Composable
internal fun QuitQuestion(count: Int, onKeep: () -> Unit, onQuit: (neverAsk: Boolean) -> Unit) {
    val s = LocalStrings.current
    var never by remember { mutableStateOf(false) }
    ConfirmDialog(
        title = s.downloadsQuitRunningTitle(count = count),
        message = s.downloadsQuitRunningBody,
        confirmLabel = s.downloadsQuitPause,
        cancelLabel = s.downloadsQuitKeep,
        destructive = false,
        extra = { CheckRow(checked = never, onChange = { never = it }, label = s.downloadsQuitNeverAsk) },
        onConfirm = { onQuit(never) },
        onDismiss = onKeep,
    )
}
