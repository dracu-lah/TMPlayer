package com.tmplayer.online

import com.tmplayer.json.JSONArray
import com.tmplayer.json.JSONObject
import com.tmplayer.platform.IOException
import com.tmplayer.platform.formEncode

/** A metadata provider's answer, sorted into what [OnlineMetadata] has to tell apart. */
sealed interface MetaReply<out T> {
    data class Ok<T>(val value: T) : MetaReply<T>

    /** 401 or 403: the key was refused. Only TMDB has a key to refuse. */
    data object Refused : MetaReply<Nothing>

    /** 429: wait [retryAfterMs] before asking this provider again. */
    data class Throttled(val retryAfterMs: Long) : MetaReply<Nothing>

    /** No connection, a server error, or an answer that could not be read. */
    data class Failed(val code: Int) : MetaReply<Nothing>
}

/** A TMDB show's seasons with their episode counts, and its episode groups (T11). */
/** A title TMDB found by another site's id: its TMDB id, and whether it is a film (else a show). */
data class TmdbFound(val id: String, val film: Boolean)

data class TmdbShowGuide(val seasons: List<MetaSeason>, val groups: List<TmdbEpisodeGroup>)

/**
 * One TMDB episode group: its id, its type (2 is "Absolute order"), how many episodes it holds, its
 * name ("Seasons", "Cours (Path)") and how many groups (seasons, cours, discs) it splits them into.
 * The name and the group count are empty in a guide kept before they were read.
 */
data class TmdbEpisodeGroup(val id: String, val type: Int, val episodeCount: Int, val name: String = "", val groupCount: Int = 0) {
    val absolute: Boolean get() = type == 2
}

/** A title's [MetaExtras] and, for a show, the episode groups TMDB lists for it. */
data class TmdbExtras(val extras: MetaExtras, val groups: List<TmdbEpisodeGroup>)

/** What AniList knows of one run's episodes: its count, the next to air, and the streaming list (T11). */
data class AniListRun(
    val episodes: Int?,
    val malId: String?,
    val next: MetaAiring?,
    val streaming: List<AnimeEpisodes.Piece>,
    val startDate: String?,
)

/** A title as a search lists it, before the closer look at the one that matched. */
data class MetaCandidate(
    val id: String,
    val names: List<String>,
    val year: Int?,
    val overview: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    /** AniList's format: a film is `MOVIE`, everything else is a show. Empty for the others. */
    val format: String = "",
    /** TMDB's `original_language`, ISO 639-1: the language the title was made in. */
    val originalLanguage: String = "",
)

private fun enc(text: String): String = formEncode(text)

/** The objects in [list], skipping anything that is not one. */
private fun objects(list: JSONArray?): List<JSONObject> =
    if (list == null) emptyList() else (0 until list.length()).mapNotNull { list.optJSONObject(it) }

private fun yearOf(date: String?): Int? = date?.take(4)?.toIntOrNull()?.takeIf { it > 1800 }

/** Reads [response] through [read], with the statuses every provider shares sorted first. */
private inline fun <T> sort(response: HttpResponse, defaultWaitMs: Long, read: (JSONObject) -> T): MetaReply<T> =
    when (response.code) {
        in 200..299 -> runCatching { MetaReply.Ok(read(JSONObject(response.text))) }.getOrElse { MetaReply.Failed(response.code) }
        401, 403 -> MetaReply.Refused
        429 -> MetaReply.Throttled(retryAfter(response) ?: defaultWaitMs)
        else -> MetaReply.Failed(response.code)
    }

/** Retry-After in seconds, or AniList's X-RateLimit-Reset as an epoch second. */
private fun retryAfter(response: HttpResponse): Long? {
    response.header("retry-after")?.trim()?.toLongOrNull()?.let { return (it * 1000).coerceIn(1_000, 600_000) }
    return null
}

/** Sends, and turns a failure to connect into [MetaReply.Failed] with code 0. */
private suspend fun <T> call(http: HttpTransport, request: HttpRequest, waitMs: Long, read: (JSONObject) -> T): MetaReply<T> {
    val response = try {
        http.send(request)
    } catch (e: IOException) {
        return MetaReply.Failed(0)
    }
    return sort(response, waitMs, read)
}

/**
 * TMDB's API, version 3: search a film or a show, then read it (and an episode) in the UI
 * language. https://developer.themoviedb.org/reference. The key travels as `api_key`; a v4 read
 * token (a long JWT) goes as a bearer header instead, so either kind works in Settings.
 */
