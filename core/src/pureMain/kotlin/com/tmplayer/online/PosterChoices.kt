package com.tmplayer.online

import com.tmplayer.data.PosterPick

/**
 * One TMDB title offered by "Choose the right poster": enough to tell two films of one name apart
 * (a poster, the year, the language it was made in, a line of its story) and to force the match.
 */
data class PosterChoice(
    val tmdbId: Int,
    val show: Boolean,
    val title: String,
    val year: Int?,
    /** TMDB's `original_language`, ISO 639-1 ("ta"); empty when unknown. */
    val originalLanguage: String,
    val overview: String,
    val posterUrl: String?,
) {
    val pick: PosterPick get() = PosterPick(tmdbId, show)

    /** Whether this is the title [info] stands for: the one a tile is showing now. */
    fun isMatch(info: MetaInfo?): Boolean =
        info != null && info.provider == MetaProvider.Tmdb && info.id == tmdbId.toString() && (info.kind == MetaKind.Show) == show
}

/** What asking TMDB for the titles a video could be gave, so the picker can say it plainly. */
sealed interface PosterChoices {
    /** Up to [PosterChoiceRules.LIMIT] titles, closest first. May be empty: TMDB knows nothing of the name. */
    data class Found(val choices: List<PosterChoice>) : PosterChoices

    /** Lookups are switched off in Settings. */
    data object Off : PosterChoices

    /** No TMDB key to ask with, or TMDB turned the key down. */
    data object NoKey : PosterChoices

    /** Offline, throttled or a TMDB outage: nothing was learned, so Try again may work later. */
    data object Unavailable : PosterChoices
}

/** Which titles the picker lists, and in which order. Pure, so the order is tested. */
object PosterChoiceRules {

    /** About ten: enough to find the right one, few enough to read at a glance on a remote. */
    const val LIMIT = 10

    /** The longest overview line kept per title. */
    const val OVERVIEW_MAX = 140

    /**
     * [lists] (each the answer of one search, films or shows, with the year or without it) merged
     * into one list: a title once however many searches found it, the closest to [query] first
     * ([MetaPick.score]: the name, then the year, then a language the file's name lists), and a
     * tie broken by the kind the file looks like, then by TMDB's own order. At most [LIMIT].
     */
    fun merge(query: MetaQuery, lists: List<Pair<Boolean, List<MetaCandidate>>>): List<PosterChoice> {
        data class Entry(val show: Boolean, val candidate: MetaCandidate, val order: Int)
        val seen = HashSet<String>()
        val entries = ArrayList<Entry>()
        var order = 0
        for ((film, candidates) in lists) {
            for (candidate in candidates) {
                val id = candidate.id.toIntOrNull()?.takeIf { it > 0 } ?: continue
                if (candidate.names.none { it.isNotBlank() }) continue
                if (seen.add("${if (film) "f" else "s"}$id")) entries += Entry(!film, candidate, order++)
            }
        }
        val prefersShow = query.kind == MetaKind.Show
        return entries
            .sortedWith(
                compareByDescending<Entry> { MetaPick.score(query, it.candidate) }
                    .thenByDescending { it.show == prefersShow }
                    .thenBy { it.order },
            )
            .take(LIMIT)
            .map { entry ->
                val c = entry.candidate
                PosterChoice(
                    tmdbId = c.id.toInt(),
                    show = entry.show,
                    title = c.names.first { it.isNotBlank() },
                    year = c.year,
                    originalLanguage = c.originalLanguage.lowercase().take(3),
                    overview = c.overview.trim().replace(Regex("""\s+"""), " ").take(OVERVIEW_MAX),
                    posterUrl = c.posterUrl,
                )
            }
    }
}
