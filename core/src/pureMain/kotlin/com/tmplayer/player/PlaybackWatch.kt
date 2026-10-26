package com.tmplayer.player

/**
 * The watchdog over the first seconds of a video (HX2): whether this device keeps up with the
 * picture, read the same way for every file from what the player reports. Pure, so the lines are
 * tested alone; the player feeds it a sample a second and acts on the one answer it gives.
 *
 * Only time spent playing counts: paused, or waiting for the stream (a slow line is not a slow
 * decoder), is left out, and a seek starts the count again. Once [WINDOW_MS] of playing time is
 * in, the answer is given once and the watch is done, so nothing keeps running for the rest of
 * the film.
 *
 * Not keeping up shows as any of: the position moving slower than the clock (the sound runs dry
 * and the clock stalls with it, which is how a starved decoder looked on the 1 GB Mi TV: 6.3 s of
 * video in 17.6 s), frames dropped, the sound running dry again and again, or frames reaching the
 * screen late on average.
 */
class PlaybackWatch {

    enum class Answer { KEPT_UP, CANNOT_KEEP_UP }

    private var lastWallMs = -1L
    private var lastPositionMs = 0L
    private var playedWallMs = 0L
    private var movedMs = 0L
    private var dropped = 0
    private var rendered = 0
    private var underruns = 0
    private var lateUs = 0L
    private var lateFrames = 0
    private var startDropped = -1
    private var startRendered = -1
    private var startUnderruns = -1

    /** Whether the answer was given: then nothing more is counted. */
    var done = false
        private set

    /** A new video: the count and the answer both start again. */
    fun restart() {
        done = false
        reset()
    }

    /** Starts the count again: after a seek, the answer still to come. */
    fun reset() {
        lastWallMs = -1L
        playedWallMs = 0L
        movedMs = 0L
        startDropped = -1
        startRendered = -1
        startUnderruns = -1
        dropped = 0
        rendered = 0
        underruns = 0
        lateUs = 0L
        lateFrames = 0
    }

    /** The video frame processing offsets Media3 reports (negative is late), summed, with their frame count. */
    fun frameOffsets(totalOffsetUs: Long, frames: Int) {
        if (done || frames <= 0) return
        lateUs += totalOffsetUs
        lateFrames += frames
    }

    /**
     * One sample: the wall clock, the position, whether the video is playing now (ready, not
     * paused, not waiting for the stream), and the running totals of frames rendered, frames
     * dropped and audio underruns. Returns the answer once, when [WINDOW_MS] of playing time is in.
     */
    fun sample(
        wallMs: Long,
        positionMs: Long,
        playing: Boolean,
        renderedTotal: Int,
        droppedTotal: Int,
        underrunTotal: Int,
    ): Answer? {
        if (done) return null
        if (!playing) {
            // The next playing sample starts a new stretch; nothing in between is counted.
            lastWallMs = -1L
            return null
        }
        // A decoder made again (a track change) counts its frames from nought: carry on from there.
        if (startRendered >= 0 && renderedTotal < startRendered + rendered) startRendered = renderedTotal - rendered
        if (startRendered < 0) {
            startRendered = renderedTotal
            startDropped = droppedTotal
            startUnderruns = underrunTotal
        }
        if (lastWallMs >= 0) {
            val wall = wallMs - lastWallMs
            val moved = positionMs - lastPositionMs
            // A jump the viewer made between samples is not playback: start over from here.
            if (wall in 1..MAX_GAP_MS && moved in 0..(wall * 3)) {
                playedWallMs += wall
                movedMs += moved
            }
        }
        lastWallMs = wallMs
        lastPositionMs = positionMs
        rendered = renderedTotal - startRendered
        dropped = droppedTotal - startDropped
        underruns = underrunTotal - startUnderruns
        if (playedWallMs < WINDOW_MS) return null
        done = true
        return if (keptUp()) Answer.KEPT_UP else Answer.CANNOT_KEEP_UP
    }

    /** The figures behind the answer, as numbers: for the opt-in "could not keep up" report. */
    data class Figures(
        /** Video seconds moved per second of playing, in per cent: 100 is real time. */
        val speedPercent: Int,
        val playedSeconds: Int,
        val dropped: Int,
        val frames: Int,
        val underruns: Int,
    )

    fun figures(): Figures {
        val speed = if (playedWallMs > 0) movedMs.toDouble() / playedWallMs else 0.0
        return Figures((speed * 100).toInt(), (playedWallMs / 1000).toInt(), dropped, dropped + rendered, underruns)
    }

    /** The figures behind the answer, for the log. */
    fun summary(): String {
        val speed = if (playedWallMs > 0) movedMs.toDouble() / playedWallMs else 0.0
        val late = if (lateFrames > 0) lateUs / lateFrames / 1000 else 0
        return "speed ${(speed * 100).toInt()}% over ${playedWallMs / 1000} s, dropped $dropped of ${dropped + rendered}, " +
            "underruns $underruns, average frame offset ${late} ms"
    }

    private fun keptUp(): Boolean {
        val speed = movedMs.toDouble() / playedWallMs
        if (speed < MIN_SPEED) return false
        val frames = dropped + rendered
        if (frames > 0 && dropped.toDouble() / frames > MAX_DROPPED_SHARE) return false
        if (underruns >= MAX_UNDERRUNS) return false
        if (lateFrames >= MIN_LATE_FRAMES && lateUs / lateFrames < MAX_LATE_US) return false
        return true
    }

    companion object {
        /** Playing time watched before the answer: long enough for a start's hiccups to even out. */
        const val WINDOW_MS = 12_000L

        /** A gap between samples longer than this (the app was away) is not counted. */
        private const val MAX_GAP_MS = 5_000L

        /** The position must keep at least this pace against the clock. */
        private const val MIN_SPEED = 0.9

        /** More than this share of frames dropped is a stutter anyone sees. */
        private const val MAX_DROPPED_SHARE = 0.1

        /** The sound running dry this often in the window is not a one-off. */
        private const val MAX_UNDERRUNS = 3

        /** Frames late on average by more than 40 ms (Media3's offset is negative when late). */
        private const val MAX_LATE_US = -40_000L
        private const val MIN_LATE_FRAMES = 60
    }
}
