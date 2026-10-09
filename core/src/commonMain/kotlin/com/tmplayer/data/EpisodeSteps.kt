package com.tmplayer.data

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
    /** The order [previous] and [next] were read in: see [EpisodeOrder]. */
    val order: EpisodeOrder = EpisodeOrder.Number,
)

/**
 * Which episode comes next. [Number] is the series view's own order, season by season. [Upload]
 * follows the chat instead, oldest post first, for a channel that posts a show out of its
 * numbering (a recap, a special, a second cut of an episode). Chosen per series in the player's
 * episode list and remembered there.
 */
enum class EpisodeOrder { Number, Upload }

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
    fun around(current: MediaItem, items: List<MediaItem>, order: EpisodeOrder = EpisodeOrder.Number): EpisodeSteps {
        val all = if (items.any { it.id == current.id }) items else items + current
        val show = SeriesShelf.arrange(all).asSequence()
            .filterIsInstance<ShelfEntry.Show>()
            .map { it.series }
            .firstOrNull { series -> series.episodes.any { holds(it, current) } }
            ?: return EpisodeSteps(current = tagOf(current), order = order)
        val episodes = ordered(show, order)
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
            order = order,
        )
    }

    /**
     * [series]' episodes in the order the next one is chosen by. By upload, an episode is placed by
     * its first post, so a later second copy of it does not move it to the end.
     */
    fun ordered(series: Series, order: EpisodeOrder): List<SeriesEpisode> = when (order) {
        EpisodeOrder.Number -> series.episodes
        EpisodeOrder.Upload -> series.episodes.sortedWith(
            compareBy<SeriesEpisode> { episode -> episode.copies.minOf { it.date } }
                .thenBy { episode -> episode.copies.minOf { it.messageId } },
        )
    }

    /**
     * The show a video belongs to, as its own name or caption says, or null for a film: what the
     * chat is searched for, and what the per series choices ([EpisodeOrder], the intro's end) are
     * filed under. A bare "E5" counts once a show is named with it.
     */
    fun showOf(item: MediaItem): String? {
        tagOf(item)?.show?.takeIf { it.isNotBlank() }?.let { return it }
        val caption = item.caption.lineSequence().firstOrNull { it.isNotBlank() }
        return listOfNotNull(item.fileName.ifBlank { item.title }.ifBlank { null }, caption)
            .firstNotNullOfOrNull { MediaName.looseEpisode(it) }?.title?.takeIf { it.isNotBlank() }
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
        return listOfNotNull(item.fileName.ifBlank { item.title }.ifBlank { null }, caption)
            .firstNotNullOfOrNull { MediaName.episodeName(it) }
    }

    private fun holds(episode: SeriesEpisode, item: MediaItem) = episode.copies.any { it.id == item.id }

    private fun tag(episode: SeriesEpisode, item: MediaItem, show: String) =
        EpisodeTag(episode.code, nameOf(item), show, episode.season, episode.episode)
}

/**
 * Skip intro, learned rather than read: Telegram files carry no chapter for the intro, so the
 * viewer marks where it ends once ("Set intro end here" in the episode list), and every episode of
 * that show offers a jump to that point until playback passes it.
 */
object IntroSkip {

    /** Shorter than this is not an intro, and a mark this early is a slip of the finger. */
    const val MIN_END_MS = 5_000L

    /** The offer goes this long before the end, so the jump never lands a second short of it. */
    const val LEAD_MS = 2_000L

    /** Whether the Skip intro pill shows at [positionMs], for a show whose intro ends at [endMs]. */
    fun offers(positionMs: Long, endMs: Long?): Boolean =
        endMs != null && endMs >= MIN_END_MS && positionMs >= 0 && positionMs < endMs - LEAD_MS

    /** Whether [positionMs] can be saved as the intro's end. */
    fun canMark(positionMs: Long): Boolean = positionMs >= MIN_END_MS
}
