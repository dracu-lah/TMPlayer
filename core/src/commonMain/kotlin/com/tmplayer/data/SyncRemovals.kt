package com.tmplayer.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.longPreferencesKey

/**
 * The removals the watched file keeps for resume on other devices ([SyncDocuments]): a mark taken
 * off or a Watch later line removed here is noted with its time, so the next sync tells the other
 * devices to drop their older copy rather than bring it back. Written whether or not sync is on
 * (a few bytes each, forgotten after [SyncDocuments.REMOVAL_DAYS] and capped), so turning it on
 * later still carries what was removed in the meantime.
 *
 * Only a removal the viewer made is noted: the cap dropping the oldest marks, a sync taking in
 * another device's removal, and signing out (which empties the file) are not.
 */
internal object SyncRemovals {

    fun watched(prefs: MutablePreferences, key: String, at: Long = System.currentTimeMillis()) {
        prefs[longPreferencesKey(SyncDocuments.WATCHED_REMOVAL_PREFIX + key)] = at
    }

    fun later(prefs: MutablePreferences, id: String, at: Long = System.currentTimeMillis()) {
        prefs[longPreferencesKey(SyncDocuments.LATER_REMOVAL_PREFIX + id)] = at
    }

    /** The entry is back (marked again, saved again, an Undo): its removal no longer stands. */
    fun forgetWatched(prefs: MutablePreferences, key: String) {
        prefs.remove(longPreferencesKey(SyncDocuments.WATCHED_REMOVAL_PREFIX + key))
    }

    fun forgetLater(prefs: MutablePreferences, id: String) {
        prefs.remove(longPreferencesKey(SyncDocuments.LATER_REMOVAL_PREFIX + id))
    }

    /** Drops removals past [SyncDocuments.REMOVAL_DAYS], then the oldest past [SyncDocuments.MAX_REMOVALS]. */
    fun prune(prefs: MutablePreferences, now: Long = System.currentTimeMillis()) {
        val oldest = now - SyncDocuments.REMOVAL_DAYS * 24 * 60 * 60 * 1000L
        val all = prefs.asMap().mapNotNull { (key, value) ->
            val name = key.name
            if (!name.startsWith(SyncDocuments.WATCHED_REMOVAL_PREFIX) && !name.startsWith(SyncDocuments.LATER_REMOVAL_PREFIX)) {
                return@mapNotNull null
            }
            name to ((value as? Long) ?: Long.MIN_VALUE)
        }
        val doomed = all.filter { it.second < oldest } +
            all.filter { it.second >= oldest }.sortedByDescending { it.second }.drop(SyncDocuments.MAX_REMOVALS)
        for ((name, _) in doomed) prefs.remove(longPreferencesKey(name))
    }
}
