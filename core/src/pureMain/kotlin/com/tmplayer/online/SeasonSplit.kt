package com.tmplayer.online

/**
 * TMDB's own season split for a show it lists as one season. Blue Box (tv 207347) is one season of
 * 37 episodes on its page, while its "Seasons" episode group splits them 25 and 12, the way the
 * releases number them (S02E01 is the page's episode 26). Without the split a chat holding S02E01
 * disagrees with the page ([com.tmplayer.data.EpisodeGuide.agrees]) and the series page lists bare
 * numbers; with it, season 2 has its titles, pictures, dates and gaps.
 *
 * Pure: [choose] picks the group from the show's page, [build] turns the group's episodes into
 * seasons numbered from one, and [store] and [read] keep those episodes in the season cache. A show
 * TMDB already lists in several seasons is never split, so its numbering stays as it was.
 */
object SeasonSplit {

    /** The share of the show's episodes a group must hold to stand for the whole show. */
    private const val SHARE_TENTHS = 9

    /** More seasons than any show has, and the longest one: the rest of a garbage answer is dropped. */
    const val MAX_SEASONS = 200
    const val MAX_EPISODES = 3000
    private const val MAX_NUMBER = 9999

    /** A row of [store] that starts the next group, its order in [MetaEpisode.number]. */
    private const val GROUP_MARK = -5

    /**
     * The episode group that splits [seasons] (the show's page, specials included or not) into
     * seasons, or null. Only for a show TMDB lists as exactly one numbered season; a group stands
     * for it when it holds at least 90% of that season's episodes (and not more than twice as many)
     * in more groups than one. "Absolute order" groups are left out: they are blocks of a hundred,
     * not seasons. The fewest groups wins, then a name starting with "Season", then the most episodes.
     */
    fun choose(seasons: List<MetaSeason>, groups: List<TmdbEpisodeGroup>): TmdbEpisodeGroup? {
        val numbered = seasons.filter { it.number > 0 }.distinctBy { it.number }
        if (numbered.size != 1) return null
        val total = numbered.sumOf { it.episodeCount.coerceIn(0, MAX_EPISODES) }
        if (total < 2) return null
        val least = (total * SHARE_TENTHS + 9) / 10
        return groups.asSequence()
            .take(TmdbApi.MAX_GROUPS)
            .filter { !it.absolute && it.id.isNotBlank() }
            .filter { it.episodeCount in least..total * 2 && it.groupCount in numbered.size + 1..MAX_SEASONS }
            .sortedWith(compareBy({ it.groupCount }, { !it.name.trim().startsWith("Season", ignoreCase = true) }, { -it.episodeCount }, { it.id }))
            .firstOrNull()
    }

    /**
     * [group]'s seasons, each with its episodes numbered from one in the group's order, and where
     * each of TMDB's own episodes landed. Specials (TMDB's season 0) are dropped, and an episode
     * the group files twice is taken once. Null when fewer than two seasons come out, or when the
     * group holds less than 90% of the [listed] episodes: then the page's own numbering stands.
     */
    fun build(group: String, episodes: List<AnimeEpisodes.GroupEpisode>, listed: List<MetaSeason>): Split? {
        val total = listed.filter { it.number > 0 }.sumOf { it.episodeCount.coerceIn(0, MAX_EPISODES) }
        val sorted = episodes.asSequence()
            .take(MAX_EPISODES * 2)
            .filter { it.season > 0 && it.episode in 1..MAX_NUMBER }
            .sortedWith(compareBy({ it.groupOrder }, { it.order }))
            .toList()
        val seen = HashSet<Pair<Int, Int>>()
        val byGroup = LinkedHashMap<Int, MutableList<AnimeEpisodes.GroupEpisode>>()
        for (e in sorted) if (seen.add(e.season to e.episode)) byGroup.getOrPut(e.groupOrder) { ArrayList() }.add(e)
        val groups = byGroup.values.asSequence().map { it.take(MAX_EPISODES) }.filter { it.isNotEmpty() }.take(MAX_SEASONS).toList()
        if (groups.size < 2) return null
        if (groups.sumOf { it.size } * 10 < total * SHARE_TENTHS) return null
        val places = HashMap<Pair<Int, Int>, Pair<Int, Int>>()
        val bySeason = LinkedHashMap<Int, List<MetaEpisode>>()
        val seasons = groups.mapIndexed { gi, list ->
            val season = gi + 1
            bySeason[season] = list.mapIndexed { i, e ->
                places[e.season to e.episode] = season to i + 1
                MetaEpisode(
                    season = season,
                    number = i + 1,
                    // TMDB's "Episode 32" for an untitled one numbers it in the show's own count, not the season's.
                    name = e.piece.name.takeUnless { EpisodeNames.placeholder(it, e.episode) }.orEmpty(),
                    overview = e.piece.overview,
                    stillUrl = e.piece.stillUrl,
                    airDate = e.piece.airDate,
                    runtimeMin = e.piece.runtimeMin,
                )
            }
            MetaSeason(season, list.size, list.mapNotNull { it.piece.airDate?.takeIf(String::isNotBlank) }.minOrNull())
        }
        return Split(group, seasons, bySeason, places)
    }

