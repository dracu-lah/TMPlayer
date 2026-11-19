package com.tmplayer.online

import com.tmplayer.platform.LruMap
import com.tmplayer.platform.deviceLocaleTag
import com.tmplayer.platform.putIfMissing
import com.tmplayer.data.MediaItem
import com.tmplayer.json.JSONArray
import com.tmplayer.json.JSONObject

/** One name in the cast: the actor (or, for anime, the character) and who they play or voice. */
data class MetaPerson(
    val name: String,
    val role: String = "",
    val photoUrl: String? = null,
)

/**
 * A trailer on YouTube, the only site it is opened from. [key] is YouTube's video id, checked
 * with [TrailerEmbed.isValidKey] wherever one is read (TMDB, AniList, the cache).
 */
data class MetaTrailer(val key: String) {
    /** Opens in the YouTube app where one is installed. */
    val appUri: String get() = "vnd.youtube:$key"

    /** The same video in a browser, and in the QR code a television shows when nothing can open it. */
    val watchUrl: String get() = "https://www.youtube.com/watch?v=$key"
}

/** One season of a show as the provider lists it: its number, how many episodes, when it began. */
data class MetaSeason(val number: Int, val episodeCount: Int, val airDate: String? = null)

/** The next episode of a show still running, and the day it airs (`2026-10-14`). */
data class MetaAiring(val season: Int, val episode: Int, val airDate: String, val name: String = "")

/** A title the provider recommends, by the provider's own id. Only shown when the viewer has it. */
data class MetaRef(
    val provider: MetaProvider,
    val kind: MetaKind,
    val id: String,
    val title: String,
) {
    /** What it is matched on. AniList's ids are one series, so its kind is left out of the match. */
    val matchKey: String get() = matchKey(provider, kind, id)

    companion object {
        fun matchKey(provider: MetaProvider, kind: MetaKind, id: String): String =
            if (provider == MetaProvider.AniList) "AniList||$id" else "${provider.name}|${kind.name}|$id"
    }
}

/**
 * The detail page's second helping, asked for only once a detail page opens: the facts line
 * (genres, length, seasons, rating, age rating, director or creators), the cast, a trailer, and
 * what the provider recommends. Every field may be empty; the page shows what there is.
 */
