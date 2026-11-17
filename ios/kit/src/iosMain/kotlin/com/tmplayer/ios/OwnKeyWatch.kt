package com.tmplayer.ios

import com.tmplayer.online.OnlineMetadata
import com.tmplayer.platform.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A TMDB key of one's own, added, changed or taken away in Settings, clears the posters and details
 * already found, as "Clear posters and details" does, so what is shown is what the key in use finds.
 * Without this a new key changed nothing until the viewer found that button (what was matched
 * without TMDB, TVmaze's and AniList's answers and the titles nothing was found for, is kept for
 * months), and a key taken away left TMDB's posters in place although TMDB is no longer asked.
 * Asked for by the maintainer (2026-11-14); Android's TMPlayer still leaves it to the button.
 *
 * The value at launch is not a change: the first one is skipped, so a launch clears nothing.
 */
internal object OwnKeyWatch {
    private var started = false

    fun start() {
        if (started) return
        val online = OnlineMetadata.current ?: return
        started = true
        com.tmplayer.App.backgroundScope.launch {
            online.store.settings
                .map { it.ownKey }
                .distinctUntilChanged()
                .drop(1)
                .collect { key ->
                    val what = if (key.isBlank()) "the TMDB key taken away" else "a new TMDB key"
                    runCatching { withContext(Dispatchers.Default) { online.purge() } }
                        .onSuccess { Logger.i(TAG, "$what: posters and details cleared, to be found again") }
                        .onFailure { Logger.w(TAG, "$what: the posters and details could not be cleared", it) }
                }
        }
    }

    private const val TAG = "OwnKeyWatch"
}