class TmdbApi(
    private val http: HttpTransport,
    private val limiter: RateLimiter,
    private val userAgent: String,
) {
    suspend fun searchMovie(key: String, query: String, year: Int?, language: String): MetaReply<List<MetaCandidate>> =
        get(key, "search/movie", "query=${enc(query)}&include_adult=false&language=$language" + (year?.let { "&year=$it" } ?: "")) { json ->
            results(json) { movieCandidate(it) }
        }

    suspend fun searchTv(key: String, query: String, year: Int?, language: String): MetaReply<List<MetaCandidate>> =
        get(key, "search/tv", "query=${enc(query)}&include_adult=false&language=$language" + (year?.let { "&first_air_date_year=$it" } ?: "")) { json ->
            results(json) { tvCandidate(it) }
        }

    suspend fun movie(key: String, id: String, language: String): MetaReply<MetaCandidate> =
        get(key, "movie/$id", "language=$language") { movieCandidate(it) }

    suspend fun tv(key: String, id: String, language: String): MetaReply<MetaCandidate> =
        get(key, "tv/$id", "language=$language") { tvCandidate(it) }

    /**
     * The film or show an IMDb id (`tt0111161`) names, through TMDB's `find`: one call. Null when
     * TMDB knows no such id. A film wins where an id somehow names both.
     */
    suspend fun findByImdb(key: String, imdbId: String): MetaReply<TmdbFound?> =
        get(key, "find/$imdbId", "external_source=imdb_id") { json ->
            val film = objects(json.optJSONArray("movie_results")).firstNotNullOfOrNull { it.optInt("id").takeIf { id -> id > 0 } }
            val show = objects(json.optJSONArray("tv_results")).firstNotNullOfOrNull { it.optInt("id").takeIf { id -> id > 0 } }
            when {
                film != null -> TmdbFound(film.toString(), film = true)
                show != null -> TmdbFound(show.toString(), film = false)
                else -> null
            }
        }

    /**
     * A film or show in [language] with its posters in [imageLanguages] appended (ISO 639-1
     * codes, `null` for posters with no words), for [MetaRules.poster] to choose from.
     */
    suspend fun localized(key: String, film: Boolean, id: String, language: String, imageLanguages: List<String>): MetaReply<Pair<MetaCandidate, List<MetaPoster>>> =
        get(key, if (film) "movie/$id" else "tv/$id", "language=$language&append_to_response=images&include_image_language=${imageLanguages.joinToString(",")}") { json ->
            val candidate = if (film) movieCandidate(json) else tvCandidate(json)
            val posters = objects(json.optJSONObject("images")?.optJSONArray("posters")).mapNotNull { p ->
                val path = p.optString("file_path").takeIf { it.startsWith("/") } ?: return@mapNotNull null
                val width = p.optInt("width")
                val height = p.optInt("height")
                MetaPoster(
                    path = path,
                    language = if (p.isNull("iso_639_1")) null else p.optString("iso_639_1").ifBlank { null },
                    aspect = p.optDouble("aspect_ratio", 0.0).takeIf { it > 0 } ?: if (height > 0) width.toDouble() / height else 0.0,
                    width = width,
                    voteAverage = p.optDouble("vote_average", 0.0),
                    voteCount = p.optInt("vote_count"),
                )
            }
            candidate to posters
        }

    /** A film's or show's title logos in [imageLanguages] (ISO 639-1, `null` for none), for [MetaRules.logo]. */
    suspend fun logos(key: String, film: Boolean, id: String, imageLanguages: List<String>): MetaReply<List<MetaPoster>> =
        get(key, if (film) "movie/$id/images" else "tv/$id/images", "include_image_language=${imageLanguages.joinToString(",")}") { json ->
            objects(json.optJSONArray("logos")).mapNotNull { p ->
                val path = p.optString("file_path").takeIf { it.startsWith("/") } ?: return@mapNotNull null
                val width = p.optInt("width")
                val height = p.optInt("height")
                MetaPoster(
                    path = path,
                    language = if (p.isNull("iso_639_1")) null else p.optString("iso_639_1").ifBlank { null },
                    aspect = p.optDouble("aspect_ratio", 0.0).takeIf { it > 0 } ?: if (height > 0) width.toDouble() / height else 0.0,
                    width = width,
                    voteAverage = p.optDouble("vote_average", 0.0),
                    voteCount = p.optInt("vote_count"),
                )
            }
        }

    /** [logo]'s address at the size the carousel draws it. */
    fun logoUrl(logo: MetaPoster): String = "$IMAGES/$LOGO_SIZE${logo.path}"

    /** [poster]'s address at the size a tile draws. */
    fun posterUrl(poster: MetaPoster): String = "$IMAGES/$POSTER_SIZE${poster.path}"

    suspend fun episode(key: String, id: String, season: Int, number: Int, language: String): MetaReply<MetaEpisode> =
        get(key, "tv/$id/season/$season/episode/$number", "language=$language") { json ->
            MetaEpisode(
                season = season,
                number = number,
                name = json.optString("name"),
                overview = json.optString("overview"),
                stillUrl = image(json, "still_path", STILL_SIZE),
            )
        }

    /** Every episode of one season, in order, with its name, picture, air date and length. */
    suspend fun season(key: String, id: String, season: Int, language: String): MetaReply<List<MetaEpisode>> =
        get(key, "tv/$id/season/$season", "language=$language") { json -> seasonEpisodes(season, json) }

    private fun seasonEpisodes(season: Int, json: JSONObject): List<MetaEpisode> =
        objects(json.optJSONArray("episodes")).mapNotNull { e ->
            val number = e.optInt("episode_number").takeIf { it > 0 } ?: return@mapNotNull null
            MetaEpisode(
                season = season,
                number = number,
                name = e.optString("name"),
                overview = e.optString("overview"),
                stillUrl = image(e, "still_path", STILL_SIZE),
                airDate = e.optString("air_date").ifBlank { null },
                runtimeMin = e.optInt("runtime").takeIf { it > 0 },
            )
        }.sortedBy { it.number }

    /**
     * A show's seasons (each with its episode count) and the episode groups it has, in one call
     * (T11): the groups' list is appended to the show's own page. Specials are left in; the guide
     * counts only the numbered seasons.
     */
    suspend fun showGuide(key: String, id: String, language: String): MetaReply<TmdbShowGuide> =
        get(key, "tv/$id", "language=$language&append_to_response=episode_groups") { json ->
            val seasons = objects(json.optJSONArray("seasons")).mapNotNull { s ->
                MetaSeason(
                    number = s.optInt("season_number", -1).takeIf { it >= 0 } ?: return@mapNotNull null,
                    episodeCount = s.optInt("episode_count").coerceAtLeast(0),
                    airDate = s.optString("air_date").ifBlank { null },
                )
            }
            TmdbShowGuide(seasons, episodeGroups(json))
        }

    /** The `episode_groups` appended to a show's page: the groups with an id, the rest skipped. */
    private fun episodeGroups(json: JSONObject): List<TmdbEpisodeGroup> =
        objects(json.optJSONObject("episode_groups")?.optJSONArray("results")).asSequence().mapNotNull { g ->
            TmdbEpisodeGroup(
                id = g.optString("id").trim().takeIf { it.isNotBlank() && it.length <= MAX_GROUP_ID } ?: return@mapNotNull null,
                type = g.optInt("type"),
                episodeCount = g.optInt("episode_count"),
                name = g.optString("name").trim().take(MAX_GROUP_NAME),
                groupCount = g.optInt("group_count"),
            )
        }.take(MAX_GROUPS).toList()

    /**
     * One episode group (the "Absolute order" one): its groups in order, each with its episodes,
     * every episode with the season and episode number TMDB files it under.
     */
    suspend fun episodeGroup(key: String, groupId: String, language: String): MetaReply<List<AnimeEpisodes.GroupEpisode>> =
        get(key, "tv/episode_group/${enc(groupId)}", "language=$language") { json ->
            objects(json.optJSONArray("groups")).flatMapIndexed { gi, group ->
                val groupOrder = group.optInt("order", gi)
                objects(group.optJSONArray("episodes")).mapIndexedNotNull { ei, e ->
                    val season = e.optInt("season_number", -1).takeIf { it >= 0 } ?: return@mapIndexedNotNull null
                    val episode = e.optInt("episode_number").takeIf { it > 0 } ?: return@mapIndexedNotNull null
                    AnimeEpisodes.GroupEpisode(
                        groupOrder = groupOrder,
                        order = e.optInt("order", ei),
                        season = season,
                        episode = episode,
                        piece = AnimeEpisodes.Piece(
                            absolute = 0,
                            name = e.optString("name"),
                            overview = e.optString("overview"),
                            stillUrl = image(e, "still_path", STILL_SIZE),
                            airDate = e.optString("air_date").ifBlank { null },
                            runtimeMin = e.optInt("runtime").takeIf { it > 0 },
                        ),
                    )
                }
            }
        }

    /**
     * Several seasons' episodes in one call: their pages are appended to the show's own, up to
     * [APPEND_LIMIT] of them (TMDB's cap), so 21 seasons cost two calls and not 21.
     */
    suspend fun seasons(key: String, id: String, numbers: List<Int>, language: String): MetaReply<Map<Int, List<MetaEpisode>>> {
        val wanted = numbers.distinct().take(APPEND_LIMIT)
        return get(key, "tv/$id", "language=$language&append_to_response=" + wanted.joinToString(",") { "season/$it" }) { json ->
            wanted.associateWith { n -> json.optJSONObject("season/$n")?.let { seasonEpisodes(n, it) }.orEmpty() }
        }
    }

    /**
     * The detail page's extras in one call: [MetaExtras] from the title's own page with its credits,
     * videos, age ratings and recommendations appended (and similar titles, for when nobody has
     * recommended any). Videos in the UI language and English, and those with no language.
     */
    suspend fun extras(key: String, film: Boolean, id: String, language: String, region: String): MetaReply<MetaExtras> =
        when (val reply = extrasAndGroups(key, film, id, language, region)) {
            is MetaReply.Ok -> MetaReply.Ok(reply.value.extras)
            MetaReply.Refused -> MetaReply.Refused
            is MetaReply.Throttled -> reply
            is MetaReply.Failed -> reply
        }

    /**
     * [extras], and for a show its episode groups, appended to the same call: a show TMDB lists as
     * one season may split it in a group ([SeasonSplit]), and this says so without a second call.
     */
    suspend fun extrasAndGroups(key: String, film: Boolean, id: String, language: String, region: String): MetaReply<TmdbExtras> {
        val append = if (film) {
            "credits,videos,release_dates,recommendations,similar"
        } else {
            "aggregate_credits,videos,content_ratings,recommendations,similar,external_ids,episode_groups"
        }
        val ui = language.substringBefore('-').lowercase()
        val videoLanguages = listOf(ui, "en", "null").distinct().joinToString(",")
        return get(key, if (film) "movie/$id" else "tv/$id", "language=$language&include_video_language=$videoLanguages&append_to_response=$append") { json ->
            TmdbExtras(tmdbExtras(json, film, language, region), if (film) emptyList() else episodeGroups(json))
        }
    }

    private fun tmdbExtras(json: JSONObject, film: Boolean, language: String, region: String): MetaExtras {
        val genres = objects(json.optJSONArray("genres")).map { it.optString("name") }.filter { it.isNotBlank() }
        val runtime = if (film) {
            json.optInt("runtime").takeIf { it > 0 }
        } else {
            val usual = json.optJSONArray("episode_run_time")?.let { list -> (0 until list.length()).map { list.optInt(it) }.firstOrNull { it > 0 } }
            usual ?: json.optJSONObject("last_episode_to_air")?.optInt("runtime")?.takeIf { it > 0 }
        }
        val certification = if (film) {
            val byRegion = objects(json.optJSONObject("release_dates")?.optJSONArray("results")).associate { r ->
                r.optString("iso_3166_1") to objects(r.optJSONArray("release_dates")).map { it.optString("certification").trim() to it.optInt("type") }
            }
            MetaRules.certification(MetaRules.filmCertifications(byRegion), region)
        } else {
            val byRegion = objects(json.optJSONObject("content_ratings")?.optJSONArray("results")).associate { it.optString("iso_3166_1") to it.optString("rating").trim() }
            MetaRules.certification(byRegion, region)
        }
        val credits = json.optJSONObject(if (film) "credits" else "aggregate_credits")
        val cast = objects(credits?.optJSONArray("cast"))
            .sortedBy { if (film) it.optInt("order", Int.MAX_VALUE) else -it.optInt("total_episode_count") }
            .take(MetaRules.CAST_LIMIT)
            .map { person ->
                val role = if (film) {
                    person.optString("character")
                } else {
                    objects(person.optJSONArray("roles")).maxByOrNull { it.optInt("episode_count") }?.optString("character").orEmpty()
                }
                MetaPerson(person.optString("name"), role.trim(), image(person, "profile_path", PROFILE_SIZE))
            }
            .filter { it.name.isNotBlank() }
        val directors = if (film) {
            objects(credits?.optJSONArray("crew")).filter { it.optString("job") == "Director" }.map { it.optString("name") }.filter { it.isNotBlank() }.distinct().take(3)
        } else {
            emptyList()
        }
        val creators = if (film) emptyList() else objects(json.optJSONArray("created_by")).map { it.optString("name") }.filter { it.isNotBlank() }.take(3)
        val videos = objects(json.optJSONObject("videos")?.optJSONArray("results")).map {
            MetaVideo(
                site = it.optString("site"),
                key = it.optString("key"),
                type = it.optString("type"),
                official = it.optBoolean("official"),
                language = it.optString("iso_639_1"),
                published = it.optString("published_at"),
            )
        }
        fun refs(name: String) = objects(json.optJSONObject(name)?.optJSONArray("results")).mapNotNull { r ->
            val id = r.opt("id")?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MetaRef(MetaProvider.Tmdb, if (film) MetaKind.Film else MetaKind.Show, id, r.optString(if (film) "title" else "name"))
        }
        val similar = refs("recommendations").ifEmpty { refs("similar") }.take(MetaRules.SIMILAR_LIMIT)
        // Season 0 is TMDB's specials, which no release names as a season.
        val seasonList = if (film) {
            emptyList()
        } else {
            objects(json.optJSONArray("seasons")).map {
                MetaSeason(it.optInt("season_number"), it.optInt("episode_count"), it.optString("air_date").ifBlank { null })
            }.filter { it.number > 0 }.sortedBy { it.number }
        }
        val next = json.optJSONObject("next_episode_to_air")?.let {
            MetaAiring(it.optInt("season_number"), it.optInt("episode_number"), it.optString("air_date"), it.optString("name"))
        }?.takeIf { !film && it.airDate.isNotBlank() && it.season > 0 }
        val rating = MetaRules.rating(json.optDouble("vote_average", 0.0), json.optInt("vote_count"))
        return MetaExtras(
            genres = genres,
            runtimeMin = runtime,
            seasons = if (film) null else json.optInt("number_of_seasons").takeIf { it > 0 },
            rating = rating,
            scores = listOfNotNull(rating?.let { MetaScore(ScoreSource.Tmdb, it) }),
            certification = certification,
            directors = directors,
            creators = creators,
            cast = cast,
            trailer = MetaRules.trailer(videos, language),
            similar = similar,
            seasonList = seasonList,
            nextEpisode = next,
            ended = !film && json.optString("status") in setOf("Ended", "Canceled"),
            // A film carries its IMDb id itself; a show's comes in `external_ids`, with TheTVDB's.
            imdbId = (json.optJSONObject("external_ids")?.optString("imdb_id") ?: json.optString("imdb_id"))
                ?.takeIf { it.startsWith("tt") },
            tvdbId = json.optJSONObject("external_ids")?.optInt("tvdb_id")?.takeIf { it > 0 }?.toString(),
        )
    }

    /**
     * Where a film or show can be streamed, rented or bought in [region] (HX41): TMDB's
     * `watch/providers`, data from JustWatch, read by [WatchOfferRules]. One call, names only.
     */
    suspend fun watchProviders(key: String, film: Boolean, id: String, region: String): MetaReply<WatchOffers> =
        get(key, if (film) "movie/${enc(id)}/watch/providers" else "tv/${enc(id)}/watch/providers", "") { json ->
            WatchOfferRules.read(json, region)
        }

    private suspend fun <T> get(key: String, path: String, params: String, read: (JSONObject) -> T): MetaReply<T> {
        limiter.acquire()
        val bearer = key.length > 40
        val url = "$BASE/$path?$params" + if (bearer) "" else "&api_key=${enc(key)}"
        val headers = buildMap {
            put("Accept", "application/json")
            put("User-Agent", userAgent)
            if (bearer) put("Authorization", "Bearer $key")
        }
        return call(http, HttpRequest("GET", url, headers), DEFAULT_WAIT_MS, read)
    }

    private fun results(json: JSONObject, each: (JSONObject) -> MetaCandidate): List<MetaCandidate> {
        val list = json.optJSONArray("results") ?: JSONArray()
        return (0 until list.length()).mapNotNull { list.optJSONObject(it)?.let(each) }
    }

    private fun movieCandidate(it: JSONObject) = MetaCandidate(
        id = it.get("id").toString(),
        names = listOf(it.optString("title"), it.optString("original_title")),
        year = yearOf(it.optString("release_date")),
        overview = it.optString("overview"),
        posterUrl = image(it, "poster_path", POSTER_SIZE),
        backdropUrl = image(it, "backdrop_path", BACKDROP_SIZE),
        originalLanguage = it.optString("original_language"),
    )

    private fun tvCandidate(it: JSONObject) = MetaCandidate(
        id = it.get("id").toString(),
        names = listOf(it.optString("name"), it.optString("original_name")),
        year = yearOf(it.optString("first_air_date")),
        overview = it.optString("overview"),
        posterUrl = image(it, "poster_path", POSTER_SIZE),
        backdropUrl = image(it, "backdrop_path", BACKDROP_SIZE),
        originalLanguage = it.optString("original_language"),
    )

    private fun image(json: JSONObject, field: String, size: String): String? =
        json.optString(field).takeIf { it.startsWith("/") }?.let { "$IMAGES/$size$it" }

    companion object {
        const val BASE = "https://api.themoviedb.org/3"
        const val IMAGES = "https://image.tmdb.org/t/p"

        /**
         * About 40 requests a second per address is TMDB's ceiling. A screenful of titles is a
         * search and a details call apiece, so half the ceiling: a tenth of it made a Home row's
         * posters wait their turn for two seconds and more.
         */
        const val GAP_MS = 50L
        const val DEFAULT_WAIT_MS = 10_000L

        // Sizes that fit what draws them: a poster at most ~240 dp wide, a 16:9 tile ~400 px.
        const val POSTER_SIZE = "w342"
        const val BACKDROP_SIZE = "w780"
        const val STILL_SIZE = "w300"

        /** A title logo, set at most about 300 dp wide in the carousel. */
        const val LOGO_SIZE = "w500"

        /** A face in the cast row, drawn at most ~96 dp wide. */
        const val PROFILE_SIZE = "w185"

        /** How many `append_to_response` pieces TMDB takes in one call. */
        const val APPEND_LIMIT = 20

        /** More episode groups than any show has, and the longest id and name kept of one. */
        const val MAX_GROUPS = 100
        const val MAX_GROUP_ID = 64
        const val MAX_GROUP_NAME = 200
    }
}

