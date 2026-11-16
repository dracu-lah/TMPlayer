package com.tmplayer.online

/**
 * How the guide's per-source answers are kept between runs (T11). The stores keep lists of
 * [MetaEpisode] under a key for a day ([MetaKeep.SEASON_TTL_MS]); each answer the guide needs
 * (AniList's run, TMDB's seasons and groups, the ids the sources were found by) is written as such
 * a list, so a source asked once is not asked again within the day, and a half-finished guide
 * resumes where it stopped. A row with a negative season is a fact, not an episode.
 */
internal object AnimeStored {

    private const val RUN = -1
    private const val SEASON_COUNT = -2
    private const val GROUP = -3
    private const val ID = -4

    /** An id a source was found by, or [NONE] kept so a "no such show" is not asked again. */
    fun id(value: String?): List<MetaEpisode> = listOf(MetaEpisode(season = ID, number = 0, name = value ?: NONE))

    /** The id [id] kept: the id, null for a kept "none", or [MISSING] when nothing was kept. */
    fun readId(rows: List<MetaEpisode>?): String? {
        val name = rows?.firstOrNull { it.season == ID }?.name ?: return MISSING
        return name.takeIf { it != NONE }
    }

    const val NONE = "none"

    /** Nothing kept: told apart from a kept "none" by identity, never equal to an id. */
    const val MISSING = "\u0000missing"

    fun run(run: AniListRun): List<MetaEpisode> {
        val next = run.next
        val head = MetaEpisode(
            season = RUN,
            number = run.episodes ?: 0,
            name = run.malId.orEmpty(),
            overview = if (next == null) "" else "${next.episode}|${next.airDate}",
            airDate = run.startDate,
        )
        return listOf(head) + run.streaming.map {
            MetaEpisode(season = 1, number = it.absolute, name = it.name, stillUrl = it.stillUrl)
        }
    }

    fun readRun(rows: List<MetaEpisode>?): AniListRun? {
        val head = rows?.firstOrNull { it.season == RUN } ?: return null
        val next = head.overview.split('|').takeIf { it.size == 2 }?.let { (n, date) ->
            n.toIntOrNull()?.let { MetaAiring(1, it, date) }
        }
        return AniListRun(
            episodes = head.number.takeIf { it > 0 },
            malId = head.name.ifBlank { null },
            next = next,
            streaming = rows.filter { it.season == 1 }.map { AnimeEpisodes.Piece(it.number, name = it.name, stillUrl = it.stillUrl) },
            startDate = head.airDate,
        )
    }

    fun showGuide(guide: TmdbShowGuide): List<MetaEpisode> =
        guide.seasons.map { MetaEpisode(season = SEASON_COUNT, number = it.number, overview = it.episodeCount.toString(), airDate = it.airDate) } +
            guide.groups.map { MetaEpisode(season = GROUP, number = it.type, name = it.id, runtimeMin = it.episodeCount.coerceAtLeast(1)) }

    fun readShowGuide(rows: List<MetaEpisode>?): TmdbShowGuide? {
        if (rows == null || rows.isEmpty()) return null
        return TmdbShowGuide(
            seasons = rows.filter { it.season == SEASON_COUNT }.map { MetaSeason(it.number, it.overview.toIntOrNull() ?: 0, it.airDate) },
            groups = rows.filter { it.season == GROUP }.map { TmdbEpisodeGroup(it.name, it.number, it.runtimeMin ?: 0) },
        )
    }

    /** A TMDB group's episodes in the order the group gives, each with its season and number there. */
    fun group(episodes: List<AnimeEpisodes.GroupEpisode>): List<MetaEpisode> =
        AnimeEpisodes.fromGroup(episodes).pieces.map {
            MetaEpisode(it.season ?: 0, it.episode ?: 0, it.name, it.overview, it.stillUrl, it.airDate, it.runtimeMin)
        }

    fun readGroup(rows: List<MetaEpisode>?): AnimeEpisodes.SourceList? {
        if (rows == null || rows.isEmpty()) return null
        val episodes = rows.mapIndexed { i, e ->
            AnimeEpisodes.GroupEpisode(0, i, e.season, e.number, AnimeEpisodes.Piece(0, e.name, e.overview, e.stillUrl, e.airDate, e.runtimeMin))
        }
        return AnimeEpisodes.fromGroup(episodes)
    }
}
