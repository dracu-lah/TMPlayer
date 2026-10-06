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

    companion object {
        const val BASE = "https://graphql.anilist.co"
        const val GAP_MS = 2_100L
        const val DEFAULT_WAIT_MS = 60_000L

        private const val QUERY =
            "query (\$search: String) { Page(perPage: 5) { media(search: \$search, type: ANIME, isAdult: false) { " +
                "id format seasonYear startDate { year } title { romaji english native } synonyms " +
                "description(asHtml: false) coverImage { large } bannerImage } } }"
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