/**
 * TVmaze: shows and episodes, no key, CC BY-SA. At least 20 calls in 10 seconds are allowed, so
 * one every half second stays inside it. https://www.tvmaze.com/api
 */
class TvMazeApi(
    private val http: HttpTransport,
    private val limiter: RateLimiter,
    private val userAgent: String,
) {
    /** The best match TVmaze has for [name], or Ok(null) when it has none (a 404). */
    suspend fun show(name: String): MetaReply<MetaCandidate?> {
        limiter.acquire()
        val request = HttpRequest("GET", "$BASE/singlesearch/shows?q=${enc(name)}", headers())
        val response = try {
            http.send(request)
        } catch (e: IOException) {
            return MetaReply.Failed(0)
        }
        if (response.code == 404) return MetaReply.Ok(null)
        return sort(response, DEFAULT_WAIT_MS) { json ->
            MetaCandidate(
                id = json.get("id").toString(),
                names = listOf(json.optString("name")),
                year = yearOf(json.optString("premiered")),
                overview = metaPlain(json.optString("summary")),
                posterUrl = json.optJSONObject("image")?.optString("medium")?.ifBlank { null },
                backdropUrl = null,
            )
        }
    }

    suspend fun episode(showId: String, season: Int, number: Int): MetaReply<MetaEpisode?> {
        limiter.acquire()
        val request = HttpRequest("GET", "$BASE/shows/$showId/episodebynumber?season=$season&number=$number", headers())
        val response = try {
            http.send(request)
        } catch (e: IOException) {
            return MetaReply.Failed(0)
        }
        if (response.code == 404) return MetaReply.Ok(null)
        return sort(response, DEFAULT_WAIT_MS) { json ->
            MetaEpisode(
                season = season,
                number = number,
                name = json.optString("name"),
                overview = metaPlain(json.optString("summary")),
                stillUrl = json.optJSONObject("image")?.optString("medium")?.ifBlank { null },
            )
        }
    }

    /** Every episode of the show, all seasons, in airing order. */
    suspend fun episodes(showId: String): MetaReply<List<MetaEpisode>> {
        limiter.acquire()
        val request = HttpRequest("GET", "$BASE/shows/$showId/episodes", headers())
        val response = try {
            http.send(request)
        } catch (e: IOException) {
            return MetaReply.Failed(0)
        }
        if (response.code == 404) return MetaReply.Ok(emptyList())
        if (response.code !in 200..299) return sort(response, DEFAULT_WAIT_MS) { emptyList() }
        return runCatching {
            val list = JSONArray(response.text)
            MetaReply.Ok(
                objects(list).mapNotNull { e ->
                    val season = e.optInt("season").takeIf { it > 0 } ?: return@mapNotNull null
                    val number = e.optInt("number").takeIf { it > 0 } ?: return@mapNotNull null
                    MetaEpisode(
                        season = season,
                        number = number,
                        name = e.optString("name"),
                        overview = metaPlain(e.optString("summary")),
                        stillUrl = e.optJSONObject("image")?.optString("medium")?.ifBlank { null },
                        airDate = e.optString("airdate").ifBlank { null },
                        runtimeMin = e.optInt("runtime").takeIf { it > 0 },
                    )
                },
            )
        }.getOrElse { MetaReply.Failed(response.code) }
    }

    /** The show's genres, episode length, cast, seasons and next episode, in one call. */
    suspend fun extras(showId: String): MetaReply<MetaExtras> {
        limiter.acquire()
        // `embed[]` written out, since a bracket is not allowed bare in a URL.
        val request = HttpRequest("GET", "$BASE/shows/$showId?embed%5B%5D=cast&embed%5B%5D=seasons&embed%5B%5D=nextepisode", headers())
        return call(http, request, DEFAULT_WAIT_MS) { json ->
            val genres = json.optJSONArray("genres")?.let { list -> (0 until list.length()).map { list.optString(it) } }.orEmpty().filter { it.isNotBlank() }
            val cast = objects(json.optJSONObject("_embedded")?.optJSONArray("cast")).take(MetaRules.CAST_LIMIT).mapNotNull { entry ->
                val person = entry.optJSONObject("person") ?: return@mapNotNull null
                MetaPerson(
                    name = person.optString("name"),
                    role = entry.optJSONObject("character")?.optString("name").orEmpty(),
                    photoUrl = person.optJSONObject("image")?.optString("medium")?.ifBlank { null },
                ).takeIf { it.name.isNotBlank() }
            }
            val embedded = json.optJSONObject("_embedded")
            val seasonList = objects(embedded?.optJSONArray("seasons")).map {
                MetaSeason(it.optInt("number"), it.optInt("episodeOrder"), it.optString("premiereDate").ifBlank { null })
            }.filter { it.number > 0 }.sortedBy { it.number }
            val next = embedded?.optJSONObject("nextepisode")?.let {
                MetaAiring(it.optInt("season"), it.optInt("number"), it.optString("airdate"), it.optString("name"))
            }?.takeIf { it.airDate.isNotBlank() && it.season > 0 }
            // TVmaze gives the average alone, no count of votes. [MetaExtras.rating] stays empty for
            // that (one vote of ten is not a 10.0), but the scores row names its source, so the
            // average is listed there as TVmaze's whenever it gives one.
            val average = json.optJSONObject("rating")?.optDouble("average", 0.0)?.takeIf { !it.isNaN() } ?: 0.0
            val tvmazeScore = if (average > 0.0) MetaRules.rating(average, MetaRules.MIN_VOTES) else null
            MetaExtras(
                genres = genres,
                runtimeMin = json.optInt("runtime").takeIf { it > 0 } ?: json.optInt("averageRuntime").takeIf { it > 0 },
                scores = listOfNotNull(tvmazeScore?.let { MetaScore(ScoreSource.TvMaze, it) }),
                seasons = seasonList.size.takeIf { it > 0 },
                cast = cast,
                seasonList = seasonList,
                nextEpisode = next,
                ended = json.optString("status") == "Ended",
                imdbId = json.optJSONObject("externals")?.optString("imdb")?.takeIf { it.startsWith("tt") },
                tvdbId = json.optJSONObject("externals")?.optInt("thetvdb")?.takeIf { it > 0 }?.toString(),
            )
        }
    }

    private fun headers() = mapOf("Accept" to "application/json", "User-Agent" to userAgent)

    companion object {
        const val BASE = "https://api.tvmaze.com"
        const val GAP_MS = 500L
        const val DEFAULT_WAIT_MS = 10_000L
    }
}

