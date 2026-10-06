package com.tmplayer.online

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

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
)

private fun enc(text: String): String = URLEncoder.encode(text, "UTF-8")

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
        get(key, "tv/$id/season/$season", "language=$language") { json ->
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
        }

    /**
     * The detail page's extras in one call: [MetaExtras] from the title's own page with its credits,
     * videos, age ratings and recommendations appended (and similar titles, for when nobody has
     * recommended any). Videos in the UI language and English, and those with no language.
     */
    suspend fun extras(key: String, film: Boolean, id: String, language: String, region: String): MetaReply<MetaExtras> {
        val append = if (film) "credits,videos,release_dates,recommendations,similar" else "aggregate_credits,videos,content_ratings,recommendations,similar"
        val ui = language.substringBefore('-').lowercase()
        val videoLanguages = listOf(ui, "en", "null").distinct().joinToString(",")
        return get(key, if (film) "movie/$id" else "tv/$id", "language=$language&include_video_language=$videoLanguages&append_to_response=$append") { json ->
            tmdbExtras(json, film, language, region)
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
        return MetaExtras(
            genres = genres,
            runtimeMin = runtime,
            seasons = if (film) null else json.optInt("number_of_seasons").takeIf { it > 0 },
            rating = MetaRules.rating(json.optDouble("vote_average", 0.0), json.optInt("vote_count")),
            certification = certification,
            directors = directors,
            creators = creators,
            cast = cast,
            trailer = MetaRules.trailer(videos, language),
            similar = similar,
            seasonList = seasonList,
            nextEpisode = next,
            ended = !film && json.optString("status") in setOf("Ended", "Canceled"),
        )
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
    )

    private fun tvCandidate(it: JSONObject) = MetaCandidate(
        id = it.get("id").toString(),
        names = listOf(it.optString("name"), it.optString("original_name")),
        year = yearOf(it.optString("first_air_date")),
        overview = it.optString("overview"),
        posterUrl = image(it, "poster_path", POSTER_SIZE),
        backdropUrl = image(it, "backdrop_path", BACKDROP_SIZE),
    )

    private fun image(json: JSONObject, field: String, size: String): String? =
        json.optString(field).takeIf { it.startsWith("/") }?.let { "$IMAGES/$size$it" }

    companion object {
        const val BASE = "https://api.themoviedb.org/3"
        const val IMAGES = "https://image.tmdb.org/t/p"

        /** About 40 requests a second per address is TMDB's ceiling; a tenth of that is plenty. */
        const val GAP_MS = 100L
        const val DEFAULT_WAIT_MS = 10_000L

        // Sizes that fit what draws them: a poster at most ~240 dp wide, a 16:9 tile ~400 px.
        const val POSTER_SIZE = "w342"
        const val BACKDROP_SIZE = "w780"
        const val STILL_SIZE = "w300"

        /** A face in the cast row, drawn at most ~96 dp wide. */
        const val PROFILE_SIZE = "w185"
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
            MetaExtras(
                genres = genres,
                runtimeMin = json.optInt("runtime").takeIf { it > 0 } ?: json.optInt("averageRuntime").takeIf { it > 0 },
                seasons = seasonList.size.takeIf { it > 0 },
                cast = cast,
                seasonList = seasonList,
                nextEpisode = next,
                ended = json.optString("status") == "Ended",
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
                cast = cast,
                trailer = trailer,
                similar = similar,
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

        private const val EXTRAS_QUERY =
            "query (\$id: Int) { Media(id: \$id, type: ANIME) { genres averageScore duration trailer { id site } " +
                "characters(sort: [ROLE, RELEVANCE], perPage: 10) { edges { node { name { full } image { medium } } " +
                "voiceActors(language: JAPANESE) { name { full } } } } " +
                "recommendations(perPage: 20, sort: RATING_DESC) { nodes { mediaRecommendation { id format isAdult title { romaji english } } } } } }"
    }
}

private val TAGS = Regex("<[^>]+>")
private val SPACES = Regex("""[ \t]+""")

/** TVmaze's summaries are HTML and AniList's carry `<br>`; both read as plain text here. */
internal fun metaPlain(html: String): String = html
    .replace(Regex("(?i)<br\\s*/?>"), "\n")
    .replace(TAGS, "")
    .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
    .replace("&nbsp;", " ")
    .replace(SPACES, " ")
    .lines().joinToString("\n") { it.trim() }
    .replace(Regex("\n{3,}"), "\n\n")
    .trim()
