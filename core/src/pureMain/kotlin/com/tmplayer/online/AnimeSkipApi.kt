package com.tmplayer.online

import com.tmplayer.json.JSONArray
import com.tmplayer.json.JSONObject
import com.tmplayer.platform.IOException
import com.tmplayer.platform.PlainLock

/**
 * Where TMPlayer's anime-skip.com client id goes.
 *
 * TODO(user): register an API client for TMPlayer on anime-skip.com (signed in, the account's
 * "API clients" page, or `myApiClients` in the playground at https://api.anime-skip.com) and put
 * its id here. Until this is filled in, [AnimeSkipApi] asks nobody anything and the lookup falls
 * through to AniSkip alone, so nothing else changes. A viewer never sees this id.
 *
 * Their terms, as far as they are published (2026-11-16): the API is open source (GPL-3.0,
 * github.com/anime-skip/public-api); every request names a client in an `X-Client-ID` header, and
 * the server counts requests per client against a per minute limit it sets for that client,
 * answering a client over it with HTTP 403 "Rate limit exceeded". Their site's own terms page
 * could not be read by a script (it needs a browser), so the user should read
 * anime-skip.com's terms and privacy policy once when registering the client.
 */
object SkipSources {
    const val ANIME_SKIP_CLIENT_ID: String = ""
}

/**
 * anime-skip.com, the second source for anime after AniSkip: a community database of timestamps,
 * asked by AniList id (`findShowsByExternalId`), so the only things sent are the id and, in the
 * answer's matching, the episode number the viewer already has: no title, no file name, no chat.
 *
 * One query answers a whole show, every episode with its timestamps, which is kept for ten
 * minutes so a season watched in a row asks once. A timestamp there is a point where a type of
 * stretch begins and runs to the next timestamp (the types are Canon, Intro, New Intro, Mixed
 * Intro, Recap, Credits, New Credits, Mixed Credits, Preview, Title Card, Branding, Transition,
 * Filler, Must Watch, Unknown); [segmentsOf] turns them into [SkipSegment]s.
 *
 * A blank [clientId] makes [segments] answer "nothing timed" without a request. An empty show, a
 * 404 and an episode nobody timed are all [MetaReply.Ok] with an empty list, so the caller keeps
 * them like any answer; the 403 the server gives a client over its limit is [MetaReply.Throttled]
 * for a minute, so it is neither kept as "nothing timed" nor asked again at once.
 */
