package androidx.media3.common.util

/** What Media3 throws when a player waits with nothing to do, as its own (the engine reports a stall as an error on iOS). */
class StuckPlayerException(val stuckType: Int, val timeoutMs: Int) : IllegalStateException("Player stuck: type $stuckType, waited ${timeoutMs}ms") {
    companion object {
        const val STUCK_BUFFERING_NOT_LOADING = 0
        const val STUCK_BUFFERING_NO_PROGRESS = 1
        const val STUCK_PLAYING_NO_PROGRESS = 2
        const val STUCK_PLAYING_NOT_ENDING = 3
        const val STUCK_SUPPRESSED = 4
    }
}