/**
 * OMDb, for the scores TMDB, TVmaze and AniList do not carry: IMDb's, Rotten Tomatoes' critics
 * and Metacritic's, asked by IMDb id. A free key allows 1,000 requests a day; its data is
 * CC BY-NC 4.0. https://www.omdbapi.com
 *
 * [MetaReply.Refused] means the key was refused (`"Error":"Invalid API key!"`); a spent day
 * (`"Error":"Request limit reached!"`, with a 401 or a 200) is [MetaReply.Throttled] with
 * [SPENT], which waits for the next UTC day; a title OMDb does not know is an empty list.
 */
class OmdbApi(
    private val http: HttpTransport,
    private val limiter: RateLimiter,
    private val userAgent: String,
) {
    suspend fun scores(key: String, imdbId: String): MetaReply<List<MetaScore>> {
        limiter.acquire()
        val url = "$BASE/?i=${enc(imdbId)}&apikey=${enc(key)}"
        val request = HttpRequest("GET", url, mapOf("Accept" to "application/json", "User-Agent" to userAgent))
        val response = try {
            http.send(request)
        } catch (e: IOException) {
            return MetaReply.Failed(0)
        }
        // OMDb answers a refused or spent key with a 401 and its reason in the body.
        val json = runCatching { JSONObject(response.text) }.getOrNull()
        if (json != null && (response.code in 200..299 || json.has("Error"))) return read(json)
        if (response.code !in 200..299) return sort(response, DEFAULT_WAIT_MS) { emptyList() }
        return MetaReply.Failed(response.code)
    }

    companion object {
        const val BASE = "https://www.omdbapi.com"
        /** OMDb publishes no rate, only the daily allowance; a small gap keeps a burst polite. */
        const val GAP_MS = 200L
        const val DEFAULT_WAIT_MS = 60_000L

        /** The wait in a [MetaReply.Throttled] that means the day's requests are spent: until the next UTC day. */
        const val SPENT = Long.MAX_VALUE

        /** The scores in one OMDb answer, in [ScoreSource] order. */
        fun read(json: JSONObject): MetaReply<List<MetaScore>> {
            if (json.optString("Response").equals("False", ignoreCase = true)) {
                val error = json.optString("Error").lowercase()
                return when {
                    "limit" in error -> MetaReply.Throttled(SPENT)
                    "api key" in error || "no api" in error -> MetaReply.Refused
                    else -> MetaReply.Ok(emptyList())
                }
            }
            val scores = ArrayList<MetaScore>()
            val votes = number(json.optString("imdbVotes"))?.toInt() ?: 0
            number(json.optString("imdbRating"))?.let { MetaRules.rating(it, votes) }
                ?.let { scores += MetaScore(ScoreSource.Imdb, it) }
            val ratings = objects(json.optJSONArray("Ratings"))
            fun rating(source: String) = ratings.firstOrNull { it.optString("Source") == source }?.optString("Value")
            rating("Rotten Tomatoes")?.substringBefore('%')?.let(::number)?.takeIf { it in 0.0..100.0 }
                ?.let { scores += MetaScore(ScoreSource.RottenTomatoes, it) }
            (rating("Metacritic")?.substringBefore('/') ?: json.optString("Metascore")).let(::number)
                ?.takeIf { it in 0.0..100.0 }
                ?.let { scores += MetaScore(ScoreSource.Metacritic, it) }
            return MetaReply.Ok(scores.filter { it.value > 0.0 }.sortedBy { it.source.ordinal })
        }

        /** "8.5", "1,234,567" or "92" as a number; "N/A", blank or anything else as null. */
        private fun number(text: String?): Double? =
            text?.trim()?.replace(",", "")?.toDoubleOrNull()?.takeIf { !it.isNaN() }
    }
}

