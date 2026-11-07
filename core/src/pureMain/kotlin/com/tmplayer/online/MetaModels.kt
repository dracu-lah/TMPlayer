package com.tmplayer.online

import com.tmplayer.platform.stripCombiningMarks
import com.tmplayer.data.MediaName
import com.tmplayer.json.JSONObject

/** Where a poster and an overview came from. Each one is credited where its words appear. */
enum class MetaProvider(val label: String) {
    Tmdb("TMDB"),
    TvMaze("TVmaze"),
    AniList("AniList"),
}

/** A film or a show. Anime is either; [MetaQuery.anime] says which provider to ask first. */
enum class MetaKind { Film, Show }

/**
 * What is asked for one video, read off its file name and caption by [MediaName].
 *
 * [tmdbId] is the `{tmdb-123}` a file name can carry to force a match; [anime] is a guess from the
 * fansub shapes (a leading `[Group]`, an ` - 02` episode with no season) that sends a show to
 * AniList first.
 */
data class MetaQuery(
    val title: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val anime: Boolean = false,
    val tmdbId: Int? = null,
    val kind: MetaKind = if (season != null || episode != null) MetaKind.Show else MetaKind.Film,
    /**
     * The audio languages the name lists after its title, ISO 639-1 ("Tamil-Hindi" gives `ta`,
     * `hi`): a hint for which of two titles of the same name and year is meant, never a reason to
     * take one. Part of [cacheKey] when the list is not empty (a Tamil and a Hindi copy of the
     * same name can be two films, and an answer chosen without the hint must not stand for one
     * with it); a name that lists none keeps its old key, so answers already kept for it stand.
     */
    val languages: List<String> = emptyList(),
) {
    /** True when an episode's own name and words are wanted, not only the show's. */
    val wantsEpisode: Boolean get() = kind == MetaKind.Show && episode != null

    /** The same question for the show alone, which is all a tile needs. */
    fun showOnly(): MetaQuery = copy(season = null, episode = null)

    /**
     * What the cache files the answer under: the cleaned title, never the file's own name. In a
     * language other than English the answer has been asked in English too and its poster chosen
     * by [MetaRules.poster], which "+en" marks, so answers kept from before either (a title not
     * found in Arabic, a poster TMDB named) are asked again rather than kept for months. The
     * audio [languages] (sorted, joined with "-") come last and only when there are some.
     */
    fun cacheKey(language: String): String = listOfNotNull(
        kind.name,
        MetaMatch.normalise(title),
        year?.toString().orEmpty(),
        if (wantsEpisode) "${season ?: 1}x$episode" else "",
        if (anime) "anime" else "",
        tmdbId?.toString().orEmpty(),
        if (language.startsWith("en")) language else "$language+en",
        languages.takeIf { it.isNotEmpty() }?.sorted()?.joinToString("-"),
    ).joinToString("|")

    companion object {
        private val FORCED = Regex("""\{tmdb-(\d{1,9})\}""", RegexOption.IGNORE_CASE)
        private val FANSUB_GROUP = Regex("""^\s*\[[^\]]{2,40}\]""")
        private val DASH_EPISODE = Regex("""\s-\s\d{1,4}(?:v\d)?(?:\s|\(|\[|$)""")
        private val WORDY = Regex("""^\p{L}{2,}""")
        private val TAG_WORDS = setOf("hevc", "web", "webrip", "bluray", "brrip", "hdrip", "dvdrip", "hdr", "remux", "mkv", "mp4", "avi", "x264", "x265", "h264", "h265")

        /** A title with a word in it, or a number standing for a film with its year ("1917 (2019)"). */
        private fun meaningful(title: String, year: Int?): Boolean {
            val words = MetaMatch.normalise(title).split(' ').filter { it.isNotEmpty() }
            if (words.any { WORDY.containsMatchIn(it) && it !in TAG_WORDS }) return true
            return year != null && words.size == 1 && words[0].all { it.isDigit() }
        }

        private val SXXEXX = Regex("""\bS\d{1,2}\s?E\d{1,4}\b|\b\d{1,2}x\d{2,3}\b""", RegexOption.IGNORE_CASE)

        /**
         * The question for a video, or null when there is nothing worth asking: a name with no
         * title left once the release tags are gone, or one too short to match safely.
         */
        fun of(fileName: String, caption: String? = null): MetaQuery? {
            val forced = FORCED.find(fileName)?.groupValues?.get(1)?.toIntOrNull()
                ?: caption?.let { FORCED.find(it) }?.groupValues?.get(1)?.toIntOrNull()
            val name = fileName.replace(FORCED, " ")
            val parsed = MediaName.parse(name, caption?.replace(FORCED, " "))
            val title = parsed.title.trim()
            if (forced == null && !meaningful(title, parsed.year)) return null
            val anime = parsed.season == null && parsed.episode != null &&
                (FANSUB_GROUP.containsMatchIn(name) || DASH_EPISODE.containsMatchIn(name)) && !SXXEXX.containsMatchIn(name)
            return MetaQuery(
                title = title,
                year = parsed.year,
                season = parsed.season ?: if (parsed.episode != null) 1 else null,
                episode = parsed.episode,
                anime = anime,
                tmdbId = forced,
                // A daily show named by its air date is a show, though it names no episode number.
                kind = if (parsed.isSeries) MetaKind.Show else MetaKind.Film,
                languages = (MediaName.languages(name) + (caption?.let { MediaName.languages(it.replace(FORCED, " ")) } ?: emptyList())).distinct(),
            )
        }
    }
}

