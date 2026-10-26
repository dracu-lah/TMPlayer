package com.tmplayer.data

import java.io.File

/**
 * Whether the app keeps crashing soon after it opens: after two runs in a row that crashed within
 * [EARLY_MS] of starting, the next start opens on the Chats page rather than Home, so a crash in
 * something Home draws cannot become a loop of crash, reopen, Home's loading screen, crash (HX8:
 * 1.28.1 on Windows crashed on a show drawn on Home, and the reporter saw "Loading your chats"
 * for hours).
 *
 * Early crashes are written to [file] from the uncaught exception handler, whatever the crash
 * reports switch says; a run that stays up [EARLY_MS] forgets them ([clean]), so what is kept is
 * always a run of early crashes back to back.
 */
class CrashLoop(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Counts a crash [sinceStartMs] into its run, if it was an early one. Never throws. */
    fun note(sinceStartMs: Long) {
        if (sinceStartMs > EARLY_MS) return
        runCatching {
            val kept = read().takeLast(KEPT - 1) + clock()
            file.parentFile?.mkdirs()
            file.writeText(kept.joinToString("\n"))
        }
    }

    /** True after two early crashes in a row, the last within [RECENT_MS]: open somewhere other than Home. */
    fun looping(): Boolean {
        val times = read()
        return times.size >= 2 && clock() - times.last() in 0..RECENT_MS
    }

    /** Forgets the crashes: this run has stayed up long enough. */
    fun clean() {
        runCatching { file.delete() }
    }

    private fun read(): List<Long> = runCatching {
        if (!file.isFile) emptyList() else file.readLines().mapNotNull { it.trim().toLongOrNull() }.sorted()
    }.getOrDefault(emptyList())

    companion object {
        /** A crash this soon after starting counts towards a loop; a run up this long ends one. */
        const val EARLY_MS = 2 * 60_000L

        /** A loop this long ago is over: the next start opens Home again. */
        const val RECENT_MS = 30 * 60_000L

        private const val KEPT = 5
    }
}
