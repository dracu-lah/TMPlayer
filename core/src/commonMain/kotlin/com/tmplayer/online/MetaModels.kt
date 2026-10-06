package com.tmplayer.online

import com.tmplayer.data.MediaName
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

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
) {
    /** True when an episode's own name and words are wanted, not only the show's. */
    val wantsEpisode: Boolean get() = kind == MetaKind.Show && episode != null

    /** The same question for the show alone, which is all a tile needs. */
    fun showOnly(): MetaQuery = copy(season = null, episode = null)

    /** What the cache files the answer under: the cleaned title, never the file's own name. */
    fun cacheKey(language: String): String = listOf(
        kind.name,
        MetaMatch.normalise(title),
        year?.toString().orEmpty(),
        if (wantsEpisode) "${season ?: 1}x$episode" else "",
        if (anime) "anime" else "",
        tmdbId?.toString().orEmpty(),
        language,
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

        private val SXXEXX = Regex("""(?i)\bS\d{1,2}\s?E\d{1,4}\b|\b\d{1,2}x\d{2,3}\b""")

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
        val plain = Normalizer.normalize(title.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(MARKS, "")
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
     */
    fun accepts(wanted: String, wantedYear: Int?, names: List<String>, year: Int?): Boolean {
        val target = normalise(wanted)
        if (target.isEmpty()) return false
        val yearAgrees = wantedYear != null && year != null && kotlin.math.abs(wantedYear - year) <= 1
        if (wantedYear != null && year != null && !yearAgrees) return false
        return names.filter { it.isNotBlank() }.any { name ->
            normalise(name) == target || (yearAgrees && overlap(wanted, name) >= 0.6)
        }
    }
}
