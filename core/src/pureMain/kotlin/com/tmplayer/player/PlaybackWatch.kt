package com.tmplayer.player

/**
 * The watchdog over the first seconds of a video (HX2): whether this device keeps up with the
 * picture, read the same way for every file from what the player reports. Pure, so the lines are
 * tested alone; the player feeds it a sample a second and acts on the one answer it gives.
 *
 * Only time spent playing counts, and only when nothing else explains a slow clock (HX37, HX11:
 * "Video could not keep up" reports with no dropped frame and no underrun came from the windows
 * below, not from a decoder):
 * - paused, or waiting for the stream (a slow line is not a slow decoder): left out, and the
 *   player calls [interrupted] the moment it stops or buffers, so a stall between two samples is
 *   not read as slow decoding;
 * - a seek, a track change or any other jump: starts the count again ([reset], [interrupted]);
 * - the first [SETTLE_MS] of playing after the start, a seek or an interruption: the decoder is
 *   warming up and Media3 reports its dropped frames up to a second late, so neither the clock
 *   nor the counters are read yet;
 * - the viewer's own playback speed: 0.75x moves the position at 75% of the clock by design, so
 *   the pace is measured against the speed asked for ([sample]'s `speed`), not against 1x.
 *
 * Once [WINDOW_MS] of counted playing time is in, the answer is given once and the watch is done,
 * so nothing keeps running for the rest of the film.
 *
 * Not keeping up shows as any of: the position moving slower than the speed asked for (the sound
 * runs dry and the clock stalls with it, which is how a starved decoder looked on the 1 GB Mi TV:
 * 6.3 s of video in 17.6 s), a tenth of the frames dropped, the sound running dry again and again,
 * or frames reaching the screen late on average together with some frames dropped. Late frames on
 * their own are not a stutter: the clock ran true and no frame was lost, so the viewer saw none
 * ([symptomOf]).
 */
class PlaybackWatch {

    enum class Answer { KEPT_UP, CANNOT_KEEP_UP }

