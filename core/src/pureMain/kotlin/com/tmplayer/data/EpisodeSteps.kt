package com.tmplayer.data

import com.tmplayer.i18n.L

/**
 * One episode as a label names it: "S01E05", and its own name where one is known, from the online
 * lookup or from what the uploader wrote after the code.
 *
 * [show], [season] and [episode] are what the series view numbered it as, which is also what an
 * online lookup for its name asks for.
 */
data class EpisodeTag(
    val code: String,
    val name: String? = null,
    val show: String = "",
    val season: Int = 1,
    val episode: Int = 0,
) {
    /** "S01E05  ·  The Lighthouse", or the code alone. */
    val label: String get() = if (name.isNullOrBlank()) code else "$code  ·  $name"
}

/**
 * The episodes either side of the one playing, with what to call each, nulls where there are none.
 * The phone, the television and the desktop all read their buttons, their next-up card and their
 * autoplay off this one answer, so the three always agree on what comes next.
 */
data class EpisodeSteps(
    val previous: MediaItem? = null,
    val next: MediaItem? = null,
    /** The episode playing, as the series view numbers it; null for a film. */
    val current: EpisodeTag? = null,
    val previousTag: EpisodeTag? = null,
    val nextTag: EpisodeTag? = null,
    /**
     * The whole show the episode belongs to, as the series view groups it, for the player's
     * episode list. Null for a film, and until the chat has answered.
     */
    val series: Series? = null,
)

/**
 * The episode before and after one video, in the order the series view lists them.
 *
 * Built on [SeriesShelf] rather than on a parse of the one file name, so the player steps through
 * exactly what the Series view shows: season by season and across the season break (the last of
 * season one leads to the first of season two), episodes named only in a caption or as a bare
 * "E5" once the chat has established the show, and one step per episode however many copies of it
 * were posted. Of those copies, the one most like the copy playing: see [EpisodeCopies.closest].
 */
object EpisodeNeighbours {

    /**
     * Where [current] sits among [items], the chat's videos. [current] need not be among them: a
     * search narrowed to the show's name can leave it out, and it is added for the grouping.
     */
    fun around(current: MediaItem, items: List<MediaItem>): EpisodeSteps {
        val all = if (items.any { it.id == current.id }) items else items + current
        val arranged = SeriesShelf.arrange(all)
        val show = arranged.asSequence()
            .filterIsInstance<ShelfEntry.Show>()
            .map { it.series }
            .firstOrNull { series -> series.episodes.any { holds(it, current) } }
            ?: return partsAround(current, arranged) ?: LessonSteps.around(current, all) ?: EpisodeSteps(current = tagOf(current))
        val episodes = show.episodes
        val at = episodes.indexOfFirst { holds(it, current) }
        val before = episodes.getOrNull(at - 1)
        val after = episodes.getOrNull(at + 1)
        fun copyOf(episode: SeriesEpisode) = EpisodeCopies.closest(current, episode.copies) ?: episode.item
        val previous = before?.let(::copyOf)
        val next = after?.let(::copyOf)
        return EpisodeSteps(
            previous = previous,
            next = next,
            current = tag(episodes[at], current, show.title),
            previousTag = before?.let { tag(it, previous!!, show.title) },
            nextTag = after?.let { tag(it, next!!, show.title) },
            series = show,
        )
    }

    /**
     * The pieces either side of [current] when it is one piece of a split film ("CD1", "CD2"):
     * the player rolls on to the next piece as it would to the next episode, with the same
     * button, card and autoplay. Null when [current] is no piece of one.
     */
    private fun partsAround(current: MediaItem, arranged: List<ShelfEntry>): EpisodeSteps? {
        val pieces = arranged.asSequence()
            .filterIsInstance<ShelfEntry.File>()
            .flatMap { it.item.versions.ifEmpty { listOf(it.item) } }
            .map { it.parts }
            .firstOrNull { parts -> parts.any { it.id == current.id } }
            ?: return null
        val at = pieces.indexOfFirst { it.id == current.id }
        fun tag(index: Int) = EpisodeTag(code = L.gridPartNumber(index + 1))
        val previous = pieces.getOrNull(at - 1)
        val next = pieces.getOrNull(at + 1)
        return EpisodeSteps(
            previous = previous,
            next = next,
            current = tag(at),
            previousTag = previous?.let { tag(at - 1) },
            nextTag = next?.let { tag(at + 1) },
        )
    }

