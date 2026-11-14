package com.tmplayer.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The files players have open, and whether the viewer is watching or has paused.
 *
 * What [DownloadYield] reads to know that a video is playing and since when it has been paused.
 * Players write it (Android's through the watch cache's playing hooks, the desktop's through its
 * open streams) and nothing here is stored: a process that dies takes every entry with it, so no
 * crash can leave a download waiting for a player that no longer exists.
 *
 * Counted per file, as the watch cache counts: two players on one file are one entry that stays
 * until both have let go.
 */
object StreamingPlays {

    /** How many players hold the file, and since when none of the ones that told us is playing. */
    data class Entry(val players: Int, val pausedSinceMs: Long? = null)

    private val _state = MutableStateFlow<Map<Int, Entry>>(emptyMap())

    /** Keyed by TDLib file id. */
    val state: StateFlow<Map<Int, Entry>> = _state.asStateFlow()

    /** A player opened [fileId]. It starts out playing: opening a video is not pausing it. */
    fun begin(fileId: Int) {
        if (fileId <= 0) return
        _state.update { map ->
            val entry = map[fileId]
            map + (fileId to Entry((entry?.players ?: 0) + 1, null))
        }
    }

    /** A player let go of [fileId]. A file nobody holds is gone from the map. */
    fun end(fileId: Int) {
        _state.update { map ->
            val entry = map[fileId] ?: return@update map
            if (entry.players <= 1) map - fileId else map + (fileId to entry.copy(players = entry.players - 1))
        }
    }

    /**
     * The viewer pressed play or pause on a player holding [fileIds]. Casting counts as playing,
     * since the phone is then the one feeding a screen somebody is watching.
     */
    fun setPlaying(fileIds: Collection<Int>, playing: Boolean, nowMs: Long = System.currentTimeMillis()) {
        if (fileIds.isEmpty()) return
        _state.update { map ->
            var next = map
            for (id in fileIds) {
                val entry = next[id] ?: continue
                val since = if (playing) null else entry.pausedSinceMs ?: nowMs
                if (since != entry.pausedSinceMs) next = next + (id to entry.copy(pausedSinceMs = since))
            }
            next
        }
    }

    /** For tests: forget everything. */
    fun clear() {
        _state.value = emptyMap()
    }
}
