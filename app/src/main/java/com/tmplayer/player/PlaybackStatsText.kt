package com.tmplayer.player

import com.tmplayer.i18n.L
import java.util.Locale

/**
 * The playback stats overlay's two lines (PR5, Settings, Playback, Advanced): the engine, the
 * decoder and whether it is hardware or software, then the dropped frames, the buffer ahead, the
 * wait on TDLib in the last second and the speed. Pure, so the wording and the limits are tested
 * without a device. Nothing here is a title, a file or a chat.
 */
object PlaybackStatsText {

    /** What one tick read from the player. [hardware] null while the decoder is not known yet. */
    data class Readout(
        val engine: String,
        val decoder: String,
        val hardware: Boolean?,
        val droppedFrames: Int,
        val bufferAheadMs: Long,
        /** Milliseconds blocked waiting for TDLib's bytes since the tick before. */
        val readWaitMs: Long,
        val speed: Float,
    )

    /** The lines to draw: the engine line is left out when it has nothing, the figures line always has all four. */
    fun lines(r: Readout): List<String> {
        val first = buildList {
            add(r.engine.trim().take(MAX_ENGINE))
            r.decoder.trim().take(MAX_DECODER).takeIf { it.isNotEmpty() }?.let { add(it) }
            r.hardware?.let { add(if (it) L.playerStatsHardware else L.playerStatsSoftware) }
        }.filter { it.isNotEmpty() }
        val second = listOf(
            L.playerStatsDropped(count = r.droppedFrames.coerceAtLeast(0).coerceAtMost(MAX_FIGURE)),
            L.playerStatsBuffer(seconds = seconds(r.bufferAheadMs)),
            L.playerStatsWait(ms = r.readWaitMs.coerceAtLeast(0L).coerceAtMost(MAX_FIGURE.toLong()).toString()),
            speed(r.speed),
        )
        return listOfNotNull(first.takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR), second.joinToString(SEPARATOR))
    }

    /** Seconds with one decimal under a minute's worth, whole above; never negative, capped at an hour. */
    fun seconds(ms: Long): String {
        val s = ms.coerceIn(0L, MAX_BUFFER_MS) / 1000.0
        return if (s < 100) String.format(Locale.ROOT, "%.1f", s) else s.toInt().toString()
    }

    private val SPEED_TRAILING_ZERO = Regex("(\\.\\d)0x$")

    /** `1.0x`; a speed that is not a number or not above zero reads as 1.0x. */
    fun speed(value: Float): String {
        val v = if (value.isNaN() || value.isInfinite() || value <= 0f) 1f else value.coerceAtMost(MAX_SPEED)
        return String.format(Locale.ROOT, "%.2fx", v).replace(SPEED_TRAILING_ZERO, "$1x")
    }

    /**
     * Lets one update through in each [minGapMs] and no more, so a caller that ticks faster (a seek,
     * a resume, a layout) still costs one redraw a second at most.
     */
    class Throttle(private val minGapMs: Long = ONE_SECOND_MS) {
        private var last = Long.MIN_VALUE

        fun allow(nowMs: Long): Boolean {
            if (last != Long.MIN_VALUE && nowMs - last in 0 until minGapMs) return false
            last = nowMs
            return true
        }

        fun reset() {
            last = Long.MIN_VALUE
        }
    }

    const val ONE_SECOND_MS = 1_000L
    private const val SEPARATOR = "  ·  "
    private const val MAX_ENGINE = 20
    private const val MAX_DECODER = 64
    private const val MAX_FIGURE = 9_999_999
    private const val MAX_BUFFER_MS = 3_600_000L
    private const val MAX_SPEED = 16f
}
