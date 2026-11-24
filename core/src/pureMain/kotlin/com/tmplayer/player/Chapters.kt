package com.tmplayer.player

import com.tmplayer.i18n.L

/** One chapter mark of the file: where it starts, and its name when the file gives one. */
data class Chapter(val startMs: Long, val title: String? = null)

/** One line of the Chapters page: its place in the sorted list, its words, where it starts, and whether it is the one playing. */
data class ChapterRow(val index: Int, val title: String, val startMs: Long, val current: Boolean)

/**
 * Chapter navigation, the way mpv and every disc player do it: forward goes to the next mark,
 * back goes to the start of the chapter playing, or to the one before when that start was only
 * just passed.
 *
 * Shared by the desktop, where mpv reads the marks, and the Android player, where [MkvChapters]
 * does (Media3 reads none). Pure, so the arithmetic is tested rather than found out on a video
 * with twenty chapters.
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

    /** The Chapters page is offered from this many marks: one mark is only the start of the video. */
    const val LIST_FROM = 2

    /**
     * How close to the end a chapter seek may land: a seek to the very end, or past it, ends the
     * video, marks it watched and may start the next one. The players' seeks keep the same second.
     */
    const val END_GUARD_MS = 1_000L

    /** The most lines the Chapters page lists: a broken file with 10,000 marks still opens it at once. */
    const val MAX_ROWS = 1_000

    /** The most characters of a chapter's name a line shows; a longer one is cut with an ellipsis. */
    const val MAX_TITLE = 200

    /** Whether the file has enough chapters for a page of them, counting only those inside [durationMs] when it is known. */
    fun listable(chapters: List<Chapter>, durationMs: Long = 0L): Boolean = within(chapters, durationMs).size >= LIST_FROM

    /**
     * The marks a seek may go to, sorted by start: a malformed file's mark at or past the end
     * ([END_GUARD_MS] before it) is left out once [durationMs] is known, since a seek there ends the
     * video. With the length unknown (0 or less) every mark stays.
     */
    fun inside(chapters: List<Chapter>, durationMs: Long): List<Chapter> = within(chapters, durationMs).sortedBy { it.startMs }

    private fun within(chapters: List<Chapter>, durationMs: Long): List<Chapter> =
        if (durationMs <= 0L) chapters else chapters.filter { it.startMs < durationMs - END_GUARD_MS }

    /** Where a seek to a chapter that starts at [startMs] lands: from zero, and never past the end of a video of known length. */
    fun seekTarget(startMs: Long, durationMs: Long): Long {
        val floor = startMs.coerceAtLeast(0L)
        return if (durationMs > END_GUARD_MS) floor.coerceAtMost(durationMs - END_GUARD_MS) else floor
    }

    /**
     * The lines of the Chapters page: sorted by start, one per start (a file that lists two marks at
     * one time keeps the first), a start below zero taken as zero, and a chapter with no name worth
     * showing numbered ("Chapter 3"). [positionMs] marks the one playing; none is marked before
     * the first mark. The parsers sort already, and the desktop's mpv gives its own order: this is
     * the page's own guard, so a broken file shows a list that still reads and jumps. A mark at or
     * past the end of a video of known [durationMs] is left out ([inside]), at most [MAX_ROWS]
     * lines are listed, and a name longer than [MAX_TITLE] is cut.
     */
    fun rows(chapters: List<Chapter>, positionMs: Long, durationMs: Long = 0L): List<ChapterRow> {
        val sorted = within(chapters, durationMs)
            .map { Chapter(it.startMs.coerceAtLeast(0L), it.title) }
            .sortedBy { it.startMs }
            .distinctBy { it.startMs }
            .take(MAX_ROWS)
        val playing = at(sorted, positionMs)
        return sorted.mapIndexed { index, chapter ->
            val name = chapter.title?.trim()?.takeIf { it.isNotEmpty() }?.let(::cut)
            ChapterRow(index, name ?: L.playerChapterUntitled(index + 1), chapter.startMs, index == playing)
        }
    }

    private fun cut(name: String): String = if (name.length <= MAX_TITLE) name else name.take(MAX_TITLE - 1).trimEnd() + "\u2026"

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

    /**
     * The chapter's own name for a line beside the time, or "Chapter 3 of 12" where the file has
     * none worth reading; null before the first mark.
     */
    fun labelAt(chapters: List<Chapter>, positionMs: Long): String? {
        val index = at(chapters, positionMs)
        return if (index < 0) null else label(chapters, index)
    }

    /**
     * Where the bar draws a mark: every chapter start but one at the very beginning, which would
     * only be a notch at the bar's own end. Inside [durationMs] when it is known.
     */
    fun marks(chapters: List<Chapter>, durationMs: Long): List<Long> =
        chapters.map { it.startMs }.filter { it > 0 && (durationMs <= 0 || it < durationMs) }.distinct()

    /** "Chapter 03", "Chapter 3": a name that says nothing the count does not. */
    private fun isGeneric(name: String): Boolean = Regex("chapter\\s*\\d+", RegexOption.IGNORE_CASE).matches(name)
}
