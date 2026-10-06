package com.tmplayer.desktop.player

import com.tmplayer.i18n.L
import java.io.File

/** One chapter mark of the file: where it starts, and its name when the file gives one. */
data class Chapter(val startMs: Long, val title: String? = null)

/**
 * Chapter navigation, the way mpv and every disc player do it: forward goes to the next mark,
 * back goes to the start of the chapter playing, or to the one before when that start was only
 * just passed.
 *
 * Pure, so the arithmetic is tested rather than found out on a film with twenty chapters.
 */
object Chapters {

    /**
     * Back from further into a chapter than this goes to its own start; closer, to the chapter
     * before. A second press straight after the first therefore keeps stepping back.
     */
    const val RESTART_AFTER_MS = 3_000L

    /**
     * Slack for a seek that lands a little short of a mark (mpv seeks to a keyframe), so the
     * chapter just jumped to still counts as the one playing.
     */
    const val LANDING_SLACK_MS = 1_000L

    /** The chapter playing at [positionMs], or -1 before the first mark. */
    fun at(chapters: List<Chapter>, positionMs: Long): Int =
        chapters.indexOfLast { it.startMs <= positionMs + LANDING_SLACK_MS }

    /** The chapter forward goes to, or null in the last one. */
    fun next(chapters: List<Chapter>, positionMs: Long): Int? =
        chapters.indexOfFirst { it.startMs > positionMs + LANDING_SLACK_MS }.takeIf { it >= 0 }

    /** The chapter back goes to, or null with no chapters at all (or none started yet). */
    fun previous(chapters: List<Chapter>, positionMs: Long): Int? {
        val current = at(chapters, positionMs)
        if (current < 0) return null
        val into = positionMs - chapters[current].startMs
        return if (into > RESTART_AFTER_MS) current else (current - 1).coerceAtLeast(0)
    }

    /** "Chapter 3 of 12" with its name after a colon, for the flash. */
    fun label(chapters: List<Chapter>, index: Int): String {
        val name = chapters.getOrNull(index)?.title?.trim()?.takeIf { it.isNotEmpty() && !isGeneric(it) }
        val count = L.playerChapterOf(index + 1, chapters.size)
        return if (name == null) count else L.playerChapterNamed(count, name)
    }

    /** "Chapter 03", "Chapter 3": a name that says nothing the count does not. */
    private fun isGeneric(name: String): Boolean = Regex("(?i)chapter\\s*\\d+").matches(name)
}

/**
 * A-B repeat: one key marks the start, the second the end and starts the loop, the third clears it.
 *
 * Only a whole loop goes to mpv. mpv given a start alone loops from the end of the file back to it,
 * which is not what a viewer who has marked one point is waiting for.
 */
data class AbLoop(val startMs: Long, val endMs: Long? = null) {

    val complete: Boolean get() = endMs != null

    companion object {
        /** Shorter than this is a stutter, not a loop; the second press is waited out instead. */
        const val MIN_LENGTH_MS = 500L

        /**
         * What the repeat key makes of [current] at [positionMs]. The two marks go in time order,
         * whichever was pressed first, and a second press too close to the first changes nothing.
         */
        fun press(current: AbLoop?, positionMs: Long): AbLoop? = when {
            current == null -> AbLoop(positionMs)
            current.endMs != null -> null
            kotlin.math.abs(positionMs - current.startMs) < MIN_LENGTH_MS -> current
            else -> AbLoop(minOf(current.startMs, positionMs), maxOf(current.startMs, positionMs))
        }
    }
}

/** Where a screenshot goes and what it is called. */
object ScreenshotFiles {

    /**
     * "Night Train 01-02-03.png" in [dir]: the video's name and the moment, a dash between the
     * parts of the time since a colon is not allowed in a Windows file name. A name already taken
     * gets " (2)", " (3)" and so on, so a second press on a paused frame keeps both.
     */
    fun fileFor(dir: File, title: String, positionMs: Long, exists: (File) -> Boolean = File::exists): File {
        val base = "${safeName(title)} ${clock(positionMs)}"
        var file = File(dir, "$base.png")
        var n = 2
        while (exists(file)) file = File(dir, "$base (${n++}).png")
        return file
    }

    /**
     * The title as a file name: no path separators or characters Windows refuses, no control
     * characters, no trailing dot or space, and not so long that a deep folder runs out of path.
     */
    fun safeName(title: String): String {
        val cleaned = title.replace(VIDEO_EXTENSION, "")
        val safe = cleaned.map { c -> if (c < ' ' || c in "\\/:*?\"<>|") ' ' else c }.joinToString("")
            .replace(Regex("\\s+"), " ")
            .take(MAX_NAME)
            .trim()
            .trimEnd('.', ' ')
        return safe.ifBlank { "TMPlayer" }
    }

    /** 1-02-03 for an hour or more, 02-03 below one. */
    fun clock(positionMs: Long): String {
        val total = (positionMs / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d-%02d-%02d".format(h, m, s) else "%02d-%02d".format(m, s)
    }

    private const val MAX_NAME = 80

    /** A video file's own extension on the end of a title, which the PNG's name has no use for. */
    private val VIDEO_EXTENSION = Regex("(?i)\\.(mkv|mp4|m4v|avi|webm|mov|ts|wmv|flv)$")
}