/**
 * Wikidata, for Rotten Tomatoes' and Metacritic's scores without a key: the title is found by its
 * IMDb id (P345) on the Wikidata Query Service, and its review scores (P444) read where they were
 * given by Rotten Tomatoes (Q105584) or Metacritic (Q150248). Its data is CC0. Wikidata has no
 * IMDb rating. Wikimedia asks for a User-Agent that names the app and how to reach it; a browser
 * cannot set one, so it goes as Api-User-Agent too, which the service allows across origins.
 * https://www.wikidata.org/wiki/Wikidata:SPARQL_query_service
 */
class WikidataApi(
    private val http: HttpTransport,
    private val limiter: RateLimiter,
    private val userAgent: String,
) {
    suspend fun scores(imdbId: String): MetaReply<List<MetaScore>> {
        if (!IMDB_ID.matches(imdbId)) return MetaReply.Ok(emptyList())
        limiter.acquire()
        val url = "$BASE?format=json&query=${enc(query(imdbId))}"
        val headers = mapOf(
            "Accept" to "application/sparql-results+json",
            "User-Agent" to userAgent,
            "Api-User-Agent" to userAgent,
        )
        return call(http, HttpRequest("GET", url, headers), DEFAULT_WAIT_MS) { read(it) }
    }

    companion object {
        const val BASE = "https://query.wikidata.org/sparql"
        /** The service has no fixed rate for light use; one query a second stays well inside it. */
        const val GAP_MS = 1_000L
        const val DEFAULT_WAIT_MS = 60_000L

        const val ROTTEN_TOMATOES = "Q105584"
        const val METACRITIC = "Q150248"
        /** P459 "determination method": Rotten Tomatoes' Tomatometer, not its average rating or audience score. */
        const val TOMATOMETER = "Q108403393"
        /** P459 for Metacritic's critics' Metascore, not its users' score. */
        const val METASCORE = "Q106515043"

        private val IMDB_ID = Regex("tt\\d{5,10}")

        /** Every Rotten Tomatoes and Metacritic review score of the title with [imdbId], with its date, method and rank. */
        fun query(imdbId: String): String =
            "SELECT ?src ?score ?when ?method ?rank WHERE { ?item wdt:P345 \"$imdbId\" . " +
                "?item p:P444 ?st . ?st ps:P444 ?score ; pq:P447 ?src ; wikibase:rank ?rank . " +
                "OPTIONAL { ?st pq:P585 ?when } OPTIONAL { ?st pq:P459 ?method } " +
                "VALUES ?src { wd:$ROTTEN_TOMATOES wd:$METACRITIC } }"

        /**
         * The scores in one answer, in [ScoreSource] order: Rotten Tomatoes' Tomatometer (a
         * percentage) and Metacritic's Metascore (out of a hundred), the newest by point in time
         * (P585) where there are several, deprecated statements left out. A Rotten Tomatoes
         * average out of ten, or a users' score, is not the score the page means and is skipped.
         */
        fun read(json: JSONObject): List<MetaScore> {
            val bindings = objects(json.optJSONObject("results")?.optJSONArray("bindings"))
            class Found(val source: ScoreSource, val value: Double, val at: String, val preferred: Boolean)
            val found = bindings.mapNotNull { b ->
                fun field(name: String) = b.optJSONObject(name)?.optString("value").orEmpty()
                fun entity(name: String) = field(name).substringAfterLast('/')
                val rank = field("rank").substringAfterLast('#')
                if (rank == "DeprecatedRank") return@mapNotNull null
                val method = entity("method").ifBlank { null }
                val (value, scale) = value(field("score")) ?: return@mapNotNull null
                val percent = field("score").trim().endsWith("%")
                val source = when (entity("src")) {
                    ROTTEN_TOMATOES -> ScoreSource.RottenTomatoes.takeIf {
                        (method == TOMATOMETER || method == null) && scale == 100 && (percent || method == TOMATOMETER)
                    }
                    METACRITIC -> ScoreSource.Metacritic.takeIf { (method == METASCORE || method == null) && scale == 100 }
                    else -> null
                } ?: return@mapNotNull null
                if (value <= 0.0) return@mapNotNull null
                Found(source, value, field("when"), rank == "PreferredRank")
            }
            return found.groupBy { it.source }
                .mapNotNull { (_, list) -> list.sortedWith(compareByDescending<Found> { it.at }.thenByDescending { it.preferred }).firstOrNull() }
                .map { MetaScore(it.source, it.value) }
                .sortedBy { it.source.ordinal }
        }

        /**
         * A review score as Wikidata writes it, as the number and what it is out of: "92%" is 92
         * of 100, "78/100" is 78 of 100, "7.8/10" is 7.8 of 10. Anything else, or a number past
         * its scale, is null.
         */
        fun value(text: String): Pair<Double, Int>? {
            val t = text.trim().replace(" ", "")
            val (number, scale) = when {
                t.endsWith("%") -> t.dropLast(1) to 100
                "/" in t -> t.substringBefore('/') to (t.substringAfter('/').toDoubleOrNull()?.takeIf { it == 10.0 || it == 100.0 }?.toInt() ?: return null)
                else -> return null
            }
            val v = number.replace(',', '.').toDoubleOrNull()?.takeIf { !it.isNaN() && it >= 0.0 && it <= scale } ?: return null
            return v to scale
        }
    }
}

