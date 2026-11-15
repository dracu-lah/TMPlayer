package com.tmplayer.player

import com.tmplayer.data.PlayerEngine

/**
 * The mark the player leaves while a native engine plays (PR6): written before VLC starts, cleared
 * when it stops cleanly. libVLC crashes in native code with no Java exception, so a mark still
 * there at the next start is the only sign the process died inside it.
 *
 * [startedAtMs] is the wall clock when playing began, [sinceStartMs] how long the process had run
 * by then, which is what decides whether the crash was an early one for the start-up loop count.
 */
data class EngineMark(val engine: PlayerEngine, val startedAtMs: Long, val sinceStartMs: Long) {

    /** As written to the file: `vlc;1731650000000;4200`. */
    fun encode(): String = "${engine.key};$startedAtMs;$sinceStartMs"

    companion object {
        /** The mark in [text], or null for anything missing, cut short or not ours. */
        fun decode(text: String?): EngineMark? {
            if (text.isNullOrBlank() || text.length > 100) return null
            val parts = text.trim().split(';')
            if (parts.size != 3) return null
            val engine = PlayerEngine.ofKey(parts[0]) ?: return null
            val started = parts[1].trim().toLongOrNull()?.takeIf { it > 0 } ?: return null
            val since = parts[2].trim().toLongOrNull()?.takeIf { it >= 0 } ?: return null
            return EngineMark(engine, started, since)
        }
    }
}

/** How Android says the last process ended, as far as an engine crash goes. */
enum class LastExit {
    /** A crash in native code (Android 11 and later say so). */
    NativeCrash,

    /** Killed for not answering: a native engine wedged on the main thread ends this way too. */
    NotResponding,

    /** A Java crash: [com.tmplayer.data.CrashLoop] counts it already, and the trace names the code. */
    JavaCrash,

    /** Ended from outside: swiped away, low memory, an update, a force stop. Not the engine's fault. */
    Ended,

    /** Android before 11 keeps no list, or the list says nothing about that process. */
    Unknown,
}

/**
 * What the device has learned of VLC crashing here (PR6), stored by the player. [count] only goes
 * up; [offeredAt] is the count when the card offering ExoPlayer was last shown, so it is offered
 * again only after another crash.
 */
data class VlcCrashes(val count: Int = 0, val offeredAt: Int = 0) {

    fun crashed(): VlcCrashes = copy(count = (count + 1).coerceAtMost(MAX))

    fun offered(): VlcCrashes = copy(offeredAt = count)

    /** `2;1` as stored. */
    fun encode(): String = "$count;$offeredAt"

    companion object {
        val None = VlcCrashes()
        private const val MAX = 1_000

        /** The stored value, or [None] for anything unreadable; a bad part counts as 0. */
        fun decode(stored: String?): VlcCrashes {
            if (stored.isNullOrBlank() || stored.length > 40) return None
            val parts = stored.split(';')
            val count = parts.getOrNull(0)?.trim()?.toIntOrNull()?.coerceIn(0, MAX) ?: 0
            val offered = parts.getOrNull(1)?.trim()?.toIntOrNull()?.coerceIn(0, count) ?: 0
            return VlcCrashes(count, offered)
        }
    }
}

/**
 * Native crash isolation for the VLC engine (PR6). Pure, so the rules are a table test.
 *
 *  - A mark left from the last run ([EngineMark]) is a VLC crash when Android says the process
 *    crashed natively or stopped answering, and also when Android cannot say (before 11): a mark
 *    is only left by a process that never reached a clean stop, and guessing "crash" costs only
 *    the automatic choice of VLC, never the viewer's own.
 *  - It is not one when Android says the process was ended from outside or crashed in Java,
 *    nor for a mark older than [STALE_MS] (a phone off in a drawer for a week).
 *  - Automatic stops choosing VLC after [AUTOMATIC_LIMIT] crash ([EngineSelector] reads
 *    [EngineInput.vlcCrashes]); a viewer who picked VLC keeps it, and is offered ExoPlayer once
 *    per new crash from the [OFFER_AFTER]th on ([offerExoPlayer]).
 *  - The start-up safe mode counts the crash as an early one when VLC had begun within the
 *    loop's early window of its process starting ([earlyForLoop]).
 */
object EngineCrashes {

    /** One native crash and Automatic leaves VLC alone on this device. */
    const val AUTOMATIC_LIMIT = 1

    /** The viewer's own VLC is questioned from the second crash. */
    const val OFFER_AFTER = 2

    /** A mark older than this says nothing about the last run worth acting on. */
    const val STALE_MS = 7L * 24 * 60 * 60 * 1000

    fun isVlcCrash(mark: EngineMark?, exit: LastExit, nowMs: Long): Boolean {
        if (mark == null || mark.engine != PlayerEngine.Vlc) return false
        if (nowMs - mark.startedAtMs !in 0..STALE_MS) return false
        return when (exit) {
            LastExit.NativeCrash, LastExit.NotResponding, LastExit.Unknown -> true
            LastExit.JavaCrash, LastExit.Ended -> false
        }
    }

    /** Whether the crash goes into [com.tmplayer.data.CrashLoop]'s count of early ones, given its window. */
    fun earlyForLoop(mark: EngineMark, earlyMs: Long): Boolean = mark.sinceStartMs in 0..earlyMs

    /** The card that offers ExoPlayer to a viewer who picked VLC: from the second crash, once per crash. */
    fun offerExoPlayer(choice: EngineChoice, crashes: VlcCrashes): Boolean =
        choice == EngineChoice.Vlc && crashes.count >= OFFER_AFTER && crashes.count > crashes.offeredAt
}
