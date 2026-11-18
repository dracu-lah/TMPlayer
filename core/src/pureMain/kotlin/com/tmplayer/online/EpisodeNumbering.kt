package com.tmplayer.online

/**
 * Where a chat's episode numbers sit in the provider's seasons, for the names and pictures of a
 * show whose files do not count the way the provider does.
 *
 * The provider counts each season from one ("S02E01" to "S02E12"). Uploads often do not: a season
 * labelled 2 carries "S02E13" to "S02E24" because the numbers run on from season 1, or a whole
 * show is "Ep 01" to "Ep 24" with no season at all. Asked for episode 13 of season 2 the provider
 * has none, so season 1 (which counts the same way for both) shows its names and stills and every
 * season past it shows none (issue 31). A season the chat holds mostly past the provider's own
 * count for it is read as counted straight through, and each number is placed by the provider's
 * season sizes. Anything else is left exactly as it was.
 */
object EpisodeNumbering {

    /** Most provider seasons one chat season is read against: a 500 episode show asks for these, not every season. */
    const val MAX_SEASONS = 12

    /** The largest episode number placed; a bigger one is a garbage name, not an episode. */
    private const val MAX_NUMBER = 9999

    /** The numbered seasons of [layout] with an episode count, in order, one each. */
    private fun counts(layout: List<MetaSeason>): List<MetaSeason> =
        layout.asSequence()
            .filter { it.number in 1..EpisodeNumberingLimits.SEASONS && it.episodeCount in 1..EpisodeNumberingLimits.EPISODES }
            .distinctBy { it.number }
            .sortedBy { it.number }
            .take(EpisodeNumberingLimits.SEASONS)
            .toList()

    /**
     * Whether the chat's [numbers] of [season] are counted straight through rather than from one:
     * at least half of them lie past the provider's count for that season. A season the provider
     * does not list, the specials, and a season with one stray number past its end are not.
     */
    fun counted(season: Int, numbers: Collection<Int>, layout: List<MetaSeason>): Boolean {
        if (season < 1 || numbers.isEmpty()) return false
        val own = counts(layout).firstOrNull { it.number == season } ?: return false
        val past = numbers.count { it > own.episodeCount }
        return past > 0 && past * 2 >= numbers.size
    }

    /**
     * The provider's season and episode for episode [number] of the chat's [season], or null where
     * it falls outside every season the provider lists. For a season that is not [counted] it is
     * the pair as written.
     */
    fun place(season: Int, number: Int, numbers: Collection<Int>, layout: List<MetaSeason>): Pair<Int, Int>? {
        if (number !in 1..MAX_NUMBER) return null
        if (!counted(season, numbers, layout)) return season to number
        val seasons = counts(layout)
        val own = seasons.firstOrNull { it.number == season }
        if (own != null && number <= own.episodeCount) return season to number
        var before = 0
        for (s in seasons) {
            if (number <= before + s.episodeCount) return s.number to number - before
            before += s.episodeCount
        }
        return null
    }

    /**
     * The provider's seasons whose lists name the chat's [numbers] of [season], in order and at most
     * [MAX_SEASONS]: just [season] unless it is [counted].
     */
    fun seasonsFor(season: Int, numbers: Collection<Int>, layout: List<MetaSeason>): List<Int> {
        if (!counted(season, numbers, layout)) return listOf(season)
        return numbers.asSequence().sorted().mapNotNull { place(season, it, numbers, layout)?.first }.distinct().take(MAX_SEASONS).toList()
            .ifEmpty { listOf(season) }
    }

    /**
     * The provider's episodes from [lists] (its seasons' lists, by season) under the chat's own
     * numbers for [season]: each of [numbers] with the name, still and words of the episode it
     * stands for. A season that is not [counted] answers its own list as it is.
     */
    fun rekey(season: Int, numbers: Collection<Int>, layout: List<MetaSeason>, lists: Map<Int, List<MetaEpisode>>): List<MetaEpisode> {
        if (!counted(season, numbers, layout)) return lists[season].orEmpty()
        val byPlace = HashMap<Pair<Int, Int>, MetaEpisode>()
        for ((s, list) in lists) for (e in list) byPlace[s to e.number] = e
        return numbers.sorted().mapNotNull { n ->
            val at = place(season, n, numbers, layout) ?: return@mapNotNull null
            byPlace[at]?.copy(season = season, number = n)
        }
    }
}

/** The bounds [EpisodeNumbering] reads a provider's layout under. */
internal object EpisodeNumberingLimits {
    const val SEASONS = 200
    const val EPISODES = 3000
}
