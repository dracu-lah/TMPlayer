package com.tmplayer.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import com.tmplayer.data.MediaItem
import com.tmplayer.ui.i18n.LocalStrings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * "Switch to Low storage mode?", asked whenever a video or a download is refused for room and
 * would stream in that mode. Asked every time, not once per install.
 *
 * The player waits on [ask] for its answer; the download picker leaves an [offer] and goes on,
 * since its refusal toast is already out. [LowStorageQuestionHost] draws the question in the shell,
 * over the player as well.
 */
object LowStorageQuestion {

    /** [answer] is the player's, waiting; null for a refused download, which plays on "Switch". */
    class Ask(val item: MediaItem, val keep: Boolean, val answer: CompletableDeferred<Boolean>?)

    private val _pending = MutableStateFlow<Ask?>(null)
    val pending = _pending.asStateFlow()

    /** For a video about to play: true when the viewer chose "Switch and play". */
    suspend fun ask(item: MediaItem): Boolean {
        val answer = CompletableDeferred<Boolean>()
        _pending.value = Ask(item, keep = false, answer = answer)
        return try {
            answer.await()
        } finally {
            if (_pending.value?.answer === answer) _pending.value = null
        }
    }

    /** For a download refused for room: "Switch and play" streams it instead. */
    fun offer(item: MediaItem) {
        _pending.value = Ask(item, keep = true, answer = null)
    }

    internal fun answered(ask: Ask, yes: Boolean) {
        if (_pending.value === ask) _pending.value = null
        ask.answer?.complete(yes)
    }
}

/** Draws [LowStorageQuestion]'s question, focus on "Switch and play". */
@Composable
fun LowStorageQuestionHost(state: ShellState) {
    val ask by LowStorageQuestion.pending.collectAsState()
    val shown = ask ?: return
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    ConfirmDialog(
        title = s.settingsStreamSwitchTitle,
        message = if (shown.keep) {
            s.mainStreamAskKeep
        } else {
            s.mainStreamAskPlay
        },
        detail = s.settingsStreamSaveDetail,
        confirmLabel = s.mainLowStorageSwitchPlay,
        cancelLabel = s.mainLowStorageNotNow,
        destructive = false,
        focusConfirm = true,
        onConfirm = {
            scope.launch {
                runCatching { state.settings.setLowStorageMode(true) }
                LowStorageQuestion.answered(shown, true)
                // A waiting player carries on by itself; a refused download is played here.
                if (shown.answer == null) state.openPlayer(shown.item, startFromBeginning = false)
            }
        },
        onDismiss = { LowStorageQuestion.answered(shown, false) },
    )
}