    /**
     * The steps around [current] among the videos kept on this device ([kept]: the Downloads), for
     * when the chat cannot be asked (no connection, or a search that failed). Only the kept
     * videos of the same show count, and the answer is null when none of them is next to [current],
     * so the caller keeps what it had.
     */
    fun amongKept(current: MediaItem, show: String, kept: List<MediaItem>): EpisodeSteps? {
        val same = kept.filter { it.id != current.id && showOf(it)?.equals(show, ignoreCase = true) == true }
        if (same.isEmpty()) return null
        return around(current, same).takeIf { it.previous != null || it.next != null }
    }

    /** Pages of a chat read at most for one lookup: forty messages of each kind a page. */
    const val MAX_PAGES = 8

    /**
     * [around], read from the chat a page at a time: the search for [show] first, then the plain
     * listing when that finds no neighbour at all, for a chat that names its files some other way.
     * Each keeps paging until both neighbours are found, its results run out or [MAX_PAGES] is
     * read, so an episode posted more than a page away from the one playing is still found.
     * [fetch] answers one page for a query from the cursors given, or null when it failed.
     */
    suspend fun lookUp(
        current: MediaItem,
        show: String,
        fetch: suspend (query: String, cursors: MediaCursors) -> MediaPage?,
    ): EpisodeSteps {
        var best = EpisodeSteps(current = tagOf(current))
        for (query in listOf(show, "")) {
            val items = mutableListOf<MediaItem>()
            var cursors = MediaCursors()
            var steps = best
            for (read in 0 until MAX_PAGES) {
                val page = fetch(query, cursors) ?: break
                items += page.items
                steps = around(current, items)
                if (steps.previous != null && steps.next != null) return steps
                if (page.endReached || page.cursors.allDone || page.cursors == cursors) break
                cursors = page.cursors
            }
            if (steps.previous != null || steps.next != null) return steps
            best = steps
        }
        return best
    }

    /**
     * The show a video belongs to, as its own name or caption says, or null for a film: what the
     * chat is searched for. A bare "E5" counts once a show is named with it.
     */
    fun showOf(item: MediaItem): String? {
        tagOf(item)?.show?.takeIf { it.isNotBlank() }?.let { return it }
        val caption = item.caption.lineSequence().firstOrNull { it.isNotBlank() }
        return listOfNotNull(item.nameForParsing.ifBlank { null }, caption)
            .firstNotNullOfOrNull { MediaName.looseEpisode(it) }?.title?.takeIf { it.isNotBlank() }
            // Not a show, but a numbered course: "Lecture 5", "Part 3", "Day 14". The search is for
            // the course's name, and empty when there is none, which lists the chat plainly.
            ?: LessonSteps.lessonOf(item)?.searchFor
    }

    /**
     * What one video says about itself, before the chat has been asked: its code and name from its
     * own file name or caption, or null when it names no episode.
     */
    fun tagOf(item: MediaItem): EpisodeTag? {
        val parsed = SeriesShelf.episodeOf(item)
        val number = parsed.episode ?: return null
        val code = parsed.episodeCode ?: return null
        return EpisodeTag(code, nameOf(item), parsed.title, parsed.season ?: 1, number)
    }

    /**
     * The name the uploader gave the episode after its code, from the file name first and the
     * caption's first line second, or null.
     */
    fun nameOf(item: MediaItem): String? {
        val caption = item.caption.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        return listOfNotNull(item.nameForParsing.ifBlank { null }, caption)
            .firstNotNullOfOrNull { MediaName.episodeName(it) }
    }

    private fun holds(episode: SeriesEpisode, item: MediaItem) = episode.copies.any { it.id == item.id }

    private fun tag(episode: SeriesEpisode, item: MediaItem, show: String) =
        EpisodeTag(episode.code, nameOf(item), show, episode.season, episode.episode)
}

