package com.tmplayer.desktop.player

import com.tmplayer.player.PlaybackSpeed
import com.tmplayer.player.StreamStats
import kotlin.math.abs
import kotlin.math.roundToInt

/** The arithmetic behind seeks, volume and speed, kept out of the composable so it can be tested. */
object SeekMath {

    /** Never seek into the very last frame, which mpv answers with an end of file. */
    const val END_GUARD_MS = 1_000L

    const val MIN_SPEED = 0.25f
    const val MAX_SPEED = 4f
    const val FINE_SPEED_STEP = 0.1f

    /** [position] moved by [delta], kept inside the video. */
    fun clampSeek(position: Long, delta: Long, duration: Long): Long =
        clampTarget(position + delta, duration)

    fun clampTarget(target: Long, duration: Long): Long {
        val floor = target.coerceAtLeast(0)
        return if (duration > END_GUARD_MS) floor.coerceAtMost(duration - END_GUARD_MS) else floor
    }

    /** 0 is the start, 5 halfway; null while the length is unknown, since a tenth of that is no place. */
    fun tenth(duration: Long, digit: Int): Long? =
        if (duration <= 0) null else duration * digit.coerceIn(0, 9) / 10

    /** The time under the cursor on a bar [width] wide, at [x]. */
    fun timeAt(x: Float, width: Float, duration: Long): Long {
        if (width <= 0f || duration <= 0) return 0
        return ((x / width).coerceIn(0f, 1f) * duration).toLong()
    }

    fun fraction(position: Long, duration: Long): Float =
        if (duration <= 0) 0f else (position.toFloat() / duration).coerceIn(0f, 1f)

    fun volume(current: Int, delta: Int): Int = (current + delta).coerceIn(0, 100)

    /**
     * A wheel turn: volume by default, a seek with Shift held or on a horizontal wheel. [notches]
     * is the scroll delta, positive away from the viewer as Compose reports it (down).
     */
    fun wheel(notches: Float, seek: Boolean, stepVolume: Int = PlayerKeys.VOLUME_STEP, stepMs: Long = PlayerKeys.SEEK_MEDIUM_MS): PlayerAction? {
        if (notches == 0f) return null
        val direction = if (notches < 0) 1 else -1
        return if (seek) PlayerAction.SeekBy(direction * stepMs) else PlayerAction.VolumeBy(direction * stepVolume)
    }

    /**
     * What a wheel event over the picture does, from its raw deltas.
     *
     * By default the vertical wheel is volume and Shift+wheel seeks; [wheelSeeks] (the setting)
     * swaps the two. Compose Desktop delivers Shift+wheel as a horizontal delta, so a horizontal
     * delta with Shift held is that swapped vertical wheel and keeps its sign; without Shift it is
     * a real horizontal wheel or a touchpad swipe, which always seeks, right going forward.
     */
    fun wheelAction(dx: Float, dy: Float, shift: Boolean, wheelSeeks: Boolean): PlayerAction? = when {
        dy != 0f -> wheel(dy, seek = shift != wheelSeeks)
        dx != 0f && shift -> wheel(dx, seek = !wheelSeeks)
        dx != 0f -> wheel(-dx, seek = true)
        else -> null
    }

    /** ] and [: a tenth at a time, rounded so 1.1 does not become 1.0999999. */
    fun fineSpeed(current: Float, up: Boolean): Float {
        val next = current + if (up) FINE_SPEED_STEP else -FINE_SPEED_STEP
        return ((next * 100).roundToInt() / 100f).coerceIn(MIN_SPEED, MAX_SPEED)
    }

    /** The chip: the same seven stops as the phone. */
    fun nextSpeedStop(current: Float): Float = PlaybackSpeed.next(current)

    fun speedLabel(speed: Float): String {
        val stop = PlaybackSpeed.CHOICES.firstOrNull { abs(it - speed) < 0.001f }
        if (stop != null) return PlaybackSpeed.label(stop)
        val text = "%.2f".format(java.util.Locale.ROOT, speed).trimEnd('0').trimEnd('.')
        return "${text}x"
    }

    fun clock(ms: Long): String = StreamStats.formatClock(ms)

    /** "-1:29:18": what is left, for the remaining time toggle. */
    fun remaining(position: Long, duration: Long): String =
        "-" + StreamStats.formatClock((duration - position).coerceAtLeast(0))
}