/** One episode's own name, words and picture, when the provider has them. */
data class MetaEpisode(
    val season: Int,
    val number: Int,
    val name: String = "",
    val overview: String = "",
    val stillUrl: String? = null,
    /** When it first aired (or will), as the provider writes it: `2026-10-14`. */
    val airDate: String? = null,
    val runtimeMin: Int? = null,
)

/**
 * What the detail panel, the series page and the tiles show for a video: the provider's title,
 * year and overview in the UI language where it has one (English otherwise), a poster (2:3), a
 * wide picture for 16:9 tiles, and the episode when the video is one.
 */
data class MetaInfo(
    val provider: MetaProvider,
    val id: String,
    val kind: MetaKind,
    val title: String,
    val year: Int? = null,
    val overview: String = "",
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    /** The language [overview] is in, as the provider was asked for it. */
    val language: String = "en",
    val episode: MetaEpisode? = null,
) {
    /** The best picture for a 16:9 tile: the episode's own still, the show's backdrop, its poster. */
    val wideUrl: String? get() = episode?.stillUrl ?: backdropUrl

    fun toJson(): JSONObject = JSONObject()
        .put("provider", provider.name)
        .put("id", id)
        .put("kind", kind.name)
        .put("title", title)
        .put("year", year ?: 0)
        .put("overview", overview)
        .put("poster", posterUrl.orEmpty())
        .put("backdrop", backdropUrl.orEmpty())
        .put("language", language)
        .apply {
            episode?.let {
                put(
                    "episode",
                    JSONObject().put("season", it.season).put("number", it.number).put("name", it.name)
                        .put("overview", it.overview).put("still", it.stillUrl.orEmpty()),
                )
            }
        }

    companion object {
        fun fromJson(json: JSONObject): MetaInfo? = runCatching {
            MetaInfo(
                provider = MetaProvider.valueOf(json.getString("provider")),
                id = json.getString("id"),
                kind = MetaKind.valueOf(json.getString("kind")),
                title = json.getString("title"),
                year = json.optInt("year").takeIf { it > 0 },
                overview = json.optString("overview"),
                posterUrl = json.optString("poster").ifBlank { null },
                backdropUrl = json.optString("backdrop").ifBlank { null },
                language = json.optString("language", "en"),
                episode = json.optJSONObject("episode")?.let {
                    MetaEpisode(
                        season = it.optInt("season"),
                        number = it.optInt("number"),
                        name = it.optString("name"),
                        overview = it.optString("overview"),
                        stillUrl = it.optString("still").ifBlank { null },
                    )
                },
            )
        }.getOrNull()
    }
}

/** How a lookup ended. Only [Found] and [NoMatch] are kept in the cache. */
sealed interface MetaResult {
    data class Found(val info: MetaInfo) : MetaResult

