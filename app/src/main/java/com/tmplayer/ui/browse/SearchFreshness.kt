package com.tmplayer.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Starts the search fresh by calling [onExpired] when the app comes back after the rule in
 * [SearchLifetime] says the old one is stale: restored into a new process (checked at once, in
 * the first composition, so the old words never draw), or back in front after more than two
 * minutes away. A rotation or a quick switch to another app keeps the search.
 *
 * Call it right after the saved search state is declared, before anything reads that state.
 */
@Composable
internal fun SearchFreshness(clock: () -> Long = System::currentTimeMillis, onExpired: () -> Unit) {
    val expire by rememberUpdatedState(onExpired)
    // Saved with the state: after a restore it is the dead process's id, not this one's.
    var savedProcess by rememberSaveable { mutableStateOf(SearchLifetime.processId) }
    remember {
        if (!SearchLifetime.keeps(savedProcess, leftAtMs = null, nowMs = clock())) expire()
        savedProcess = SearchLifetime.processId
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        var leftAt: Long? = null
        val watcher = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> leftAt = clock()
                Lifecycle.Event.ON_START -> {
                    val left = leftAt
                    leftAt = null
                    if (left != null && !SearchLifetime.keeps(savedProcess, left, clock())) expire()
                }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(watcher)
        onDispose { owner.lifecycle.removeObserver(watcher) }
    }
}
