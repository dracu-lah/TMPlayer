package com.tmplayer.cast.dlna

import com.tmplayer.cast.IdleReason
import com.tmplayer.cast.ReceiverMedia
import com.tmplayer.cast.ReceiverState
import java.util.Locale

/** UPnP's time strings, which every renderer writes a little differently. */
object UpnpTime {

    private val TIME = Regex("""^([+-])?(\d{1,5}):(\d{1,2}):(\d{1,2})(?:\.(\d{1,9}))?(?:\.\d+)?(?:\s*[Ff]\d+/\d+)?$""")

    /**
     * `0:00:30`, `00:00:05`, `0:00:01.016`, `+00:01:00.5` as milliseconds. Null for what carries
     * no time: `NOT_IMPLEMENTED`, empty, garbage, a negative time, minutes or seconds of 60 or more.
     */
    fun parseMs(text: String?): Long? {
        val m = TIME.matchEntire(text?.trim() ?: return null) ?: return null
        if (m.groupValues[1] == "-") return null
        val h = m.groupValues[2].toLong()
        val min = m.groupValues[3].toLong()
        val s = m.groupValues[4].toLong()
        if (min >= 60 || s >= 60) return null
        val frac = m.groupValues[5]
        val ms = if (frac.isEmpty()) 0L else (frac.take(3).padEnd(3, '0')).toLong()
        return h * 3_600_000 + min * 60_000 + s * 1000 + ms
    }

    /** `HH:MM:SS`, the form REL_TIME seeks take; every renderer reads this one. */
    fun format(ms: Long): String {
        val t = (ms.coerceAtLeast(0)) / 1000
        return String.format(Locale.ROOT, "%02d:%02d:%02d", t / 3600, t / 60 % 60, t % 60)
    }
}

/** The actions a renderer allows right now (GetCurrentTransportActions). */
data class TransportActions(
    val play: Boolean,
    val pause: Boolean,
    val stop: Boolean,
    val seek: Boolean,
) {
    companion object {
        /** Before the renderer has been asked: nothing is promised, and Seek is tried, not hidden. */
        val UNKNOWN = TransportActions(play = true, pause = true, stop = true, seek = true)

        /**
         * `Play,Stop,Pause,Seek` as gmrender-resurrect writes it in capitals, Rygel's list with its
         * `X_DLNA_SeekTime` and `X_DLNA_PS=1/16\,1/8` extras (the escaped commas stay inside one
         * item), and an empty list. Null when the answer names no action at all.
         */
        fun parse(text: String?): TransportActions? {
            if (text.isNullOrBlank()) return null
            val items = text.replace("\\,", "\u0000").split(',').map { it.replace('\u0000', ',').trim().lowercase(Locale.ROOT) }
            return TransportActions(
                play = "play" in items,
                pause = "pause" in items,
                stop = "stop" in items,
                seek = "seek" in items || "x_dlna_seektime" in items,
            )
        }
    }
}

/**
 * What a renderer's transport state means for the cast screen, and what its position means.
 *
 * Renderers report the position badly while they change state: Rygel answers `0:00:00` straight
 * after a Seek, a TV buffering may step back a few seconds, and a stopped player often writes
 * zero. So the place is kept here ([place]) and a raw position is believed only when it fits what
 * was asked and what was playing. One instance per cast session; not thread safe, the poll loop
 * owns it.
 */
