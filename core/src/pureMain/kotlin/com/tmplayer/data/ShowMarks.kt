package com.tmplayer.data

/**
 * Mark as watched for a show (HX20): what a show's page and a show's menu offer, a season or the
 * whole show at a time, and which videos each choice marks or unmarks.
 *
 * Worked out here, away from any screen, so the phone, the television, the desktop and their
 * tests agree on it. A choice says how many episodes it covers, which is what the viewer confirms:
 * nothing is marked until a choice is picked, and the list names each one's count, since a show of
 * 500 episodes marked by a slip would bury the Watched list.
 */
object ShowMarks {

    /** What a choice covers. */
    enum class Scope { Season, Show }

    /**
     * One line of the choice: mark (or unmark) [episodes] of [scope]. [season] is the season's
     * number for [Scope.Season], else null.
     */
    data class Choice(
        val scope: Scope,
        val watched: Boolean,
        val season: Int?,
        val episodes: List<SeriesEpisode>,
    ) {
        /** How many episodes this changes: those not yet in the state it puts them in. */
        val count: Int get() = episodes.size
    }

    /**
     * The choices for [series] with [season] shown, in the order they are offered: the season
     * first (it is what the page shows), then the whole show when it has more than that season.
     * Each is offered only when it would change something: Mark watched while some of it is
     * unwatched, Mark unwatched while some is watched.
     *
     * @param finished whether an episode counts as watched (any copy, or a file that holds it).
     */
    fun choices(series: Series, season: SeriesSeason?, finished: (SeriesEpisode) -> Boolean): List<Choice> = buildList {
        val whole = series.episodes
        val wholeTodo = whole.filterNot(finished)
        val wholeDone = whole.filter(finished)
        val several = season == null || series.seasons.size > 1
        if (season != null) {
            val todo = season.episodes.filterNot(finished)
            val done = season.episodes.filter(finished)
            if (todo.isNotEmpty()) add(Choice(Scope.Season, true, season.number, todo))
            if (several && wholeTodo.isNotEmpty()) add(Choice(Scope.Show, true, null, wholeTodo))
            if (done.isNotEmpty()) add(Choice(Scope.Season, false, season.number, done))
            if (several && wholeDone.isNotEmpty()) add(Choice(Scope.Show, false, null, wholeDone))
        } else {
            if (wholeTodo.isNotEmpty()) add(Choice(Scope.Show, true, null, wholeTodo))
            if (wholeDone.isNotEmpty()) add(Choice(Scope.Show, false, null, wholeDone))
        }
    }

    /**
     * The videos to mark watched for [episodes]: one copy each, the one the list would play
     * ([pick]), since an episode counts as watched once any copy is ([SeriesEpisode.watched]).
     * Marking every copy of a show posted in three sizes would put each episode on the Watched
     * list three times.
     */
    fun toMark(episodes: List<SeriesEpisode>, pick: (SeriesEpisode) -> MediaItem): List<MediaItem> =
        episodes.map(pick).distinctBy { it.id }

    /**
     * The videos to unmark for [episodes]: every copy and every file of several episodes that holds
     * one, since any of them left marked keeps the episode ticked.
     */
    fun toUnmark(episodes: List<SeriesEpisode>): List<MediaItem> =
        episodes.flatMap { it.copies + it.alsoIn }.distinctBy { it.id }

    /** The other copies of [marked]'s episodes, which leave Watch later with them. */
    fun copiesOf(episodes: List<SeriesEpisode>, marked: List<MediaItem>): List<MediaItem> {
        val ids = marked.mapTo(HashSet()) { it.id }
        return episodes.flatMap { it.copies }.filterNot { it.id in ids }.distinctBy { it.id }
    }
}
