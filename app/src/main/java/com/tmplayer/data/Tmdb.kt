package com.tmplayer.data

import android.util.LruCache
import com.tmplayer.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

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
    /**
     * For an episode: which one it is and what it is called, as "S2 E4 · Wednesday's Child".
     * Null for a film, which is how the screen tells the two apart.
     */
    val episodeLabel: String? = null,
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

    @Volatile
    private var diskDir: File? = null

    /** Guards against two cards looking the same film up at once, which is the common case. */
    private val inFlight = mutableMapOf<String, Mutex>()
    private val inFlightLock = Mutex()

    /**
     * Points the answer cache at a directory on disk.
     *
     * The memory cache dies with the process, and this app is routinely killed behind the player
     * on a 1 GB stick, so without this every launch re-asks TMDB about the same films: the details
     * panel and the artwork on every Continue watching card. The payloads are a few kilobytes each.
     */
    fun init(cacheDir: File) {
        diskDir = File(cacheDir, "tmdb-answers").apply { mkdirs() }
    }

    fun imageUrl(path: String?, size: String): String? {
        if (path.isNullOrBlank()) return null
        return "$IMAGE_BASE/$size$path"
    }

    /**
     * Looks a film up from its release file name.
     *
     * Answers are cached in memory, then on disk, failures included: a chat with two hundred films
     * would otherwise fire two hundred requests every time the grid was scrolled, and a service
     * that is down will still be down a second later.
     */
    suspend fun lookup(fileName: String): FilmLookup {
        if (!isConfigured) return FilmLookup.Disabled

        val parsed = FilmName.parse(fileName)
        if (parsed.title.isBlank()) return FilmLookup.NotFound

        // The episode is part of the key, or every episode of a series would be handed the
        // answer cached for whichever one was opened first.
        val key = if (parsed.isSeries) {
            "${parsed.query} s${parsed.season}e${parsed.episode}".lowercase()
        } else {
            parsed.query.lowercase()
        }
        cache.get(key)?.let { return it }

        // One request per film, however many cards are asking. Whoever queues behind the first
        // finds the answer in the memory cache and never touches the network.
        val lock = inFlightLock.withLock { inFlight.getOrPut(key) { Mutex() } }
        return lock.withLock {
            cache.get(key)?.let { return@withLock it }

            val result = withContext(Dispatchers.IO) {
                fromDisk(key)
                    ?: withTimeoutOrNull(TIMEOUT_MS) {
                        if (parsed.isSeries) fetchEpisode(parsed, key) else fetch(parsed, key)
                    }
                    ?: FilmLookup.Failed("The film database took too long to answer.")
            }

            // A timeout is worth retrying on the next open, so it is the one outcome left uncached.
            if (result !is FilmLookup.Failed) cache.put(key, result)
            result
        }.also {
            inFlightLock.withLock { inFlight.remove(key) }
        }
    }

    /**
     * A previous answer, if one was stored and has not gone stale.
     *
     * A film's details barely change once it is released, so [DISK_TTL_MS] is generous; "no such
     * film" expires sooner, because that is the answer most likely to be wrong later: TMDB gains
     * entries, and a release name we could not match today may match after the next scene rename.
     */
    private fun fromDisk(key: String): FilmLookup? {
        val file = cacheFile(key) ?: return null
        if (!file.exists()) return null
        return runCatching {
            val stored = JSONObject(file.readText())
            val age = System.currentTimeMillis() - stored.optLong("at")
            val notFound = stored.optBoolean("notFound")
            val ttl = if (notFound) MISS_TTL_MS else DISK_TTL_MS
            when {
                age !in 0..ttl -> null
                notFound -> FilmLookup.NotFound
                else -> stored.optJSONObject("details")?.let {
                    FilmLookup.Found(parseDetails(it, stored.optInt("id")))
                }
            }
        }.getOrNull()
    }

    private fun toDisk(key: String, id: Int, details: JSONObject?) {
        val file = cacheFile(key) ?: return
        runCatching {
            val stored = JSONObject()
                .put("at", System.currentTimeMillis())
                .put("id", id)
            if (details == null) stored.put("notFound", true) else stored.put("details", details)
            // Through a temporary file: a write cut halfway would otherwise leave truncated JSON
            // that every later read has to throw away, which is the cache quietly not working.
            val temp = File(file.parentFile, "${file.name}.part")
            temp.writeText(stored.toString())
            if (!temp.renameTo(file)) temp.delete()
        }
    }

    private fun cacheFile(key: String): File? {
        val dir = diskDir ?: return null
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return File(dir, digest.joinToString("") { "%02x".format(it) })
    }

    /** Called when the viewer clears the cache, so answers do not survive a deliberate wipe. */
    fun clearCache() {
        cache.evictAll()
        runCatching { diskDir?.listFiles()?.forEach { it.delete() } }
    }

    private fun fetch(parsed: ParsedName, key: String): FilmLookup = try {
        val search = buildString {
            append("$API_BASE/search/movie?api_key=${BuildConfig.TMDB_API_KEY}")
            append("&query=${encode(parsed.title)}")
            if (parsed.year != null) append("&year=${parsed.year}")
        }

        val results = getJson(search).optJSONArray("results")
        val first = if (results == null || results.length() == 0) null else results.getJSONObject(0)

        when {
            first != null -> FilmLookup.Found(details(first.getInt("id"), key))

            // A year narrows the search, but scene names carry the wrong year often enough that
            // giving up on the first miss would lose real matches. Retry on the title alone.
            parsed.year != null -> {
                val loose = "$API_BASE/search/movie?api_key=${BuildConfig.TMDB_API_KEY}" +
                    "&query=${encode(parsed.title)}"
                val retry = getJson(loose).optJSONArray("results")
                if (retry == null || retry.length() == 0) {
                    toDisk(key, id = 0, details = null)
                    FilmLookup.NotFound
                } else {
                    FilmLookup.Found(details(retry.getJSONObject(0).getInt("id"), key))
                }
            }

            else -> {
                toDisk(key, id = 0, details = null)
                FilmLookup.NotFound
            }
        }
    } catch (e: IOException) {
        FilmLookup.Failed(humanise(e))
    } catch (e: Exception) {
        // A malformed payload is TMDB's problem, not something the viewer can act on.
        FilmLookup.Failed("The film database sent something we couldn't read.")
    }

    /**
     * The same job for a series, against TMDB's separate television index.
     *
     * Searching the film index for "Wednesday" returns a film called Wednesday, confidently and
     * wrongly, so the two indexes are never mixed. The show supplies the poster, the cast and the
     * genres; the episode supplies the parts that differ between one week and the next.
     */
    private fun fetchEpisode(parsed: ParsedName, key: String): FilmLookup = try {
        val search = buildString {
            append("$API_BASE/search/tv?api_key=${BuildConfig.TMDB_API_KEY}")
            append("&query=${encode(parsed.title)}")
            // TV search wants the year as its own parameter; folded into the query it is read as
            // part of the show's name and matches nothing.
            if (parsed.year != null) append("&first_air_date_year=${parsed.year}")
        }

        val results = getJson(search).optJSONArray("results")
        val show = if (results == null || results.length() == 0) {
            // Same reasoning as for films: scene names carry the wrong year often enough that a
            // single miss is not an answer.
            if (parsed.year == null) null else {
                val loose = "$API_BASE/search/tv?api_key=${BuildConfig.TMDB_API_KEY}" +
                    "&query=${encode(parsed.title)}"
                getJson(loose).optJSONArray("results")?.optJSONObject(0)
            }
        } else {
            results.getJSONObject(0)
        }

        if (show == null) {
            toDisk(key, id = 0, details = null)
            FilmLookup.NotFound
        } else {
            val id = show.getInt("id")
            val json = getJson(
                "$API_BASE/tv/$id?api_key=${BuildConfig.TMDB_API_KEY}" +
                    "&append_to_response=credits,videos",
            )
            // A whole-season pack names no episode to fetch, and a season or episode number the
            // uploader invented is a 404. Neither is worth losing the show over: the panel falls
            // back to the series' own details.
            if (parsed.season != null && parsed.episode != null) {
                runCatching {
                    getJson(
                        "$API_BASE/tv/$id/season/${parsed.season}/episode/${parsed.episode}" +
                            "?api_key=${BuildConfig.TMDB_API_KEY}",
                    )
                }.getOrNull()?.let { json.put(EPISODE_KEY, it) }
            }

            toDisk(key, id, json)
            FilmLookup.Found(parseDetails(json, id))
        }
    } catch (e: IOException) {
        FilmLookup.Failed(humanise(e))
    } catch (e: Exception) {
        FilmLookup.Failed("The film database sent something we couldn't read.")
    }

    /** One extra request, batched: TMDB folds credits and videos into the detail response. */
    private fun details(id: Int, key: String): FilmDetails {
        val url = "$API_BASE/movie/$id?api_key=${BuildConfig.TMDB_API_KEY}" +
            "&append_to_response=credits,videos"
        val json = getJson(url)
        // Stored as it arrived rather than as a flattened [FilmDetails]: the payload is already
        // the format this file knows how to read, so nothing needs a second parser or a migration
        // the day a field is added to the model.
        toDisk(key, id, json)
        return parseDetails(json, id)
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

        // A series answers to different field names throughout: name for title, first_air_date
        // for release_date, episode_run_time for runtime. Reading both keeps one model on screen.
        val episode = json.optJSONObject(EPISODE_KEY)
        val showRuntime = json.optInt("runtime", 0)
            .takeIf { it > 0 }
            ?: json.optJSONArray("episode_run_time").let { runtimes ->
                if (runtimes == null || runtimes.length() == 0) 0 else runtimes.optInt(0, 0)
            }

        return FilmDetails(
            id = id,
            title = json.optString("title")
                .ifBlank { json.optString("name") }
                .ifBlank { json.optString("original_title") }
                .ifBlank { json.optString("original_name") },
            year = json.optString("release_date").ifBlank { json.optString("first_air_date") }
                .take(4).toIntOrNull(),
            // An episode's own synopsis is the whole point of opening it; the series blurb is
            // the same on all ten and only helps when the episode has none written yet.
            overview = episode?.optString("overview")?.ifBlank { null }
                ?: json.optString("overview"),
            rating = episode?.optDouble("vote_average", 0.0)?.takeIf { it > 0 }
                ?: json.optDouble("vote_average", 0.0),
            runtimeMinutes = episode?.optInt("runtime", 0)?.takeIf { it > 0 } ?: showRuntime,
            genres = json.optJSONArray("genres").orEmptyObjects().map { it.optString("name") },
            // The poster stays the series poster: the grid is posters, and an episode still is
            // the wrong shape for that column.
            posterPath = json.optString("poster_path").nullIfBlank(),
            backdropPath = episode?.optString("still_path")?.nullIfBlank()
                ?: json.optString("backdrop_path").nullIfBlank(),
            cast = cast,
            trailerKey = trailer,
            episodeLabel = episode?.let { label(it) },
        )
    }

    /** "S2 E4 · Wednesday's Child", or just "S2 E4" for an episode nobody has titled. */
    private fun label(episode: JSONObject): String {
        val numbers = "S${episode.optInt("season_number")} E${episode.optInt("episode_number")}"
        val name = episode.optString("name").nullIfBlank() ?: return numbers
        return "$numbers  ·  $name"
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

    /**
     * Where an episode is folded into the show's response before it is cached.
     *
     * The disk cache stores the payload as it arrived and reads it back through [parseDetails],
     * so an episode has to travel inside that same object or it would need a second format and a
     * second parser. The prefix keeps it clear of anything TMDB might add later.
     */
    private const val EPISODE_KEY = "tmplayer_episode"

    private const val API_BASE = "https://api.themoviedb.org/3"
    private const val IMAGE_BASE = "https://image.tmdb.org/t/p"
    private const val CACHE_ENTRIES = 80

    /** A released film's details do not change; a month is short only against how long they last. */
    private const val DISK_TTL_MS = 30L * 24 * 60 * 60 * 1000

    /** "No such film" is the answer most likely to be wrong later, so it is kept for a week. */
    private const val MISS_TTL_MS = 7L * 24 * 60 * 60 * 1000
    private const val MAX_CAST = 8
    private const val TIMEOUT_MS = 12_000L
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000
}