    /** Asked, and nobody had a title close enough to trust. The filename stays the title. */
    data object NoMatch : MetaResult

    /** Switched off in Settings. */
    data object Off : MetaResult

    /** Every provider that could answer was offline, refusing, or asking us to slow down. */
    data object Unavailable : MetaResult
}

/**
 * Whether a provider's title is the one asked for. Strict on purpose: a wrong poster is worse
 * than none, so a home video called "coast walk day 2" must not turn into somebody's film.
 */
object MetaMatch {

    private val MARKS = Regex("""\p{M}+""")
    private val NOT_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val ARTICLE = Regex("""^(the|a|an) """)

    /** Lower case, accents and punctuation gone, "&" as "and", a leading article dropped. */
    fun normalise(title: String): String {
        val plain = stripCombiningMarks(title.lowercase()).replace(MARKS, "")
        return plain.replace("&", " and ").replace(NOT_WORD, " ").trim().replace(ARTICLE, "")
    }

    /** Share of words the two titles have in common, 0 to 1. */
    fun overlap(a: String, b: String): Double {
        val x = normalise(a).split(' ').filter { it.isNotEmpty() }.toSet()
        val y = normalise(b).split(' ').filter { it.isNotEmpty() }.toSet()
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return (x intersect y).size.toDouble() / (x union y).size
    }

    /**
     * True when one of [names] is [wanted]: the same words, or most of them with the year agreeing
     * (within a year, since festival and release dates differ). With no year on either side only
     * the same words will do.
     *
     * For a [show], the year in an episode's file name is often its season's, not the premiere's
     * ("Loki 2023 S02E01" for a show from 2021), so a later year does not rule out the same words.
     * It still takes a year within one for most of the words to do.
     */
    fun accepts(wanted: String, wantedYear: Int?, names: List<String>, year: Int?, show: Boolean = false): Boolean {
        val target = normalise(wanted)
        if (target.isEmpty()) return false
        val yearAgrees = wantedYear != null && year != null && kotlin.math.abs(wantedYear - year) <= 1
        val laterSeason = show && wantedYear != null && year != null && wantedYear > year
        if (wantedYear != null && year != null && !yearAgrees && !laterSeason) return false
        return names.filter { it.isNotBlank() }.any { name ->
            normalise(name) == target || (yearAgrees && overlap(wanted, name) >= 0.6)
        }
    }
}

/**
 * Which of a search's results is the title asked for, once [MetaMatch] has said which may be:
 * the same words before most of them, the same year before a year one off, a title made in one
 * of the languages the file lists before one that is not, and otherwise the provider's own order
 * (its relevance and popularity). "Clap 2022 Tamil-Hindi" so takes the 2022 film called exactly
 * that over an older "Clap" or a "Clap Clap", and of two films of one name and year, the Tamil one.
 */
object MetaPick {

    fun best(query: MetaQuery, candidates: List<MetaCandidate>, show: Boolean): MetaCandidate? =
        candidates.withIndex()
            .filter { MetaMatch.accepts(query.title, query.year, it.value.names, it.value.year, show) }
            .maxWithOrNull(compareBy<IndexedValue<MetaCandidate>> { score(query, it.value) }.thenByDescending { it.index })
            ?.value

    /** Higher is closer. The weights keep the order above: words, then year, then language. */
    fun score(query: MetaQuery, candidate: MetaCandidate): Int {
        val target = MetaMatch.normalise(query.title)
        var score = 0
        if (candidate.names.any { it.isNotBlank() && MetaMatch.normalise(it) == target }) score += EXACT_WORDS
        val wanted = query.year
        val year = candidate.year
        if (wanted != null && year != null) {
            if (wanted == year) score += SAME_YEAR else if (kotlin.math.abs(wanted - year) <= 1) score += NEAR_YEAR
        }
        val original = candidate.originalLanguage.lowercase()
        if (original.isNotEmpty() && original in query.languages) score += LANGUAGE
        return score
    }

    private const val EXACT_WORDS = 8
    private const val SAME_YEAR = 4
    private const val NEAR_YEAR = 1
    private const val LANGUAGE = 2
}

/** What is known of a title's logo: its address, or null for a title that has none. */
data class LogoAnswer(val url: String?)