class DlnaPlayback(
    /** The video's length as probed, used when the renderer does not know it. */
    private val probedDurationMs: Long = 0,
) {
    /** The renderer's own length, once it has said one. */
    var durationMs: Long = probedDurationMs
        private set

    /** Where the video is, for the clock and the saved place. */
    var place: Long = 0
        private set

    private var playedAtMs = 0L
    private var seekTarget = -1L
    private var seekAskedAtMs = 0L
    private var playedOnce = false
    private var stoppedByUs = false
    private var pausedByUs = false

    /** A new video was loaded, to start at [startMs]. */
    fun loaded(startMs: Long, nowMs: Long) {
        place = startMs.coerceAtLeast(0)
        playedAtMs = nowMs
        playedOnce = false
        stoppedByUs = false
        pausedByUs = false
        seekTarget = -1
    }

    /** The viewer (or a resume) seeked to [targetMs]. */
    fun seeking(targetMs: Long, nowMs: Long) {
        seekTarget = targetMs.coerceAtLeast(0)
        seekAskedAtMs = nowMs
        place = seekTarget
        playedAtMs = nowMs
    }

    /** TMPlayer sent Stop: the STOPPED that follows is a cancel, not an interruption. */
    fun stoppedHere() {
        stoppedByUs = true
        pausedByUs = false
    }

    /**
     * TMPlayer sent Pause. Rygel's Playbin renderer answers STOPPED while paused (its allowed
     * actions say Play and Seek, but its state does not say PAUSED_PLAYBACK), so until Play, Stop
     * or a new load, a STOPPED is read as the pause it is.
     */
    fun pausedHere() {
        pausedByUs = true
    }

    /** TMPlayer sent Play. */
    fun playedHere() {
        pausedByUs = false
    }

    /**
     * One poll: [transport] is `CurrentTransportState`, [status] `CurrentTransportStatus`, [rawPositionMs]
     * the `RelTime`, [rawDurationMs] the `TrackDuration` (either null when absent or unreadable).
     */
    fun update(transport: String?, status: String?, rawPositionMs: Long?, rawDurationMs: Long?, nowMs: Long): ReceiverMedia {
        // A real length beats the probed one; zero is how some renderers say "not yet".
        if (rawDurationMs != null && rawDurationMs > 0) durationMs = rawDurationMs
        var state = transport?.trim()?.uppercase(Locale.ROOT)
        if (pausedByUs && playedOnce && (state == "STOPPED" || state == "NO_MEDIA_PRESENT") && status?.trim()?.uppercase(Locale.ROOT) != "ERROR_OCCURRED") {
            state = "PAUSED_PLAYBACK"
        }
        val error = status?.trim()?.uppercase(Locale.ROOT) == "ERROR_OCCURRED"
        val moving = state == "PLAYING"
        place = believe(rawPositionMs, moving, state == "STOPPED" || state == "NO_MEDIA_PRESENT", nowMs)
        if (moving || state == "PAUSED_PLAYBACK" || state == "PAUSED_RECORDING") playedOnce = true
        return when (state) {
            "PLAYING" -> media(ReceiverState.Playing, null, nowMs)
            "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> media(ReceiverState.Paused, null, nowMs)
            "TRANSITIONING" -> media(ReceiverState.Buffering, null, nowMs)
            "STOPPED", "NO_MEDIA_PRESENT" -> media(ReceiverState.Idle, idleReason(error), nowMs)
            else -> media(ReceiverState.Idle, if (error) IdleReason.Error else null, nowMs)
        }
    }

    private fun idleReason(error: Boolean): IdleReason? = when {
        error -> IdleReason.Error
        stoppedByUs -> IdleReason.Cancelled
        // A STOPPED with no play behind it is the load still settling, which the mapper reads as loading.
        !playedOnce -> null
        nearEnd() -> IdleReason.Finished
        else -> IdleReason.Interrupted
    }

    /** Within the last 2% (or 10 s, if that is more) of a length that is known. */
    private fun nearEnd(): Boolean {
        val d = durationMs
        if (d <= 0) return false
        return place >= d - maxOf(d / 50, 10_000L)
    }

    private fun media(state: ReceiverState, reason: IdleReason?, nowMs: Long) =
        ReceiverMedia(state, reason, positionMs = place, atMs = nowMs)

    private fun believe(raw: Long?, moving: Boolean, ended: Boolean, nowMs: Long): Long {
        val expected = if (moving) place + (nowMs - playedAtMs).coerceAtLeast(0) else place
        if (raw == null) return keep(expected, moving, nowMs)
        if (ended) {
            // Zero from a stopped player is no word on the place; the end of the video is.
            return if (raw > 0) raw else place
        }
        val target = seekTarget
        if (target >= 0) {
            val near = kotlin.math.abs(raw - target) <= SEEK_TOLERANCE_MS
            val timedOut = nowMs - seekAskedAtMs > SEEK_PATIENCE_MS
            if (!near && !timedOut) return target
            // Arrived, or given up on: the TV's word is the place either way.
            seekTarget = -1
            playedAtMs = nowMs
            return raw
        }
        // Stepping back while it plays on, when nobody asked: the TV re-buffering, not the viewer's place.
        if (moving && raw < expected - BACKWARD_MS && playedOnce) return keep(expected, moving, nowMs)
        playedAtMs = nowMs
        return raw
    }

    private fun keep(expected: Long, moving: Boolean, nowMs: Long): Long {
        if (moving) playedAtMs = nowMs
        return if (durationMs > 0) expected.coerceAtMost(durationMs) else expected
    }

    private companion object {
        const val SEEK_TOLERANCE_MS = 3_000L
        const val SEEK_PATIENCE_MS = 8_000L
        const val BACKWARD_MS = 2_000L
    }
}
