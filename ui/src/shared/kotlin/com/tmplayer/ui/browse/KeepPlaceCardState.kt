package com.tmplayer.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tmplayer.data.ResumeSync
import com.tmplayer.data.SettingsStore
import com.tmplayer.platform.Background
import kotlinx.coroutines.launch

/**
 * Whether Home draws [KeepPlaceCard] now, and what its buttons do. Kept above the list, so the card
 * stays for as long as this Home is on screen even after it was counted as shown (and a row
 * scrolling it out of the list does not take it away). "Shown" is written the moment the card's
 * item first comes into the list, which is when the viewer can see it: one showing, then never
 * again; answering either way does the same. Sync that is already on (a switch turned on in
 * Settings, or by an earlier build) never shows it.
 *
 * The work goes on the process's own scope: leaving Home with a press on "Yes" does not cancel it.
 */
@Stable
class KeepPlaceCardState(private val settings: SettingsStore, private val say: (String) -> Unit) {
    internal var keep by mutableStateOf(false)

    val visible: Boolean get() = keep

    /** The card's item is in the list: counted as shown, for good. */
    fun shown() {
        Background.scope.launch { runCatching { settings.setKeepPlaceCardSeen() } }
    }

    fun yes() {
        keep = false
        Background.scope.launch {
            runCatching {
                settings.setKeepPlaceCardSeen()
                settings.setResumeSync(true)
                ResumeSync.syncSoon(settings, afterMs = 0)
            }
        }
        say(com.tmplayer.i18n.L.syncplaceStarted)
    }

    fun notNow() {
        keep = false
        Background.scope.launch { runCatching { settings.setKeepPlaceCardSeen() } }
    }
}

/**
 * [KeepPlaceCardState] for this Home. [eligible] is false until Home has something to show, so the
 * question comes at the end of a Home that has rows, never over a first-run or empty page.
 */
@Composable
fun rememberKeepPlaceCardState(settings: SettingsStore, eligible: Boolean, say: (String) -> Unit): KeepPlaceCardState {
    val state = remember(settings) { KeepPlaceCardState(settings, say) }
    val pending by settings.keepPlaceCardPending.collectAsState(initial = false)
    // Once asked to stay for this visit it stays, though the stored flag turns false at once.
    LaunchedEffect(pending, eligible) { if (pending && eligible) state.keep = true }
    return state
}
