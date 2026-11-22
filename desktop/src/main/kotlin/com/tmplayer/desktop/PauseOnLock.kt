package com.tmplayer.desktop

import kotlinx.coroutines.flow.Flow

/**
 * The player pauses when its profile locks (HX88): a PIN profile that locks while the window is
 * minimised (a minute away) otherwise keeps playing behind the gate, with sound, and a cast keeps
 * going. [unlocked] is `ProfileUnlock.unlockedId`: a change from a profile to none is a lock (a
 * switch, the app lock and a long absence all end there), and the first value alone never pauses
 * (a player opened for a profile that has no PIN, or one still locked, has nothing to pause for).
 * The covers need nothing here: while locked the gate is drawn over the window and the
 * system's media controls and notifications go neutral ([com.tmplayer.desktop.os.ProfileOutside]).
 * Pausing never throws: [pause] is run guarded, since an engine may be gone by then.
 */
internal suspend fun pauseWhenLocked(unlocked: Flow<String?>, pause: () -> Unit) {
    var was: String? = null
    unlocked.collect { now ->
        if (was != null && now == null) runCatching(pause)
        was = now
    }
}