/**
 * AniList's GraphQL API for anime: no key, about 30 requests a minute while it is degraded (90
 * otherwise), so one every two seconds. https://docs.anilist.co
 */
class AniListApi(
    private val http: HttpTransport,
    private val limiter: RateLimiter,
    private val userAgent: String,
) {
    suspend fun search(name: String): MetaReply<List<MetaCandidate>> {
        limiter.acquire()
        val body = JSONObject()
            .put("query", QUERY)
            .put("variables", JSONObject().put("search", name))
            .toString()
        val headers = mapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "User-Agent" to userAgent,
        )
        return call(http, HttpRequest("POST", BASE, headers, body), DEFAULT_WAIT_MS) { json ->
            val list = json.optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media") ?: JSONArray()
            (0 until list.length()).mapNotNull { list.optJSONObject(it) }.map { media ->
                val title = media.optJSONObject("title") ?: JSONObject()
                val synonyms = media.optJSONArray("synonyms") ?: JSONArray()
                MetaCandidate(
                    id = media.get("id").toString(),
                    names = listOf(title.optString("english"), title.optString("romaji"), title.optString("native")) +
                        (0 until synonyms.length()).map { synonyms.optString(it) },
                    year = media.optInt("seasonYear").takeIf { it > 0 }
                        ?: media.optJSONObject("startDate")?.optInt("year")?.takeIf { it > 0 },
                    overview = metaPlain(media.optString("description")),
                    posterUrl = media.optJSONObject("coverImage")?.optString("large")?.ifBlank { null },
                    backdropUrl = media.optString("bannerImage").takeIf { it.startsWith("http") },
                    format = media.optString("format"),
                )
            }
        }
    }

    /** An anime's genres, score, episode length, characters, trailer and recommendations, by id. */
    suspend fun extras(id: String): MetaReply<MetaExtras> {
        limiter.acquire()
        val body = JSONObject()
            .put("query", EXTRAS_QUERY)
            .put("variables", JSONObject().put("id", id.toIntOrNull() ?: 0))
            .toString()
        val headers = mapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "User-Agent" to userAgent,
        )
        return call(http, HttpRequest("POST", BASE, headers, body), DEFAULT_WAIT_MS) { json ->
            val media = json.optJSONObject("data")?.optJSONObject("Media") ?: JSONObject()
            val genres = media.optJSONArray("genres")?.let { list -> (0 until list.length()).map { list.optString(it) } }.orEmpty().filter { it.isNotBlank() }
            val cast = objects(media.optJSONObject("characters")?.optJSONArray("edges")).take(MetaRules.CAST_LIMIT).mapNotNull { edge ->
                val node = edge.optJSONObject("node") ?: return@mapNotNull null
                MetaPerson(
                    name = node.optJSONObject("name")?.optString("full").orEmpty(),
                    role = objects(edge.optJSONArray("voiceActors")).firstOrNull()?.optJSONObject("name")?.optString("full").orEmpty(),
                    photoUrl = node.optJSONObject("image")?.optString("medium")?.takeIf { it.startsWith("https://") },
                ).takeIf { it.name.isNotBlank() }
            }
            val trailer = media.optJSONObject("trailer")?.let { t ->
                MetaRules.trailer(listOf(MetaVideo(site = t.optString("site"), key = t.optString("id"), type = "Trailer")), "en")
            }
            val similar = objects(media.optJSONObject("recommendations")?.optJSONArray("nodes")).mapNotNull { node ->
                val rec = node.optJSONObject("mediaRecommendation") ?: return@mapNotNull null
                if (rec.optBoolean("isAdult")) return@mapNotNull null
                val title = rec.optJSONObject("title")
                MetaRef(
                    provider = MetaProvider.AniList,
                    kind = if (rec.optString("format") == "MOVIE") MetaKind.Film else MetaKind.Show,
                    id = rec.opt("id")?.toString() ?: return@mapNotNull null,
                    title = title?.optString("english")?.takeIf { it.isNotBlank() && it != "null" } ?: title?.optString("romaji").orEmpty(),
                )
            }.take(MetaRules.SIMILAR_LIMIT)
            // AniList leaves the average out until enough people have scored a title.
            val score = media.optInt("averageScore")
            MetaExtras(
                genres = genres,
                runtimeMin = media.optInt("duration").takeIf { it > 0 },
                rating = if (score > 0) MetaRules.rating(score / 10.0, MetaRules.MIN_VOTES) else null,
                scores = if (score > 0) listOf(MetaScore(ScoreSource.AniList, score.coerceAtMost(100).toDouble())) else emptyList(),
                cast = cast,
                trailer = trailer,
                similar = similar,
                malId = media.optInt("idMal").takeIf { it > 0 }?.toString(),
            )
        }
    }

    /**
     * One run's episode facts, by id (T11): how many AniList counts, the next to air, the
     * MyAnimeList id the other lists are found by, and the streaming episodes (a title and a picture each).
     */
    suspend fun episodes(id: String): MetaReply<AniListRun> {
        limiter.acquire()
        val body = JSONObject()
            .put("query", EPISODES_QUERY)
            .put("variables", JSONObject().put("id", id.toIntOrNull() ?: 0))
            .toString()
        val headers = mapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "User-Agent" to userAgent,
        )
        return call(http, HttpRequest("POST", BASE, headers, body), DEFAULT_WAIT_MS) { json ->
            val media = json.optJSONObject("data")?.optJSONObject("Media") ?: JSONObject()
            val next = media.optJSONObject("nextAiringEpisode")?.let { n ->
                val episode = n.optInt("episode").takeIf { it > 0 } ?: return@let null
                val date = AnimeEpisodes.dateOf(n.optLong("airingAt")) ?: return@let null
                MetaAiring(season = 1, episode = episode, airDate = date)
            }
            val start = media.optJSONObject("startDate")?.let { d ->
                val y = d.optInt("year").takeIf { it > 1900 } ?: return@let null
                val m = d.optInt("month").takeIf { it in 1..12 } ?: 1
                val day = d.optInt("day").takeIf { it in 1..31 } ?: 1
                "$y-${m.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
            }
            AniListRun(
                episodes = media.optInt("episodes").takeIf { it > 0 },
                malId = media.optInt("idMal").takeIf { it > 0 }?.toString(),
                next = next,
                streaming = objects(media.optJSONArray("streamingEpisodes")).take(AnimeEpisodes.MAX_EPISODES).mapNotNull { e ->
                    AnimeEpisodes.streamingPiece(e.optString("title"), e.optString("thumbnail").takeIf { it.startsWith("https://") })
                },
                startDate = start,
            )
        }
    }

    companion object {
        const val BASE = "https://graphql.anilist.co"
        const val GAP_MS = 2_100L
        const val DEFAULT_WAIT_MS = 60_000L

        private const val QUERY =
            "query (\$search: String) { Page(perPage: 5) { media(search: \$search, type: ANIME, isAdult: false) { " +
                "id format seasonYear startDate { year } title { romaji english native } synonyms " +
                "description(asHtml: false) coverImage { large } bannerImage } } }"

        private const val EPISODES_QUERY =
            "query (\$id: Int) { Media(id: \$id, type: ANIME) { idMal episodes nextAiringEpisode { episode airingAt } " +
                "startDate { year month day } streamingEpisodes { title thumbnail } } }"

        private const val EXTRAS_QUERY =
            "query (\$id: Int) { Media(id: \$id, type: ANIME) { idMal genres averageScore duration trailer { id site } " +
                "characters(sort: [ROLE, RELEVANCE], perPage: 10) { edges { node { name { full } image { medium } } " +
                "voiceActors(language: JAPANESE) { name { full } } } } " +
                "recommendations(perPage: 20, sort: RATING_DESC) { nodes { mediaRecommendation { id format isAdult title { romaji english } } } } } }"
    }
}