/**
 * Study videos and other numbered runs that are not television: "Lecture 5", "Lec-05", "Lesson 4",
 * "Class 7", "Part 3", "Chapter 2", "Module 6", "Day 14", "Session 9", "Video 10".
 *
 * Kept apart from [MediaName]'s episode reading on purpose. Taught there, "Class 12" would make
 * every grade-twelve physics video an episode and fill the Series view with courses. Here it is
 * only the player's question, what comes next, asked once nothing reads as a show.
 *
 * The number read is the last one in the name, since that is the finest step: "Physics Class 12
 * Chapter 3 Lecture 2" is lecture 2, and its neighbours are lectures 1 and 3 of the same chapter.
 * Everything before it is the course, and a neighbour must share it word for word, or lecture 3 of
 * one chapter would follow lecture 2 of another. A name with no lesson word in it reads nothing.
 */
object LessonSteps {

    /** One video's place in a run: the course before the number, the word, the number. */
    data class Lesson(val course: String, val word: String, val number: Int, val name: String?) {
        /** What to search the chat for to find the rest of the run: the course, as written. */
        val searchFor: String get() = course
        val code: String get() = "${word.replaceFirstChar { it.uppercase() }} $number"
    }

    fun lessonOf(item: MediaItem): Lesson? {
        // "Name.part003" is a byte piece of one video, not lesson three of a course.
        if (item.splitPiece != null) return null
        val caption = item.caption.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        return listOfNotNull(item.nameForParsing.ifBlank { null }, caption).firstNotNullOfOrNull(::read)
    }

    /** The lessons either side of [current] among [items], or null when it is not one. */
    fun around(current: MediaItem, items: List<MediaItem>): EpisodeSteps? {
        val here = lessonOf(current) ?: return null
        val run = items.mapNotNull { item -> lessonOf(item)?.let { item to it } }
            .filter { (_, lesson) -> lesson.word == here.word && key(lesson.course) == key(here.course) }
        // The nearest number either side, not exactly one more: a run with a lecture missing still
        // steps on. Of two copies of one lecture, the newest post.
        val previous = run.filter { it.second.number < here.number }
            .maxWithOrNull(compareBy({ it.second.number }, { it.first.messageId }))
        val next = run.filter { it.second.number > here.number }
            .minWithOrNull(compareBy<Pair<MediaItem, Lesson>> { it.second.number }.thenByDescending { it.first.messageId })
        if (previous == null && next == null) return null
        fun tag(lesson: Lesson) = EpisodeTag(lesson.code, lesson.name, lesson.course, 1, lesson.number)
        return EpisodeSteps(
            previous = previous?.first,
            next = next?.first,
            current = tag(here),
            previousTag = previous?.second?.let(::tag),
            nextTag = next?.second?.let(::tag),
        )
    }

    internal fun read(text: String): Lesson? {
        val clean = text.substringBeforeLast('.').takeIf { '.' in text && text.substringAfterLast('.').length in 2..4 }
            ?: text
        val spaced = clean.replace(SEPARATORS, " ").replace(SPACES, " ").trim()
        val match = LESSON.findAll(spaced).lastOrNull() ?: return null
        val number = match.groupValues[2].toIntOrNull() ?: return null
        val course = spaced.substring(0, match.range.first).trim().trimEnd('-', ':', '|', ',').trim()
        val name = spaced.substring(match.range.last + 1).trim().trimStart('-', ':', '|', ',', '.').trim()
            .takeIf { it.isNotEmpty() && it.any(Char::isLetter) }
        return Lesson(course, match.groupValues[1].lowercase().let { WORDS[it] ?: it }, number, name)
    }

    /** The course as compared: case and punctuation do not part two runs. */
    internal fun key(course: String) = course.lowercase().replace(NOT_WORD, "")

    private val SEPARATORS = Regex("""[._]+""")
    private val SPACES = Regex("""\s+""")
    private val NOT_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val LESSON = Regex(
        """\b(lecture|lec|lesson|class|part|pt|chapter|ch|module|mod|day|session|unit|video|vid|tutorial|tut|week)[\s#:-]*0*(\d{1,3})(?!\d)""",
        RegexOption.IGNORE_CASE,
    )

    /** Short forms read as the word they stand for, so "Lec 4" and "Lecture 5" are one run. */
    private val WORDS = mapOf(
        "lec" to "lecture", "pt" to "part", "ch" to "chapter", "mod" to "module", "vid" to "video", "tut" to "tutorial",
    )
}
