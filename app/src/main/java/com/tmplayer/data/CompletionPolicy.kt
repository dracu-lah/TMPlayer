package com.tmplayer.data

object CompletionPolicy {
    const val THRESHOLD = 0.95

    fun isComplete(
        positionMs: Long,
        durationMs: Long,
        endedNormally: Boolean = false,
        playbackError: Boolean = false,
    ): Boolean {
        if (playbackError || durationMs <= 0L || positionMs < 0L) return false
        return endedNormally || positionMs.toDouble() / durationMs.toDouble() >= THRESHOLD
    }

    fun canRecoverLegacy(positionMs: Long, durationMs: Long, explicitlyUnwatched: Boolean): Boolean =
        !explicitlyUnwatched && isComplete(positionMs, durationMs)
}
