package com.tmplayer.desktop.os

/**
 * One press of a media key, counted once.
 *
 * With the player focused, a Play/Pause key can arrive twice: as a key event in the window, and
 * through the system media session (SMTC on Windows, MPRIS on Linux), in either order. The window
 * toggles while the session sends an explicit Play or Pause, so the wrong order pauses and then
 * resumes. Whichever path reports first owns the press; the other path's copy inside [WINDOW_MS]
 * is dropped. Two presses from the same path are never merged, so a real double tap still works.
 */
object MediaKeyEcho {
    const val WINDOW_MS = 400L

    enum class Source { Keyboard, Session }

    private var lastSource: Source? = null
    private var lastAt = 0L

    /** True when this play or pause should act, false when it is the other path's echo. */
    @Synchronized
    fun claim(source: Source, nowMs: Long = System.nanoTime() / 1_000_000): Boolean {
        val echo = lastSource != null && lastSource != source && nowMs - lastAt in 0 until WINDOW_MS
        if (echo) {
            // Consumed: a third copy should not find this one to pair with.
            lastSource = null
            return false
        }
        lastSource = source
        lastAt = nowMs
        return true
    }

    @Synchronized
    internal fun reset() {
        lastSource = null
        lastAt = 0L
    }
}
