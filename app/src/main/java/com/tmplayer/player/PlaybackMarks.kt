package com.tmplayer.player

import java.io.File
import java.io.IOException

/**
 * "Report stutter" (reliability plan L3): the viewer marks the moment, and the last minute of the
 * playback trace is kept for the next Send logs, so a report lines up with what they felt.
 *
 * Each mark is a window of [TraceSample]s (at most [WINDOW_MS] long) in a small file of its own, the
 * last [MAX_MARKS] of them, written through the same bounded ring as the trace. A mark made within
 * [DEBOUNCE_MS] of the last one is not a new one (a double press, or a press while a seek is still
 * settling), and a mark with nothing in its window (no reading yet, a seek right at the start) is
 * still kept: the moment is the news.
 *
 * Never throws: a full disk or a missing folder gives `false`.
 */
class PlaybackMarks(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {

    private var lastMarkAt = Long.MIN_VALUE

    /** What [mark] did. */
    enum class Result { KEPT, ALREADY, FAILED }

    /** Keeps [window] (cut to the last [WINDOW_MS] of it) as a mark made now. */
    @Synchronized
    fun mark(window: List<TraceSample>): Result {
        val now = clock()
        if (lastMarkAt != Long.MIN_VALUE && now - lastMarkAt in 0 until DEBOUNCE_MS) return Result.ALREADY
        return try {
            val prior = PlaybackTrace.read(readText(), maxSessions = MAX_MARKS - 1)
            // A trace's clock only runs forward, so a mark keeps just the readings after the last
            // mark's own: pressed again within the minute it adds what is new, and the minute
            // before is already in the earlier mark.
            val priorLast = prior.lastOrNull()?.let { it.samples.lastOrNull()?.atMs ?: it.startedAtMs } ?: 0L
            val fresh = lastMinute(window).filter { it.atMs > priorLast }.map { it.clamped() }
            val start = fresh.firstOrNull()?.atMs ?: maxOf(now, priorLast)
            val sessions = prior + TraceSession(start, fresh)
            file.parentFile?.mkdirs()
            val partial = File(file.path + ".part")
            partial.writeText(PlaybackTrace.encode(sessions), Charsets.UTF_8)
            if (!partial.renameTo(file)) {
                file.delete()
                if (!partial.renameTo(file)) throw IOException("marks could not be replaced")
            }
            lastMarkAt = now
            Result.KEPT
        } catch (e: Exception) {
            Result.FAILED
        }
    }

    /** The marks kept, oldest first. */
    @Synchronized
    fun marks(): List<TraceSession> = try {
        PlaybackTrace.read(readText(), maxSessions = MAX_MARKS)
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * The marks as lines for Send logs: when each was made, what the verdict says about its
     * window, then the readings in plain words ([TraceText]). Empty when nothing was marked.
     */
    fun reportLines(): List<String> {
        val kept = marks()
        if (kept.isEmpty()) return emptyList()
        return buildList {
            add("Playback trace: ${kept.size} moment(s) the viewer marked as stuttering, oldest first.")
            for (mark in kept) {
                val verdict = StutterVerdict.classify(mark.samples).name.lowercase()
                // The newest reading's time is the moment of the press, give or take one interval.
                val at = mark.samples.lastOrNull()?.atMs ?: mark.startedAtMs
                add("Marked at ${com.tmplayer.platform.FileLogSink.stamp(at)}, ${mark.samples.size} reading(s), verdict $verdict")
                addAll(TraceText.lines(mark.samples))
            }
        }
    }

    /** Removes the marks (sign-out, "Delete your data"). */
    @Synchronized
    fun delete() {
        runCatching { file.delete() }
        lastMarkAt = Long.MIN_VALUE
    }

    private fun readText(): String = if (file.isFile) file.readText(Charsets.UTF_8) else ""

    private fun lastMinute(window: List<TraceSample>): List<TraceSample> {
        val newest = window.maxOfOrNull { it.atMs } ?: return emptyList()
        return window.filter { it.atMs > newest - WINDOW_MS }.takeLast(MAX_READINGS)
    }

    companion object {
        const val NAME = "playback-marks.jsonl"
        const val WINDOW_MS = 60_000L
        const val MAX_MARKS = 5

        /** A minute at the fastest cadence (2 s) with room to spare. */
        const val MAX_READINGS = 40

        /** A second press this soon after the first is the same mark. */
        const val DEBOUNCE_MS = 5_000L

        /** The key that marks a stutter on a remote: a held Info. A plain press is left to whoever had it. */
        fun isMarkKey(keyCode: Int, isLongPress: Boolean): Boolean = isLongPress && keyCode == android.view.KeyEvent.KEYCODE_INFO

        fun fileIn(context: android.content.Context): File = File(context.filesDir, NAME)
    }
}

/**
 * A reading as one line of plain words for a log a person reads. Times are seconds from the
 * window's first reading, never a wall clock number: Send logs' scrubber takes any long number out,
 * and a reading's order is all a reader needs.
 */
object TraceText {

    fun lines(samples: List<TraceSample>): List<String> {
        val first = samples.firstOrNull()?.atMs ?: return emptyList()
        return samples.map { line(it, first) }
    }

    fun line(s: TraceSample, firstAtMs: Long): String {
        val c = s.clamped()
        val at = ((c.atMs - firstAtMs).coerceAtLeast(0L) / 1000).toString()
        return "  +${at}s dropped=${c.droppedFrames} underruns=${c.underruns} bufferMs=${c.bufferAheadMs} " +
            "bitrateKbps=${c.bitrateKbps} decoder=${c.decoder.ifEmpty { "?" }} ${if (c.hardware) "hardware" else "software"} " +
            "readWaitMs=${c.readWaitMs} opens=${c.reopens} cpu=${c.cpuPercent} thermal=${c.thermal} memory=${c.memoryLevel} " +
            "windowMs=${c.intervalMs} engine=${c.engine.ifEmpty { "?" }}"
    }
}