    private var lastWallMs = -1L
    private var lastPositionMs = 0L
    private var lastSpeed = 1f
    private var lastRendered = -1
    private var lastDropped = 0
    private var lastUnderruns = 0
    private var settledMs = 0L
    private var playedWallMs = 0L
    private var expectedMs = 0.0
    private var movedMs = 0L
    private var dropped = 0
    private var rendered = 0
    private var underruns = 0
    private var lateUs = 0L
    private var lateFrames = 0

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
        interrupted()
        playedWallMs = 0L
        expectedMs = 0.0
        movedMs = 0L
        dropped = 0
        rendered = 0
        underruns = 0
        lateUs = 0L
        lateFrames = 0
    }

    /**
     * The video stopped or jumped without a sample to say so (a pause, the player buffering, a
     * track or decoder change, a discontinuity that was not a seek): the stretch in hand is
     * dropped, what was counted stays, and the next [SETTLE_MS] of playing is not read.
     */
    fun interrupted() {
        lastWallMs = -1L
        lastRendered = -1
        settledMs = 0L
    }

    /** The video frame processing offsets Media3 reports (negative is late), summed, with their frame count. */
    fun frameOffsets(totalOffsetUs: Long, frames: Int) {
        // Not while settling: the start's late frames are the warm-up, not the decoder.
        if (done || frames <= 0 || settledMs < SETTLE_MS) return
        lateUs += totalOffsetUs
        lateFrames += frames
    }

    /**
     * One sample: the wall clock, the position, whether the video is playing now (ready, not
     * paused, not waiting for the stream), the running totals of frames rendered, frames dropped
     * and audio underruns, and the playback speed the viewer asked for (1 for normal). Returns
     * the answer once, when [WINDOW_MS] of counted playing time is in.
     */
    fun sample(
        wallMs: Long,
        positionMs: Long,
        playing: Boolean,
        renderedTotal: Int,
        droppedTotal: Int,
        underrunTotal: Int,
        speed: Float = 1f,
    ): Answer? {
        if (done) return null
        if (!playing) {
            // The next playing sample starts a new stretch; nothing in between is counted.
            interrupted()
            return null
        }
        val pace = speed.takeIf { it.isFinite() && it > 0f } ?: 1f
        // The stretch since the last sample, when there was one and it was whole playback at one speed.
        if (lastWallMs >= 0 && pace == lastSpeed) {
            val wall = wallMs - lastWallMs
            val moved = positionMs - lastPositionMs
            val expected = wall * pace.toDouble()
            // A jump the viewer made between samples is not playback: start over from here.
            if (wall in 1..MAX_GAP_MS && moved >= 0 && moved <= expected * 3) {
                if (settledMs < SETTLE_MS) {
                    settledMs += wall
                } else {
                    playedWallMs += wall
                    expectedMs += expected
                    movedMs += moved
                    // A decoder made again (a track change) counts its frames from nought: carry on from there.
                    val newFrames = renderedTotal - lastRendered
                    rendered += if (lastRendered < 0) 0 else if (newFrames < 0) renderedTotal else newFrames
                    dropped += (droppedTotal - lastDropped).coerceAtLeast(0)
                    underruns += (underrunTotal - lastUnderruns).coerceAtLeast(0)
                }
            } else {
                settledMs = 0L
            }
        } else if (lastWallMs >= 0) {
            // The speed changed since the last sample: the audio and the clock re-time themselves.
            settledMs = 0L
        }
        lastWallMs = wallMs
        lastPositionMs = positionMs
        lastSpeed = pace
        lastRendered = renderedTotal
        lastDropped = droppedTotal
        lastUnderruns = underrunTotal
        if (playedWallMs < WINDOW_MS) return null
        done = true
        return if (symptom() == null) Answer.KEPT_UP else Answer.CANNOT_KEEP_UP
    }

    /** The figures behind the answer, as numbers: for the opt-in "could not keep up" report. */
    data class Figures(
        /** Video seconds moved per second asked for, in per cent: 100 is as fast as the viewer asked. */
        val speedPercent: Int,
        val playedSeconds: Int,
        val dropped: Int,
        val frames: Int,
        val underruns: Int,
        /** What showed the stutter ([PlaybackWatch.symptomOf]), or empty when nothing did. */
        val symptom: String,
    )

    fun figures(): Figures = Figures(
        (speed() * 100).toInt(), (playedWallMs / 1000).toInt(), dropped, dropped + rendered, underruns, symptom().orEmpty(),
    )

    /** The figures behind the answer, for the log. */
    fun summary(): String {
        val late = if (lateFrames > 0) lateUs / lateFrames / 1000 else 0
        return "speed ${(speed() * 100).toInt()}% over ${playedWallMs / 1000} s, dropped $dropped of ${dropped + rendered}, " +
            "underruns $underruns, average frame offset ${late} ms, symptom ${symptom() ?: "none"}"
    }

    private fun speed(): Double = if (expectedMs > 0) movedMs / expectedMs else 0.0

    private fun symptom(): String? {
        val late = lateFrames >= MIN_LATE_FRAMES && lateUs / lateFrames < MAX_LATE_US
        return symptomOf(speed(), dropped, dropped + rendered, underruns, late)
    }

    companion object {
        /** Playing time watched before the answer: long enough for a start's hiccups to even out. */
        const val WINDOW_MS = 12_000L

        /**
         * Playing time not read after the start, a seek or an interruption: the decoder warms up,
         * and Media3 hands in its dropped frames up to a second after they happen.
         */
        const val SETTLE_MS = 2_000L

        /** A gap between samples longer than this (the app was away) is not counted. */
        private const val MAX_GAP_MS = 5_000L

        /** The position must keep at least this pace against the speed asked for. */
        private const val MIN_SPEED = 0.9

        /** More than this share of frames dropped is a stutter anyone sees. */
        private const val MAX_DROPPED_SHARE = 0.1

        /** A handful of dropped frames is not a share to judge by. */
        private const val MIN_DROPPED = 10

        /** Late frames count only with more than this share of frames dropped: late alone is not seen. */
        private const val MIN_DROPPED_WITH_LATE = 0.02

        /** The sound running dry this often in the window is not a one-off. */
        private const val MAX_UNDERRUNS = 3

        /** Frames late on average by more than 40 ms (Media3's offset is negative when late). */
        private const val MAX_LATE_US = -40_000L
        private const val MIN_LATE_FRAMES = 60

        const val SLOW = "slow"
        const val DROPPED = "dropped"
        const val UNDERRUNS = "underruns"
        const val LATE = "late"

        /**
         * The one definition of a real stutter, for the watch's answer and for the report's gate
         * (HX37): [speed] is the position's pace against the speed asked for (1.0 is on time),
         * and [late] whether frames reached the screen late on average. Null when nothing the
         * viewer would see happened. Garbage in (not a number, negative, absurdly large) reads as
         * no evidence, never as a stutter.
         */
        fun symptomOf(speed: Double, dropped: Int, frames: Int, underruns: Int, late: Boolean): String? {
            val lost = dropped.coerceAtLeast(0).toLong()
            val total = frames.coerceAtLeast(0).toLong().coerceAtLeast(lost)
            if (speed.isFinite() && speed >= 0.0 && speed < MIN_SPEED) return SLOW
            if (lost >= MIN_DROPPED && lost.toDouble() / total > MAX_DROPPED_SHARE) return DROPPED
            if (underruns >= MAX_UNDERRUNS) return UNDERRUNS
            if (late && lost >= MIN_DROPPED && lost.toDouble() / total > MIN_DROPPED_WITH_LATE) return LATE
            return null
        }
    }
}