/**
 * Kitsu's JSON API for an anime's episodes (T11): a title, a synopsis, a picture and a date for
 * each, found by the MyAnimeList id through Kitsu's mappings. No key. Twenty episodes a page is its
 * ceiling, so a long show is several calls (made once and kept), spaced out. https://kitsu.docs.apiary.io
 */
class KitsuApi(
    private val http: HttpTransport,
    private val limiter: RateLimiter,
    private val userAgent: String,
) {
    /** Kitsu's own id for the anime with MyAnimeList id [malId], or null where Kitsu has none. */
    suspend fun idFor(malId: String): MetaReply<String?> =
        get("mappings?filter%5BexternalSite%5D=myanimelist%2Fanime&filter%5BexternalId%5D=${enc(malId)}&include=item") { json ->
            objects(json.optJSONArray("data")).firstNotNullOfOrNull { mapping ->
                val item = mapping.optJSONObject("relationships")?.optJSONObject("item")?.optJSONObject("data")
                item?.takeIf { it.optString("type") == "anime" }?.opt("id")?.toString()?.takeIf { id -> id.isNotBlank() && id.all(Char::isDigit) }
            }
        }

    /** One page of [kitsuId]'s episodes from [offset], and whether another follows. */
    suspend fun episodes(kitsuId: String, offset: Int): MetaReply<Pair<List<MetaEpisode>, Boolean>> =
        get("anime/${enc(kitsuId)}/episodes?page%5Blimit%5D=$PAGE&page%5Boffset%5D=$offset&sort=number") { json ->
            val list = objects(json.optJSONArray("data")).mapNotNull { e ->
                val a = e.optJSONObject("attributes") ?: return@mapNotNull null
                val number = a.optInt("number").takeIf { it in 1..AnimeEpisodes.MAX_NUMBER } ?: return@mapNotNull null
                val titles = a.optJSONObject("titles")
                MetaEpisode(
                    season = 1,
                    number = number,
                    name = titles?.optString("en_us")?.takeIf { it.isNotBlank() && it != "null" }
                        ?: titles?.optString("en")?.takeIf { it.isNotBlank() && it != "null" }
                        ?: a.optString("canonicalTitle").takeIf { it != "null" }.orEmpty(),
                    overview = a.optString("synopsis").takeIf { it != "null" }.orEmpty(),
                    stillUrl = a.optJSONObject("thumbnail")?.optString("original")?.takeIf { it.startsWith("https://") },
                    airDate = a.optString("airdate").take(10).takeIf { it.length == 10 && it[4] == '-' },
                    runtimeMin = a.optInt("length").takeIf { it > 0 },
                )
            }
            list to (json.optJSONObject("links")?.optString("next")?.isNotBlank() == true && list.isNotEmpty())
        }

    private suspend fun <T> get(path: String, read: (JSONObject) -> T): MetaReply<T> {
        limiter.acquire()
        val headers = mapOf("Accept" to "application/vnd.api+json", "User-Agent" to userAgent)
        return call(http, HttpRequest("GET", "$BASE/$path", headers), DEFAULT_WAIT_MS, read)
    }

    companion object {
        const val BASE = "https://kitsu.io/api/edge"
        const val PAGE = 20
        const val GAP_MS = 400L
        const val DEFAULT_WAIT_MS = 30_000L

        /** The most pages one show costs: 3,000 episodes. */
        const val MAX_PAGES = AnimeEpisodes.MAX_EPISODES / PAGE
    }
}

/**
 * Jikan, the open MyAnimeList API, for an anime's episode titles and air dates (T11): a hundred a
 * page, 3 calls a second and 60 a minute at most. No key. https://docs.api.jikan.moe
 */
