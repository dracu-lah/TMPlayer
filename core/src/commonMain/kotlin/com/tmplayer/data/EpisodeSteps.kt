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
        val show = SeriesShelf.arrange(all).asSequence()
            .filterIsInstance<ShelfEntry.Show>()
            .map { it.series }
            .firstOrNull { series -> series.episodes.any { holds(it, current) } }
            ?: return EpisodeSteps(current = tagOf(current))
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
     * The show a video belongs to, as its own name or caption says, or null for a film: what the
     * chat is searched for. A bare "E5" counts once a show is named with it.
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