    /** A show's seasons as [SeasonSplit] makes them from one TMDB episode group. */
    class Split internal constructor(
        val group: String,
        val seasons: List<MetaSeason>,
        /** Each season's episodes, numbered from one. */
        val episodes: Map<Int, List<MetaEpisode>>,
        private val places: Map<Pair<Int, Int>, Pair<Int, Int>>,
    ) {
        /**
         * [airing] (TMDB's next episode, numbered its way) in the split's numbering. One the group
         * has not filed yet but that follows the last episode it has is counted on in the last
         * season; any other the split cannot place is null rather than labelled wrong.
         */
        fun next(airing: MetaAiring?): MetaAiring? {
            if (airing == null) return null
            places[airing.season to airing.episode]?.let { (season, number) -> return airing.copy(season = season, episode = number) }
            val last = seasons.lastOrNull() ?: return null
            val lastListed = places.keys.filter { it.first == airing.season }.maxByOrNull { it.second } ?: return null
            if (airing.episode <= lastListed.second || places[lastListed]?.first != last.number) return null
            val number = last.episodeCount + (airing.episode - lastListed.second)
            return if (number in 1..MAX_NUMBER) airing.copy(season = last.number, episode = number) else null
        }

        /** [extras] (TMDB's, of the show this split is of) with the split's seasons, TMDB's own kept beside. */
        fun applyTo(extras: MetaExtras): MetaExtras = extras.copy(
            seasons = seasons.size,
            seasonList = seasons,
            nextEpisode = next(extras.nextEpisode),
            seasonGroup = group,
            listedSeasons = extras.seasonList,
            listedNext = extras.nextEpisode,
        )
    }

    /** [episodes] (a group's, as [TmdbApi.episodeGroup] reads them) as rows the season cache keeps. */
    fun store(episodes: List<AnimeEpisodes.GroupEpisode>): List<MetaEpisode> {
        val out = ArrayList<MetaEpisode>()
        var group: Int? = null
        for (e in episodes.asSequence().take(MAX_EPISODES * 2).sortedWith(compareBy({ it.groupOrder }, { it.order }))) {
            if (e.groupOrder != group) {
                group = e.groupOrder
                out += MetaEpisode(season = GROUP_MARK, number = e.groupOrder)
            }
            out += MetaEpisode(e.season, e.episode, e.piece.name, e.piece.overview, e.piece.stillUrl, e.piece.airDate, e.piece.runtimeMin)
        }
        // An empty group is kept too (a mark alone), so it is not asked for again within the day.
        if (out.isEmpty()) out += MetaEpisode(season = GROUP_MARK, number = 0)
        return out
    }

    /** What [store] kept, back as the group's episodes; null for nothing kept. */
    fun read(rows: List<MetaEpisode>?): List<AnimeEpisodes.GroupEpisode>? {
        if (rows.isNullOrEmpty()) return null
        var group = 0
        val out = ArrayList<AnimeEpisodes.GroupEpisode>()
        rows.asSequence().take(MAX_EPISODES * 2 + MAX_SEASONS).forEachIndexed { i, row ->
            if (row.season == GROUP_MARK) {
                group = row.number
            } else {
                out += AnimeEpisodes.GroupEpisode(
                    groupOrder = group,
                    order = i,
                    season = row.season,
                    episode = row.number,
                    piece = AnimeEpisodes.Piece(0, row.name, row.overview, row.stillUrl, row.airDate, row.runtimeMin),
                )
            }
        }
        return out
    }
}
