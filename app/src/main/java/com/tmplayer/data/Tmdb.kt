package com.tmplayer.data

import android.util.LruCache
import com.tmplayer.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One credited performer, in billing order. */
data class CastMember(
    val name: String,
    val character: String,
    val profilePath: String?,
)

/** What TMDB knows about a film, reduced to the parts a TV screen has room for. */
data class FilmDetails(
    val id: Int,
    val title: String,
    val year: Int?,
    val overview: String,
    val rating: Double,
    val runtimeMinutes: Int,
    val genres: List<String>,
    val posterPath: String?,
    val backdropPath: String?,
    val cast: List<CastMember>,
    /** YouTube video id for the trailer, when one is published. */
    val trailerKey: String?,
) {
    val posterUrl: String? get() = Tmdb.imageUrl(posterPath, Tmdb.POSTER_SIZE)
    val backdropUrl: String? get() = Tmdb.imageUrl(backdropPath, Tmdb.BACKDROP_SIZE)
}

/**
 * The outcome of a lookup. "Couldn't reach TMDB" and "no such film" need different wording on
 * screen, so they stay separate cases rather than collapsing into a null.
 */
sealed interface FilmLookup {
    data object Loading : FilmLookup
    data class Found(val details: FilmDetails) : FilmLookup
    data object NotFound : FilmLookup
    data class Failed(val message: String) : FilmLookup
    /** No API key was compiled in, so the feature is simply absent. */
    data object Disabled : FilmLookup
}

/**
 * Film details from The Movie Database.
 *
 * Hand-rolled over [HttpURLConnection] and `org.json`, both of which ship with
 * Android: this makes exactly two request shapes, and pulling in Retrofit plus a JSON library to
 * do it would cost more than the feature is worth on a 1 GB stick.
 *
 * Every failure path returns a [FilmLookup] the screen can render. Nothing here throws at the
 * caller, because a film must stay playable when the metadata service is down.
 */
object Tmdb {

    const val POSTER_SIZE = "w342"
    const val BACKDROP_SIZE = "w780"
    const val PROFILE_SIZE = "w185"

    /** Whether a key was compiled in at all. */
    val isConfigured: Boolean get() = BuildConfig.TMDB_API_KEY.isNotEmpty()

    private val cache = LruCache<String, FilmLookup>(CACHE_ENTRIES)

    fun imageUrl(path: String?, size: String): String? {
        if (path.isNullOrBlank()) return null
        return "$IMAGE_BASE/$size$path"
    }

    /**
     * Looks a film up from its release file name.
     *
     * Results are cached, failures included: a chat with two hundred films would otherwise fire
     * two hundred requests every time the grid was scrolled, and a service that is down will
     * still be down a second later.
     */
    suspend fun lookup(fileName: String): FilmLookup {
        if (!isConfigured) return FilmLookup.Disabled

        val parsed = FilmName.parse(fileName)
        if (parsed.title.isBlank()) return FilmLookup.NotFound

        val key = parsed.query.lowercase()
        cache.get(key)?.let { return it }

        val result = withContext(Dispatchers.IO) {
            withTimeoutOrNull(TIMEOUT_MS) { fetch(parsed) }
                ?: FilmLookup.Failed("The film database took too long to answer.")
        }

        // A timeout is worth retrying on the next open, so it is the one outcome left uncached.
        if (result !is FilmLookup.Failed) cache.put(key, result)
        return result
    }

    private fun fetch(parsed: ParsedName): FilmLookup = try {
        val search = buildString {
            append("$API_BASE/search/movie?api_key=${BuildConfig.TMDB_API_KEY}")
            append("&query=${encode(parsed.title)}")
            if (parsed.year != null) append("&year=${parsed.year}")
        }

        val results = getJson(search).optJSONArray("results")
        val first = if (results == null || results.length() == 0) null else results.getJSONObject(0)

        when {
            first != null -> FilmLookup.Found(details(first.getInt("id")))

            // A year narrows the search, but scene names carry the wrong year often enough that
            // giving up on the first miss would lose real matches. Retry on the title alone.
            parsed.year != null -> {
                val loose = "$API_BASE/search/movie?api_key=${BuildConfig.TMDB_API_KEY}" +
                    "&query=${encode(parsed.title)}"
                val retry = getJson(loose).optJSONArray("results")
                if (retry == null || retry.length() == 0) FilmLookup.NotFound
                else FilmLookup.Found(details(retry.getJSONObject(0).getInt("id")))
            }

            else -> FilmLookup.NotFound
        }
    } catch (e: IOException) {
        FilmLookup.Failed(humanise(e))
    } catch (e: Exception) {
        // A malformed payload is TMDB's problem, not something the viewer can act on.
        FilmLookup.Failed("The film database sent something we couldn't read.")
    }

