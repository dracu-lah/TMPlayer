package com.tmplayer.player

/**
 * Sound without a picture (RL3, research 5.1, class C2): the decoder started and no frame came.
 * Media3 raises no error for it, so this counts the time the player has been READY with the
 * sound playing and no first frame, and says [RemedyEvidence.NoFirstFrame] once that reaches
 * [limitMs]: [HARDWARE_MS] on a hardware decoder, [SOFTWARE_MS] on software, [AFTER_SEEK_MS] more
 * after a seek. Pure; the player feeds it a sample a second from its existing sampler.
 *
 * Only qualifying time counts: ready, the sound playing, not buffering, not waiting for the bytes
 * (that is the source, not the decoder), the picture not hidden on purpose (background audio, PiP
 * hiding it) and not a cover art track. A gap between samples longer than [MAX_GAP_MS] (the app
 * away) is not counted. It fires once per [arm]; [firstFrame] ends it.
 */
class FirstFrameWatch {

    private var countedMs = 0L
    private var lastMs = -1L
    private var extraMs = 0L

    /** True once the first frame came or the watch fired: nothing more is counted until [arm]. */
    var done = false
        private set

    /** A new video, a hop or a re-prepare: count from nothing. [afterSeek] adds [AFTER_SEEK_MS]. */
    fun arm(afterSeek: Boolean = false) {
        done = false
        countedMs = 0L
        lastMs = -1L
        extraMs = if (afterSeek) AFTER_SEEK_MS else 0L
    }

    /** A seek before the first frame: the wait gets [AFTER_SEEK_MS] more, the count goes on. */
    fun seeked() {
        if (!done) extraMs = AFTER_SEEK_MS
    }

    /** `onRenderedFirstFrame`: the picture is there. */
    fun firstFrame() {
        done = true
    }

    /** The wait in force for a decoder that is [software] or not. */
    fun limitMs(software: Boolean): Long = (if (software) SOFTWARE_MS else HARDWARE_MS) + extraMs

    /** One sample; true once, when the qualifying wait reached [limitMs]. */
    fun sample(
        nowMs: Long,
        ready: Boolean,
        audioPlaying: Boolean,
        buffering: Boolean = false,
        sourceWaiting: Boolean = false,
        software: Boolean = false,
        hidden: Boolean = false,
        coverArt: Boolean = false,
    ): Boolean {
        if (done) return false
        if (coverArt) {
            done = true
            return false
        }
        val counts = ready && audioPlaying && !buffering && !sourceWaiting && !hidden
        if (!counts) {
            lastMs = -1L
            return false
        }
        val step = if (lastMs < 0) 0L else nowMs - lastMs
        if (step in 1..MAX_GAP_MS) countedMs += step
        lastMs = nowMs
        if (countedMs < limitMs(software)) return false
        done = true
        return true
    }

    companion object {
        const val HARDWARE_MS = 4_000L
        const val SOFTWARE_MS = 10_000L
        const val AFTER_SEEK_MS = 3_000L

        /** Samples further apart than this are a gap (the app away), not waiting. */
        const val MAX_GAP_MS = 2_500L
    }
}