data class MetaExtras(
    val genres: List<String> = emptyList(),
    /** A film's length, or a show's usual episode length, in minutes. */
    val runtimeMin: Int? = null,
    val seasons: Int? = null,
    /** Out of ten, and only once enough people have voted: see [MetaRules.rating]. */
    val rating: Double? = null,
    /** The age rating in the viewer's region, else the US one. */
    val certification: String? = null,
    val directors: List<String> = emptyList(),
    val creators: List<String> = emptyList(),
    val cast: List<MetaPerson> = emptyList(),
    val trailer: MetaTrailer? = null,
    val similar: List<MetaRef> = emptyList(),
    /** A show's seasons, specials left out, so the page can say which the chat lacks. */
    val seasonList: List<MetaSeason> = emptyList(),
    /** The next episode to air, where the provider knows one. */
    val nextEpisode: MetaAiring? = null,
    /** The show has ended or been cancelled: no new season to wait for. */
    val ended: Boolean = false,
    /**
     * The title's ids elsewhere, for asking the skip-time databases about it: IMDb's `tt0903747`
     * and TheTVDB's number from TVmaze, MyAnimeList's from AniList. Null where the provider gave none.
     */
    val imdbId: String? = null,
    val tvdbId: String? = null,
    val malId: String? = null,
    /**
     * Every site's score the page shows, in [ScoreSource] order: IMDb, Rotten Tomatoes and
     * Metacritic from OMDb by [imdbId] (Rotten Tomatoes and Metacritic from Wikidata where OMDb
     * could not be asked or did not have them), then the provider's own (TMDB, AniList or TVmaze).
     */
    val scores: List<MetaScore> = emptyList(),
    /**
     * OMDb or Wikidata answered for [scores] (whatever they said), so an answer kept from before
     * they were asked is asked once, and a revisit asks nobody until [scoresAt] is old.
     */
    val scoresAsked: Boolean = false,
    /** When [scores] were last asked for, in epoch seconds; zero for never. Asked again after 30 days. */
    val scoresAt: Long = 0,
    /** OMDb itself answered (not only Wikidata), so the page has IMDb's score if OMDb has one. */
    val scoresFromOmdb: Boolean = false,
    /**
     * The TMDB episode group whose split [seasonList] and [nextEpisode] follow ([SeasonSplit]): a
     * show TMDB lists as one season that the group splits into several. Null for every other title.
     */
    val seasonGroup: String? = null,
    /** TMDB's own seasons and next episode, kept beside a [seasonGroup] split for a chat numbered TMDB's way. */
    val listedSeasons: List<MetaSeason> = emptyList(),
    val listedNext: MetaAiring? = null,
    /** The answer came with the show's episode groups, so a split was looked for; false in one kept from before. */
    val groupsRead: Boolean = false,
) {
    val isEmpty: Boolean
        get() = genres.isEmpty() && runtimeMin == null && seasons == null && rating == null && certification == null &&
            directors.isEmpty() && creators.isEmpty() && cast.isEmpty() && trailer == null && similar.isEmpty() &&
            seasonList.isEmpty() && nextEpisode == null && scores.isEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("genres", JSONArray(genres))
        .put("runtime", runtimeMin ?: 0)
        .put("seasons", seasons ?: 0)
        .put("rating", rating ?: 0.0)
        .put("certification", certification.orEmpty())
        .put("directors", JSONArray(directors))
        .put("creators", JSONArray(creators))
        .put("cast", JSONArray(cast.map { JSONObject().put("name", it.name).put("role", it.role).put("photo", it.photoUrl.orEmpty()) }))
        .put("trailer", trailer?.key.orEmpty())
        .put(
            "similar",
            JSONArray(similar.map { JSONObject().put("provider", it.provider.name).put("kind", it.kind.name).put("id", it.id).put("title", it.title) }),
        )
        .put("seasonList", seasonsJson(seasonList))
        .apply {
            nextEpisode?.let { put("next", airingJson(it)) }
            listedNext?.let { put("listedNext", airingJson(it)) }
            if (listedSeasons.isNotEmpty()) put("listedSeasons", seasonsJson(listedSeasons))
        }
        .put("seasonGroup", seasonGroup.orEmpty())
        .put("groupsRead", groupsRead)
        .put("ended", ended)
        .put("imdb", imdbId.orEmpty())
        .put("tvdb", tvdbId.orEmpty())
        .put("mal", malId.orEmpty())
        .put("scores", MetaScore.listToJson(scores))
        .put("scoresAsked", scoresAsked)
        .put("scoresAt", scoresAt)
        .put("scoresFromOmdb", scoresFromOmdb)

    companion object {
        private fun seasonsJson(list: List<MetaSeason>) =
            JSONArray(list.map { JSONObject().put("number", it.number).put("episodes", it.episodeCount).put("airDate", it.airDate.orEmpty()) })

        private fun airingJson(it: MetaAiring) =
            JSONObject().put("season", it.season).put("episode", it.episode).put("airDate", it.airDate).put("name", it.name)

        fun fromJson(json: JSONObject): MetaExtras? = runCatching {
            fun strings(name: String): List<String> {
                val list = json.optJSONArray(name) ?: return emptyList()
                return (0 until list.length()).map { list.optString(it) }.filter { it.isNotBlank() }
            }
            fun objects(name: String): List<JSONObject> {
                val list = json.optJSONArray(name) ?: return emptyList()
                return (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            }
            fun seasons(name: String) =
                objects(name).map { MetaSeason(it.optInt("number"), it.optInt("episodes"), it.optString("airDate").ifBlank { null }) }.filter { it.number > 0 }
            fun airing(name: String) = json.optJSONObject(name)?.let {
                MetaAiring(it.optInt("season"), it.optInt("episode"), it.optString("airDate"), it.optString("name"))
            }?.takeIf { it.airDate.isNotBlank() && it.season > 0 }
            MetaExtras(
                genres = strings("genres"),
                runtimeMin = json.optInt("runtime").takeIf { it > 0 },
                seasons = json.optInt("seasons").takeIf { it > 0 },
                rating = json.optDouble("rating", 0.0).takeIf { it > 0.0 },
                certification = json.optString("certification").ifBlank { null },
                directors = strings("directors"),
                creators = strings("creators"),
                cast = objects("cast").map { MetaPerson(it.optString("name"), it.optString("role"), it.optString("photo").ifBlank { null }) },
                trailer = json.optString("trailer").takeIf(TrailerEmbed::isValidKey)?.let { MetaTrailer(it) },
                similar = objects("similar").mapNotNull {
                    runCatching {
                        MetaRef(MetaProvider.valueOf(it.getString("provider")), MetaKind.valueOf(it.getString("kind")), it.getString("id"), it.optString("title"))
                    }.getOrNull()
                },
                seasonList = seasons("seasonList"),
                nextEpisode = airing("next"),
                ended = json.optBoolean("ended"),
                imdbId = json.optString("imdb").ifBlank { null },
                tvdbId = json.optString("tvdb").ifBlank { null },
                malId = json.optString("mal").ifBlank { null },
                scores = MetaScore.listFromJson(json.optJSONArray("scores")),
                scoresAsked = json.optBoolean("scoresAsked"),
                scoresAt = json.optLong("scoresAt"),
                scoresFromOmdb = json.optBoolean("scoresFromOmdb"),
                seasonGroup = json.optString("seasonGroup").ifBlank { null },
                listedSeasons = seasons("listedSeasons"),
                listedNext = airing("listedNext"),
                groupsRead = json.optBoolean("groupsRead"),
            )
        }.getOrNull()
    }
}

/** A video the viewer has, and the provider's title it was matched to: one "More like this" tile. */
data class KnownTitle(val item: MediaItem, val info: MetaInfo)

/** One video in a provider's list of videos for a title, before [MetaRules.trailer] picks one. */
data class MetaVideo(
    val site: String,
    val key: String,
    val type: String,
    val official: Boolean = false,
    val language: String = "",
    val published: String = "",
)

/**
 * One poster in TMDB's list of a title's images, before [MetaRules.poster] picks one. [language]
 * is ISO 639-1, or null for a poster with no words on it.
 */
data class MetaPoster(
    val path: String,
    val language: String?,
    val aspect: Double,
    val width: Int,
    val voteAverage: Double = 0.0,
    val voteCount: Int = 0,
)

/** The choices behind [MetaExtras], kept apart from the parsing so each one is tested on its own. */
object MetaRules {

    /** Fewer votes than this and the average says nothing: one vote of 10 is not a 10.0 film. */
    const val MIN_VOTES = 25

    /** At most this many people in the cast row, and recommendations kept per title. */
    const val CAST_LIMIT = 10
    const val SIMILAR_LIMIT = 20

    /** The rating to show, rounded to one decimal, or null when too few voted or nobody did. */
    fun rating(average: Double, votes: Int): Double? {
        if (votes < MIN_VOTES || average <= 0.0 || average.isNaN()) return null
        return kotlin.math.round(average.coerceAtMost(10.0) * 10) / 10.0
    }

    /** The viewer's region: the device's country, or the US where it has none. */
    fun region(tag: String = deviceLocaleTag()): String = tag.replace('_', '-').split('-').drop(1).firstOrNull { it.length == 2 && it.all(Char::isLetter) }?.uppercase() ?: "US"

    /** The age rating for [region], else the US one, else none. Blank ratings are none. */
    fun certification(byRegion: Map<String, String>, region: String): String? {
        val clean = byRegion.filterValues { it.isNotBlank() }.mapKeys { it.key.uppercase() }
        return clean[region.uppercase()] ?: clean["US"]
    }

    /**
     * A film's age rating per region from TMDB's release dates: the theatrical release's where it
     * has one, then digital, physical, television, limited and premiere.
     */
    fun filmCertifications(byRegion: Map<String, List<Pair<String, Int>>>): Map<String, String> =
        byRegion.mapValues { (_, releases) ->
            releases.filter { it.first.isNotBlank() }
                .minByOrNull { RELEASE_ORDER.indexOf(it.second).let { at -> if (at < 0) RELEASE_ORDER.size else at } }
                ?.first.orEmpty()
        }

    private val RELEASE_ORDER = listOf(3, 4, 5, 6, 2, 1)

    /**
     * The trailer to offer: YouTube only; a trailer before a teaser; an official one first; then
     * one in the UI language, then English, then any; the newest of what is left. A key that is
     * not a YouTube id is outside data gone wrong and never offered.
     */
    fun trailer(videos: List<MetaVideo>, uiLanguage: String): MetaTrailer? {
        val ui = uiLanguage.substringBefore('-').lowercase()
        fun languageRank(v: MetaVideo): Int {
            val lang = v.language.lowercase()
            return when {
                lang.isNotEmpty() && lang == ui -> 0
                lang == "en" -> 1
                else -> 2
            }
        }
        return videos
            .filter { it.site.equals("YouTube", ignoreCase = true) && TrailerEmbed.isValidKey(it.key) }
            .filter { it.type.equals("Trailer", ignoreCase = true) || it.type.equals("Teaser", ignoreCase = true) }
            .sortedWith(
                compareBy<MetaVideo>({ if (it.type.equals("Trailer", ignoreCase = true)) 0 else 1 }, { if (it.official) 0 else 1 }, { languageRank(it) })
                    .thenByDescending { it.published },
            )
            .firstOrNull()
            ?.let { MetaTrailer(it.key) }
    }

    /**
     * The poster to show from a title's [posters]: one in the UI language first, then one in
     * English or with no words on it, then one in the title's [original] language; the best voted
     * of that tier, the widest on a tie. Never a picture that is not a poster: anything far from
     * the 2:3 shape (a banner, a square logo or a channel's badge uploaded in the wrong place) or
     * too small to fill a tile is passed over. Null when none is left, and the caller keeps what
     * the title's own record names.
     */
    /**
     * The title logo to draw for a film or show from TMDB's `logos`: one in the UI language, else
     * English, else one with no words, PNG only (an SVG would not decode), wide rather than tall
     * (a logo set in a carousel's corner is a line of type), then by votes and width. Null when
     * there is none worth drawing, and the carousel sets the name as text.
     */
    fun logo(logos: List<MetaPoster>, uiLanguage: String): MetaPoster? {
        val ui = uiLanguage.substringBefore('-').lowercase()
        fun tier(p: MetaPoster): Int? {
            val lang = p.language?.lowercase()?.takeIf { it.isNotBlank() && it != "xx" }
            return when {
                lang != null && lang == ui -> 0
                lang == "en" -> 1
                lang == null -> 2
                else -> null
            }
        }
        return logos
            .filter { it.path.startsWith("/") && it.path.endsWith(".png", ignoreCase = true) && it.aspect >= LOGO_MIN_ASPECT && it.width >= LOGO_MIN_WIDTH }
            .mapNotNull { p -> tier(p)?.let { it to p } }
            .sortedWith(
                compareBy<Pair<Int, MetaPoster>> { it.first }
                    .thenByDescending { it.second.voteAverage }
                    .thenByDescending { it.second.voteCount }
                    .thenByDescending { it.second.width },
            )
            .firstOrNull()
            ?.second
    }

    /** A logo narrower than this for its height is a stacked badge that would tower over the buttons. */
    const val LOGO_MIN_ASPECT = 1.2

    /** Smaller than this and a logo is soft at the carousel's size. */
    const val LOGO_MIN_WIDTH = 200

    fun poster(posters: List<MetaPoster>, uiLanguage: String, original: String?): MetaPoster? {
        val ui = uiLanguage.substringBefore('-').lowercase()
        val first = original?.substringBefore('-')?.lowercase()?.takeIf { it.isNotBlank() }
        fun tier(p: MetaPoster): Int? {
            val lang = p.language?.lowercase()?.takeIf { it.isNotBlank() && it != "xx" }
            return when {
                lang != null && lang == ui -> 0
                lang == null || lang == "en" -> 1
                lang == first -> 2
                else -> null
            }
        }
        return posters
            .filter { it.path.startsWith("/") && it.aspect in POSTER_ASPECT && it.width >= POSTER_MIN_WIDTH }
            .mapNotNull { p -> tier(p)?.let { it to p } }
            .sortedWith(
                compareBy<Pair<Int, MetaPoster>> { it.first }
                    .thenByDescending { it.second.voteAverage }
                    .thenByDescending { it.second.voteCount }
                    .thenByDescending { it.second.width },
            )
            .firstOrNull()
            ?.second
    }

    /** Width over height a poster may have: 2:3 is 0.667, and TMDB takes 0.6 to 0.75 or so. */
    private val POSTER_ASPECT = 0.6..0.76

    /** Narrower than this and a poster is a thumbnail, softer than the tile it fills. */
    private const val POSTER_MIN_WIDTH = 300

    /**
     * "More like this", narrowed to what the viewer can play: each of [similar] that one of
     * [known] was matched to, in the provider's order, once each, never [self], at most [limit].
     */
    fun moreLikeThis(similar: List<MetaRef>, self: MetaInfo, known: List<KnownTitle>, limit: Int = 10): List<KnownTitle> {
        if (similar.isEmpty() || known.isEmpty()) return emptyList()
        val selfKey = MetaRef.matchKey(self.provider, self.kind, self.id)
        val byKey = LinkedHashMap<String, KnownTitle>()
        for (k in known) byKey.putIfMissing(MetaRef.matchKey(k.info.provider, k.info.kind, k.info.id), k)
        return similar.asSequence()
            .map { it.matchKey }
            .filter { it != selfKey }
            .distinct()
            .mapNotNull { byKey[it] }
            .take(limit)
            .toList()
    }
}

/**
 * The videos this app has listed lately (Home's rows, a chat's grid, a search), so "More like
 * this" can offer only what the viewer has. Filled where the pages are loaded; bounded, oldest
 * first out, so a long session on a 1 GB television does not keep every video it ever showed.
 */
object KnownMedia {
    private const val LIMIT = 1_500

    private val items = LruMap<String, MediaItem>(LIMIT)

    private val changes = kotlinx.coroutines.flow.MutableStateFlow(0)

    /**
     * Goes up each time videos are noted, so a page built from [all] (the detail page's episode
     * list) redraws when a chat's listing is loaded or refreshed and a new episode turns up.
     */
    val version: kotlinx.coroutines.flow.StateFlow<Int> get() = changes

    fun note(list: Collection<MediaItem>) {
        if (list.isEmpty()) return
        items.locked { list.forEach { this[it.id] = it } }
        changes.value = changes.value + 1
    }

    /** Most recently listed last. */
    fun all(): List<MediaItem> = items.values.toList()

    fun clear() {
        items.clear()
        changes.value = changes.value + 1
    }
}