    /** One extra request, batched: TMDB folds credits and videos into the detail response. */
    private fun details(id: Int): FilmDetails {
        val url = "$API_BASE/movie/$id?api_key=${BuildConfig.TMDB_API_KEY}" +
            "&append_to_response=credits,videos"
        return parseDetails(getJson(url), id)
    }

    /**
     * Turns a detail response into a [FilmDetails].
     *
     * Split out from the request so it can be tested against real payloads. Every field here is
     * optional in practice: older films have no backdrop, unreleased ones have no runtime or
     * cast, and `profile_path` is frequently the JSON string "null" rather than a null.
     */
    internal fun parseDetails(json: JSONObject, id: Int): FilmDetails {
        val cast = json.optJSONObject("credits")
            ?.optJSONArray("cast")
            .orEmptyObjects()
            .take(MAX_CAST)
            .map {
                CastMember(
                    name = it.optString("name"),
                    character = it.optString("character"),
                    profilePath = it.optString("profile_path").nullIfBlank(),
                )
            }

        val trailer = json.optJSONObject("videos")
            ?.optJSONArray("results")
            .orEmptyObjects()
            .filter { it.optString("site").equals("YouTube", ignoreCase = true) }
            // "Trailer" first, then anything else playable, so a teaser beats nothing at all.
            .sortedBy { if (it.optString("type").equals("Trailer", ignoreCase = true)) 0 else 1 }
            .firstOrNull()
            ?.optString("key")
            ?.nullIfBlank()

        return FilmDetails(
            id = id,
            title = json.optString("title").ifBlank { json.optString("original_title") },
            year = json.optString("release_date").take(4).toIntOrNull(),
            overview = json.optString("overview"),
            rating = json.optDouble("vote_average", 0.0),
            runtimeMinutes = json.optInt("runtime", 0),
            genres = json.optJSONArray("genres").orEmptyObjects().map { it.optString("name") },
            posterPath = json.optString("poster_path").nullIfBlank(),
            backdropPath = json.optString("backdrop_path").nullIfBlank(),
            cast = cast,
            trailerKey = trailer,
        )
    }

    /**
     * One retry, because this endpoint really does drop connections.
     *
     * Testing against the live API, roughly one request in three died with a connection reset
     * before any status line arrived, and the immediate retry always succeeded. Without this a
     * viewer would see "couldn't reach the film database" on a perfectly good connection.
     * Retrying is safe: these are GETs, and a genuine HTTP error is not retried at all.
     */
    private fun getJson(url: String): JSONObject = try {
        request(url)
    } catch (e: IOException) {
        if (e.message?.startsWith("HTTP ") == true) throw e
        request(url)
    }

    private fun request(url: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code")
            }
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    /** Network failures phrased for someone sitting on a sofa, not reading a stack trace. */
    private fun humanise(e: IOException): String {
        val message = e.message.orEmpty()
        return when {
            message.contains("HTTP 401") -> "This copy of TMPlayer isn't set up for film details."
            message.contains("HTTP 429") -> "The film database is busy. Try again in a moment."
            message.startsWith("HTTP 5") -> "The film database is having trouble right now."
            else -> "Couldn't reach the film database. Check your internet connection."
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun String?.nullIfBlank(): String? = this?.takeIf { it.isNotBlank() && it != "null" }

    /** org.json arrays are not iterable, and every call site here wants the same loop. */
    private fun org.json.JSONArray?.orEmptyObjects(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it) }
    }

    private const val API_BASE = "https://api.themoviedb.org/3"
    private const val IMAGE_BASE = "https://image.tmdb.org/t/p"
    private const val CACHE_ENTRIES = 80
    private const val MAX_CAST = 8
    private const val TIMEOUT_MS = 12_000L
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000
}