class AnimeSkipApi(
    private val http: HttpTransport,
    private val limiter: RateLimiter,
    private val userAgent: String,
    private val clientId: String = SkipSources.ANIME_SKIP_CLIENT_ID,
    private val now: () -> Long = { com.tmplayer.platform.nowMillis() },
) {
    val configured: Boolean get() = clientId.isNotBlank()

    private class Cached(val at: Long, val episodes: List<Episode>)

    /** One episode as the show lists it: its numbers, its running time, and the stretches already read. */
    internal class Episode(val number: Int?, val absolute: Int?, val baseSec: Double?, val segments: List<SkipSegment>)

    private val shows = LinkedHashMap<String, Cached>()
    private val lock = PlainLock()

    suspend fun segments(anilistId: String, episode: Int, lengthSec: Int): MetaReply<List<SkipSegment>> {
        if (!configured || anilistId.isBlank() || anilistId.length > 12 || !anilistId.all { it.isDigit() }) {
            return MetaReply.Ok(emptyList())
        }
        val cached = lock.locked { shows[anilistId]?.takeIf { now() - it.at < SHOW_TTL_MS } }
        val episodes = cached?.episodes ?: when (val reply = fetch(anilistId)) {
            is MetaReply.Ok -> reply.value.also { list ->
                lock.locked {
                    shows[anilistId] = Cached(now(), list)
                    while (shows.size > SHOWS_KEPT) shows.remove(shows.keys.first())
                }
            }
            is MetaReply.Throttled -> return reply
            is MetaReply.Failed -> return reply
            MetaReply.Refused -> return MetaReply.Refused
        }
        return MetaReply.Ok(pick(episodes, episode, lengthSec))
    }

    private suspend fun fetch(anilistId: String): MetaReply<List<Episode>> {
        limiter.acquire()
        val body = JSONObject()
            .put("query", QUERY)
            .put("variables", JSONObject().put("id", anilistId))
            .toString()
        val headers = mapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "User-Agent" to userAgent,
            "X-Client-ID" to clientId,
        )
        val response = try {
            http.send(HttpRequest("POST", ENDPOINT, headers, body))
        } catch (e: IOException) {
            return MetaReply.Failed(0)
        }
        return when (response.code) {
            404 -> MetaReply.Ok(emptyList())
            429 -> MetaReply.Throttled(response.header("retry-after")?.trim()?.toLongOrNull()?.let { (it * 1000).coerceIn(1_000, 600_000) } ?: RATE_WAIT_MS)
            403 -> if (response.text.take(400).contains("rate limit", ignoreCase = true)) MetaReply.Throttled(RATE_WAIT_MS) else MetaReply.Refused
            401 -> MetaReply.Refused
            in 200..299 -> {
                if (response.body.size > MAX_BYTES) MetaReply.Failed(response.code) else parse(response)
            }
            else -> MetaReply.Failed(response.code)
        }
    }

    private fun parse(response: HttpResponse): MetaReply<List<Episode>> = runCatching {
        val json = JSONObject(response.text)
        val shows = json.optJSONObject("data")?.optJSONArray("findShowsByExternalId")
        if (shows == null) {
            // Errors and no data is a failure to ask again later; data with no show is "not timed".
            if (json.optJSONArray("errors") != null) MetaReply.Failed(response.code) else MetaReply.Ok(emptyList<Episode>())
        } else {
            val episodes = ArrayList<Episode>()
            for (s in 0 until minOf(shows.length(), MAX_SHOWS)) {
                val list = shows.optJSONObject(s)?.optJSONArray("episodes") ?: continue
                for (i in 0 until minOf(list.length(), MAX_EPISODES)) {
                    val e = list.optJSONObject(i) ?: continue
                    val segments = segmentsOf(e.optJSONArray("timestamps"), e.optDouble("baseDuration", 0.0))
                    if (segments.isEmpty()) continue
                    episodes += Episode(
                        number = numberOf(e.opt("number")),
                        absolute = numberOf(e.opt("absoluteNumber")),
                        baseSec = e.optDouble("baseDuration", 0.0).takeIf { it > 0 && it < 86_400 },
                        segments = segments,
                    )
                }
            }
            MetaReply.Ok(episodes)
        }
    }.getOrElse { MetaReply.Failed(response.code) }

    /** The episode [episode] of a show's list: by number, else by absolute number; a cut of another length is not it. */
    private fun pick(episodes: List<Episode>, episode: Int, lengthSec: Int): List<SkipSegment> {
        val byNumber = episodes.filter { it.number == episode }.ifEmpty { episodes.filter { it.absolute == episode } }
        val fits = byNumber.filter { lengthSec <= 0 || it.baseSec == null || kotlin.math.abs(it.baseSec - lengthSec) <= LENGTH_SLACK_SEC }
        return fits.firstOrNull()?.segments.orEmpty()
    }

    companion object {
        const val ENDPOINT = "https://api.anime-skip.com/graphql"
        const val GAP_MS = 700L
        const val SHOW_TTL_MS = 10 * 60_000L
        const val RATE_WAIT_MS = 60_000L
        private const val SHOWS_KEPT = 3
        private const val MAX_SHOWS = 4
        private const val MAX_EPISODES = 6_000
        private const val MAX_BYTES = 12 * 1024 * 1024
        private const val LENGTH_SLACK_SEC = 45

        private const val QUERY =
            "query(\$id: String!) { findShowsByExternalId(service: ANILIST, serviceId: \$id) { " +
                "episodes { number absoluteNumber baseDuration timestamps { at type { name } } } } }"

        private fun numberOf(value: Any?): Int? = when (value) {
            is Number -> value.toDouble().takeIf { it >= 0 && it < 100_000 && it == kotlin.math.floor(it) }?.toInt()
            is String -> value.trim().toDoubleOrNull()?.takeIf { it >= 0 && it < 100_000 && it == kotlin.math.floor(it) }?.toInt()
            else -> null
        }

        /** anime-skip's timestamp type for the skip kind it stands for; the rest (Canon, Title Card...) only end a stretch. */
        fun kindOf(type: String?): SkipKind? = when (type?.trim()?.lowercase()) {
            "intro", "new intro", "mixed intro" -> SkipKind.Opening
            "credits", "new credits", "mixed credits" -> SkipKind.Ending
            "recap" -> SkipKind.Recap
            "preview" -> SkipKind.Preview
            else -> null
        }

        /**
         * Timestamps are points: each starts a stretch of its type that runs to the next. One that
         * names a skip kind becomes a segment to the next point (credits with none after them run to
         * the end); an intro, recap or preview with nothing after it has no end, which is a
         * half-filled entry, and is dropped. A stretch under three seconds or over ten minutes is
         * dropped too (credits may run longer).
         */
        internal fun segmentsOf(stamps: JSONArray?, baseSec: Double): List<SkipSegment> {
            if (stamps == null) return emptyList()
            val points = ArrayList<Pair<Long, String>>()
            for (i in 0 until minOf(stamps.length(), 200)) {
                val s = stamps.optJSONObject(i) ?: continue
                val at = s.optDouble("at", -1.0)
                if (!(at >= 0 && at < 86_400)) continue
                points += (at * 1000).toLong() to s.optJSONObject("type")?.optString("name").orEmpty()
            }
            points.sortBy { it.first }
            val out = ArrayList<SkipSegment>()
            points.forEachIndexed { i, (start, type) ->
                val kind = kindOf(type) ?: return@forEachIndexed
                val closing = kind == SkipKind.Ending
                val next = points.getOrNull(i + 1)?.first?.takeIf { it > start }
                if (next == null) {
                    if (closing) out += SkipSegment(kind, start, null)
                    return@forEachIndexed
                }
                val length = next - start
                if (length < 3_000 || length > (if (closing) 20 else 10) * 60_000L) return@forEachIndexed
                out += SkipSegment(kind, start, next)
            }
            return out
        }
    }
}

