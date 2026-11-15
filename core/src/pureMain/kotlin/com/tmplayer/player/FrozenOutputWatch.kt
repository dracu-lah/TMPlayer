package com.tmplayer.player

/**
 * A frozen picture while the sound goes on (RL3, research 5.2, class C3): the decoder stopped
 * returning output, with no error and no dropped frame. Says [RemedyEvidence.Frozen] when the
 * position advanced [FROZEN_MS] of video while the rendered, dropped and skipped totals all stood
 * still. A still scene keeps rendering frames, so it never looks frozen. Pure; the player feeds it
 * a sample a second (`DecoderCounters` after `ensureUpdated()`).
 *
 * Only steady playback counts: playing (not paused, not buffering) at a speed above 0. A paused
 * sample, a jump of the position (a seek between samples), a gap between samples longer than
 * [MAX_GAP_MS] (the app away), a speed change or totals going down (a decoder made anew) start the
 * count again from that sample. It fires once per [arm] (a hop arms it again), so one stall is
 * never reported twice.
 */
class FrozenOutputWatch {

    private var basePosition = 0L
    private var lastWall = -1L
    private var lastPosition = 0L
    private var lastSpeed = 1f
    private var rendered = 0L
    private var dropped = 0L
    private var skipped = 0L

    /** True once it fired: nothing more until [arm]. */
    var fired = false
        private set

    /** A new video, a hop or a re-prepare: watch from the next sample. */
    fun arm() {
        fired = false
        restart()
    }

    /** A seek: the count starts again, the fired state stays. */
    fun seeked() = restart()

    /** One sample; true once, when the output stood still for [FROZEN_MS] of advancing position. */
    fun sample(
        wallMs: Long,
        positionMs: Long,
        playing: Boolean,
        renderedTotal: Long,
        droppedTotal: Long,
        skippedTotal: Long,
        speed: Float = 1f,
    ): Boolean {
        if (fired) return false
        val pace = speed.takeIf { it.isFinite() && it > 0f }
        if (!playing || pace == null) {
            restart()
            return false
        }
        val outputMoved = renderedTotal != rendered || droppedTotal != dropped || skippedTotal != skipped
        if (lastWall < 0 || outputMoved || !steady(wallMs, positionMs, pace)) {
            basePosition = positionMs
        }
        lastWall = wallMs
        lastPosition = positionMs
        lastSpeed = pace
        rendered = renderedTotal
        dropped = droppedTotal
        skipped = skippedTotal
        if (positionMs - basePosition < FROZEN_MS) return false
        fired = true
        return true
    }

    /** The stretch since the last sample was plain playback at one speed: no jump, no gap. */
    private fun steady(wallMs: Long, positionMs: Long, pace: Float): Boolean {
        val wall = wallMs - lastWall
        if (wall <= 0 || wall > MAX_GAP_MS || pace != lastSpeed) return false
        val moved = positionMs - lastPosition
        val expected = wall * pace
        return moved >= 0 && moved <= expected * 1.5 + JUMP_SLACK_MS
    }

    private fun restart() {
        lastWall = -1L
    }

    companion object {
        /** Video time the output may stand still while the position runs. */
        const val FROZEN_MS = 3_000L

        /** Samples further apart than this are a gap, not playback. */
        const val MAX_GAP_MS = 2_500L

        /** How far beyond the clock the position may move between samples before it counts as a jump. */
        private const val JUMP_SLACK_MS = 1_000L
    }
}
