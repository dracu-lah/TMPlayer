package com.tmplayer.ui.browse

import java.util.UUID

/**
 * How long a global search outlives the app being away (user, 2026-11-16: "I search, close the
 * app and come back, the old search is there again"). The search box, its filters and its open
 * state are kept across a rotation and a short app switch, and start fresh when the app has been
 * away for more than [AWAY_LIMIT_MS], or when the state comes back into a new process (the system
 * killed the app and restored the saved state, or the desktop window was reopened).
 *
 * One rule for every platform, so the phone, the TV and the desktop palette cannot drift apart.
 */
object SearchLifetime {
    /** Two minutes: a glance at another app keeps the search, a coffee does not. */
    const val AWAY_LIMIT_MS = 120_000L

    /** This process's id, stored beside the saved state: a restored id that differs is from a dead process. */
    val processId: String = UUID.randomUUID().toString()

    /**
     * Whether the saved search stays.
     *
     * [savedProcessId] is the id stored with the state (null when nothing was stored). [leftAtMs]
     * is when the app went to the background, null while it did not (a rotation, a restore).
     * Both times are wall-clock milliseconds, so a sleeping device still counts as away.
     */
    fun keeps(
        savedProcessId: String?,
        leftAtMs: Long?,
        nowMs: Long,
        currentProcessId: String = processId,
    ): Boolean {
        if (savedProcessId == null || savedProcessId != currentProcessId) return false
        if (leftAtMs == null) return true
        val away = nowMs - leftAtMs
        // A clock set back is not "a moment ago": start fresh.
        return away in 0..AWAY_LIMIT_MS
    }
}
