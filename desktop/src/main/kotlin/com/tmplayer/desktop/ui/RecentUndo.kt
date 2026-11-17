package com.tmplayer.desktop.ui

import com.tmplayer.data.SettingsStore
import com.tmplayer.platform.Background
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Clear on the chat search's recent searches, with Undo (the phone has had it since 7.x): the list
 * goes at once and the toast offers it back. What is cleared is read when the work runs, not from
 * what the panel drew, so a second press on a list already gone says nothing and a stale panel
 * never restores more than was there. Undo puts the list back under any search made since
 * ([SettingsStore.restoreRecentSearches]). On the process's scope: the page may leave before the
 * toast does.
 */
internal fun clearRecentWithUndo(
    settings: SettingsStore,
    undoToast: (message: String, action: String, onAction: () -> Unit) -> Unit,
    said: String,
    undoLabel: String,
) {
    Background.scope.launch {
        val before = runCatching { settings.recentSearches.first() }.getOrDefault(emptyList())
        if (before.isEmpty()) return@launch
        if (runCatching { settings.clearRecentSearches() }.isFailure) return@launch
        undoToast(said, undoLabel) {
            Background.scope.launch { runCatching { settings.restoreRecentSearches(before) } }
        }
    }
}