/** The ids a public id-mapping service knows for one anime, each null where it has none. */
data class AnimeIds(val mal: String? = null, val imdb: String? = null, val tvdb: String? = null, val tmdb: String? = null)

/**
 * A second chance for an anime's ids, for when AniList gives no MyAnimeList id (`idMal`): the
 * public id mapping at arm.haglund.dev (no key, open source) answers an AniList id with the ids
 * of the same title on MyAnimeList, TheTVDB, IMDb and TMDB, which is what AniSkip and the other
 * community databases are keyed by. Only the AniList id is sent. A 404, which is what it says for
 * an id it does not map, is an empty [AnimeIds].
 */
class IdMapApi(
    private val http: HttpTransport,
    private val limiter: RateLimiter,
    private val userAgent: String,
) {
    suspend fun forAniList(anilistId: String): MetaReply<AnimeIds> {
        if (anilistId.isBlank() || anilistId.length > 12 || !anilistId.all { it.isDigit() }) return MetaReply.Ok(AnimeIds())
        limiter.acquire()
        val request = HttpRequest("GET", "$BASE/ids?source=anilist&id=$anilistId", mapOf("Accept" to "application/json", "User-Agent" to userAgent))
        val response = try {
            http.send(request)
        } catch (e: IOException) {
            return MetaReply.Failed(0)
        }
        return when (response.code) {
            404 -> MetaReply.Ok(AnimeIds())
            429 -> MetaReply.Throttled(response.header("retry-after")?.trim()?.toLongOrNull()?.let { (it * 1000).coerceIn(1_000, 600_000) } ?: 30_000L)
            in 200..299 -> runCatching {
                val json = JSONObject(response.text)
                MetaReply.Ok(
                    AnimeIds(
                        mal = idOf(json.opt("myanimelist")),
                        imdb = (json.opt("imdb") as? String)?.trim()?.takeIf { IMDB.matches(it) },
                        tvdb = idOf(json.opt("thetvdb")),
                        tmdb = idOf(json.opt("themoviedb")),
                    ),
                )
            }.getOrElse { MetaReply.Failed(response.code) }
            else -> MetaReply.Failed(response.code)
        }
    }

    private fun idOf(value: Any?): String? = when (value) {
        is Number -> value.toLong().takeIf { it in 1..999_999_999 }?.toString()
        is String -> value.trim().takeIf { it.length in 1..12 && it.all(Char::isDigit) && it != "0" }
        else -> null
    }

    companion object {
        const val BASE = "https://arm.haglund.dev/api/v2"
        const val GAP_MS = 700L
        private val IMDB = Regex("tt\\d{5,10}")
    }
}
