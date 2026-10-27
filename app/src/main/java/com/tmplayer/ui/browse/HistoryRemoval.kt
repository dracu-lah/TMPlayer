package com.tmplayer.ui.browse

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.tmplayer.App
import com.tmplayer.data.Failures
import com.tmplayer.data.HistoryEdits
import com.tmplayer.data.HistoryUndo
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.WatchedStore
import com.tmplayer.i18n.L
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.components.rememberActionToast
import com.tmplayer.ui.components.showMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Remove from history, Clear history and Remove from Watch later, run the one way on every
 * screen: on the app's scope (a hold menu or a page closes at once and takes its own scope with
 * it), then said in a line with Undo on a touch screen. A remote cannot reach that line, so a
 * television asks first instead ([TvConfirm] at the caller) and the card leaving is the answer.
 */
internal class HistoryRemoval(
    private val context: Context,
    private val touch: Boolean,
    private val undoToast: (message: String, action: String, onAction: () -> Unit) -> Unit,
) {
    private val settings = SettingsStore(context)
    private val watched = WatchedStore(context)

    /** Runs [work]; [said] words the line for the number of cards that went. */
    fun run(said: (Int) -> String, work: suspend (SettingsStore, WatchedStore) -> HistoryUndo) {
        App.backgroundScope.launch {
            val result = runCatching { work(settings, watched) }
            val undo = result.getOrElse { e ->
                withContext(Dispatchers.Main) { showMessage(context, Failures.humanise(e)) }
                return@launch
            }
            // Nothing was there (the same card twice): nothing to say or undo.
            if (undo.isEmpty || !touch) return@launch
            withContext(Dispatchers.Main) {
                undoToast(said(undo.count), L.browseUndo) {
                    App.backgroundScope.launch {
                        runCatching { HistoryEdits.undo(settings, watched, undo) }
                            .onFailure { e -> withContext(Dispatchers.Main) { showMessage(context, Failures.humanise(e)) } }
                    }
                }
            }
        }
    }
}

@Composable
internal fun rememberHistoryRemoval(): HistoryRemoval {
    val context = LocalContext.current
    val touch = isTouch()
    val undoToast = rememberActionToast()
    return remember(context, touch, undoToast) { HistoryRemoval(context.applicationContext, touch, undoToast) }
}