class JikanApi(
    private val http: HttpTransport,
    private val limiter: RateLimiter,
    private val userAgent: String,
) {
    /** Page [page] of the episodes of MyAnimeList's [malId], and whether another follows. */
    suspend fun episodes(malId: String, page: Int): MetaReply<Pair<List<MetaEpisode>, Boolean>> {
        limiter.acquire()
        val headers = mapOf("Accept" to "application/json", "User-Agent" to userAgent)
        return call(http, HttpRequest("GET", "$BASE/anime/${enc(malId)}/episodes?page=$page", headers), DEFAULT_WAIT_MS) { json ->
            val list = objects(json.optJSONArray("data")).mapNotNull { e ->
                val number = e.optInt("mal_id").takeIf { it in 1..AnimeEpisodes.MAX_NUMBER } ?: return@mapNotNull null
                MetaEpisode(
                    season = 1,
                    number = number,
                    name = e.optString("title").takeIf { it != "null" }.orEmpty(),
                    airDate = e.optString("aired").take(10).takeIf { it.length == 10 && it[4] == '-' },
                )
            }
            list to (json.optJSONObject("pagination")?.optBoolean("has_next_page") == true && list.isNotEmpty())
        }
    }

    companion object {
        const val BASE = "https://api.jikan.moe/v4"
        const val GAP_MS = 1_100L
        const val DEFAULT_WAIT_MS = 30_000L

        /** The most pages one show costs: 3,000 episodes. */
        const val MAX_PAGES = AnimeEpisodes.MAX_EPISODES / 100
    }
}

private val TAGS = Regex("<[^>]+>")
private val SPACES = Regex("""[ \t]+""")

/** TVmaze's summaries are HTML and AniList's carry `<br>`; both read as plain text here. */
internal fun metaPlain(html: String): String = html
    .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
    .replace(TAGS, "")
    .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
    .replace("&nbsp;", " ")
    .replace(SPACES, " ")
    .lines().joinToString("\n") { it.trim() }
    .replace(Regex("\n{3,}"), "\n\n")
    .trim()

/**
 * The two skip-time databases: TheIntroDB for shows and films (by TMDB, IMDb or TheTVDB id), and
 * AniSkip for anime (by MyAnimeList id). Neither wants a key for reading. A 404 is their way of
 * saying nobody has timed this one yet, which is an empty answer rather than a failure.
 */
class SkipTimesApi(
    private val http: HttpTransport,
    private val introLimiter: RateLimiter,
    private val aniLimiter: RateLimiter,
    private val userAgent: String,
) {
    /**
     * TheIntroDB's segments for a film ([season] and [episode] null) or one episode. Times there
     * are in milliseconds, a null start meaning the very start and a null end the very end.
     * https://github.com/TheIntroDB/theintrodb-npm
     */
    suspend fun theIntroDb(
        tmdbId: String?,
        imdbId: String?,
        tvdbId: String?,
        season: Int?,
        episode: Int?,
    ): MetaReply<List<SkipSegment>> {
        val id = when {
            tmdbId != null -> "tmdb_id=${enc(tmdbId)}"
            imdbId != null -> "imdb_id=${enc(imdbId)}"
            tvdbId != null -> "tvdb_id=${enc(tvdbId)}"
            else -> return MetaReply.Ok(emptyList())
        }
        val numbers = if (season != null && episode != null) "&season=$season&episode=$episode" else ""
        introLimiter.acquire()
        val request = HttpRequest("GET", "$INTRO_BASE/media?$id$numbers", headers())
        val response = try {
            http.send(request)
        } catch (e: IOException) {
            return MetaReply.Failed(0)
        }
        if (response.code == 404) return MetaReply.Ok(emptyList())
        return sort(response, DEFAULT_WAIT_MS) { json ->
            fun read(name: String, kind: SkipKind) = objects(json.optJSONArray(name)).mapNotNull { seg ->
                val start = if (seg.isNull("start_ms")) 0L else seg.optLong("start_ms", -1)
                val end = if (seg.isNull("end_ms")) null else seg.optLong("end_ms", -1)
                if (start < 0 || (end != null && end <= start)) null else SkipSegment(kind, start, end)
            }
            read("recap", SkipKind.Recap) + read("intro", SkipKind.Intro) +
                read("credits", SkipKind.Credits) + read("preview", SkipKind.Preview)
        }
    }

    /**
     * AniSkip's opening, ending and recap for one episode, in seconds. [lengthSec] is the file's
     * running time, which AniSkip matches against the length the times were taken on, so a cut
     * with a different length does not get times that land in the wrong place; 0 takes any.
     * https://github.com/aniskip/aniskip-api
     */
    suspend fun aniSkip(malId: String, episode: Int, lengthSec: Int): MetaReply<List<SkipSegment>> {
        aniLimiter.acquire()
        val types = listOf("op", "ed", "mixed-op", "mixed-ed", "recap").joinToString("") { "&types=$it" }
        val request = HttpRequest("GET", "$ANISKIP_BASE/skip-times/${enc(malId)}/$episode?episodeLength=$lengthSec$types", headers())
        val response = try {
            http.send(request)
        } catch (e: IOException) {
            return MetaReply.Failed(0)
        }
        if (response.code == 404) return MetaReply.Ok(emptyList())
        return sort(response, DEFAULT_WAIT_MS) { json ->
            objects(json.optJSONArray("results")).mapNotNull { result ->
                val interval = result.optJSONObject("interval") ?: return@mapNotNull null
                val kind = when (result.optString("skipType")) {
                    "op", "mixed-op" -> SkipKind.Opening
                    "ed", "mixed-ed" -> SkipKind.Ending
                    "recap" -> SkipKind.Recap
                    else -> return@mapNotNull null
                }
                val start = (interval.optDouble("startTime", -1.0) * 1000).toLong()
                val end = (interval.optDouble("endTime", -1.0) * 1000).toLong()
                if (start < 0 || end <= start) null else SkipSegment(kind, start, end)
            }
        }
    }

    /**
     * IntroDB.app's segments for one episode or film, by IMDb id: a second community database,
     * separate from TheIntroDB, asked when that one has nothing. Each kind is one object or null,
     * with times in milliseconds. https://api.introdb.app/openapi.json
     */
    suspend fun introDbApp(imdbId: String, season: Int?, episode: Int?): MetaReply<List<SkipSegment>> {
        val numbers = if (season != null && episode != null) "&season=$season&episode=$episode" else "&is_movie=true"
        introLimiter.acquire()
        val request = HttpRequest("GET", "$INTRODB_APP_BASE/segments?imdb_id=${enc(imdbId)}$numbers", headers())
        val response = try {
            http.send(request)
        } catch (e: IOException) {
            return MetaReply.Failed(0)
        }
        if (response.code == 404) return MetaReply.Ok(emptyList())
        return sort(response, DEFAULT_WAIT_MS) { json ->
            listOf("recap" to SkipKind.Recap, "intro" to SkipKind.Intro, "outro" to SkipKind.Credits).mapNotNull { (name, kind) ->
                val seg = json.optJSONObject(name) ?: return@mapNotNull null
                val start = seg.optLong("start_ms", -1)
                val end = seg.optLong("end_ms", -1)
                if (start < 0 || end <= start) null else SkipSegment(kind, start, end)
            }
        }
    }

    private fun headers() = mapOf("Accept" to "application/json", "User-Agent" to userAgent)

    companion object {
        const val INTRODB_APP_BASE = "https://api.introdb.app"
        const val INTRO_BASE = "https://api.theintrodb.org/v3"
        const val ANISKIP_BASE = "https://api.aniskip.com/v2"

        /** TheIntroDB allows 30 requests in ten seconds. */
        const val INTRO_GAP_MS = 400L

        /** AniSkip allows 120 a minute. */
        const val ANISKIP_GAP_MS = 500L
        const val DEFAULT_WAIT_MS = 10_000L
    }
}
