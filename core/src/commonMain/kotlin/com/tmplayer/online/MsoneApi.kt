package com.tmplayer.online

import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/**
 * What TMPlayer's gateway at subs.tmplayer.org says about MSone (malayalamsubtitles.org): [On]
 * when searches work, [Off] while no key is set there, [Paused] or [Stopped] when MSone has asked
 * us to wait or to stop, and [Unknown] before the first check has answered.
 */
enum class MsoneState {
    On, Off, Paused, Stopped, Unknown;

    companion object {
        fun of(text: String?): MsoneState = when (text?.trim()?.lowercase(Locale.ROOT)) {
            "on" -> On
            "off" -> Off
            "paused" -> Paused
            "stopped" -> Stopped
            else -> Unknown
        }
    }
}

/** The last state the gateway gave, its optional [message] shown as is, and when it was asked. */
data class MsoneStatus(val state: MsoneState, val message: String?, val checkedAt: Long)

/**
 * The MSone half of TMPlayer's subtitle gateway: `/health`, and `/msone/search`, which answers
 * with metadata only (the apps download each file from MSone themselves, which is what MSone
 * counts). The shape is the gateway repo's `API.md`, version 0, a draft until MSone's own search
 * contract is known, so every field is read as optional and a hit without what it needs is
 * dropped rather than failing the list.
 */
class MsoneApi(
    private val http: HttpTransport,
    private val limiter: RateLimiter,
    private val userAgent: String,
    private val base: String = BASE,
) {
    /** Why a call gave no hits: the gateway's own error codes, sorted into what the screens tell apart. */
    sealed interface Failure {
        /**
         * 410 `stopped` or 503 `paused`: the operator's switch, which is the state from now on. A
         * 503 `unavailable` is not one: the gateway also answers it for any failure of its own, so
         * it reads as [Offline] and only `/health` says whether MSone is off.
         */
        data class State(val state: MsoneState, val message: String?) : Failure

        /** 400 `bad_query`: nothing usable to search with. */
        data object BadQuery : Failure

        /** 429 `rate_limited`, with the wait it asked for. */
        data class Busy(val retryAfterMs: Long) : Failure

        /** No answer, a server error, or one that could not be read. */
        data object Offline : Failure
    }

    sealed interface Answer<out T> {
        data class Ok<T>(val value: T) : Answer<T>
        data class Failed(val failure: Failure) : Answer<Nothing>
    }

    /** What to search for: the IMDb id when known, else the title with its year. */
    data class Query(
        val imdb: String? = null,
        val title: String? = null,
        val year: Int? = null,
        val season: Int? = null,
        val episode: Int? = null,
    )

    suspend fun health(): Answer<MsoneStatus> {
        val response = get("$base/health") ?: return Answer.Failed(Failure.Offline)
        if (response.code !in 200..299) {
            ProviderLog.status(PROVIDER, "GET", "$base/health", response)
            return Answer.Failed(Failure.Offline)
        }
        return parseHealth(response.text, 0)?.let { Answer.Ok(it) } ?: Answer.Failed(Failure.Offline)
    }

    suspend fun search(query: Query): Answer<List<SubtitleHit>> {
        val params = linkedMapOf<String, String>()
        val imdb = query.imdb?.trim()?.lowercase(Locale.ROOT)?.takeIf { IMDB.matches(it) }
        if (imdb != null) {
            params["imdb"] = imdb
        } else {
            val title = query.title?.trim()?.take(MAX_QUERY)?.takeIf { it.isNotEmpty() } ?: return Answer.Failed(Failure.BadQuery)
            params["q"] = title
            query.year?.takeIf { it in 1870..2100 }?.let { params["year"] = it.toString() }
        }
        query.season?.takeIf { it in 0..999 }?.let { params["season"] = it.toString() }
        query.episode?.takeIf { it in 0..9999 }?.let { params["episode"] = it.toString() }
        val url = "$base/msone/search?" + params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val response = get(url) ?: return Answer.Failed(Failure.Offline)
        if (response.code == 404) return Answer.Ok(emptyList())
        if (response.code !in 200..299) {
            ProviderLog.status(PROVIDER, "GET", url, response)
            return Answer.Failed(failureOf(response))
        }
        return parseHits(response.text)?.let { Answer.Ok(it) } ?: Answer.Failed(Failure.Offline)
    }

    /**
     * The file behind [hit]: from MSone's own download address when it is one this app trusts,
     * else through the gateway, which fetches only from MSone. At most [maxBytes].
     */
    suspend fun fetch(hit: SubtitleHit, maxBytes: Int): Answer<ByteArray> {
        val url = hit.link.takeIf { trustedDownload(it) }
            ?: "$base/msone/download/${URLEncoder.encode(hit.id, "UTF-8")}"
        val response = get(url) ?: return Answer.Failed(Failure.Offline)
        if (response.code !in 200..299) {
            ProviderLog.status(PROVIDER, "GET", url, response)
            return Answer.Failed(failureOf(response))
        }
        if (response.body.size > maxBytes) {
            ProviderLog.failed(PROVIDER, "GET", url, "${response.body.size} bytes, over $maxBytes")
            return Answer.Failed(Failure.Offline)
        }
        return Answer.Ok(response.body)
    }

    private suspend fun get(url: String): HttpResponse? {
        limiter.acquire()
        return http.sendLogged(PROVIDER, HttpRequest("GET", url, mapOf("User-Agent" to userAgent, "Accept" to "application/json")))
    }

    private fun failureOf(response: HttpResponse): Failure {
        val json = runCatching { JSONObject(response.text) }.getOrNull()
        val error = json?.optString("error").orEmpty()
        val message = cleanMessage(json?.opt("message"))
        return when {
            response.code == 410 || error == "stopped" -> Failure.State(MsoneState.Stopped, message)
            error == "paused" -> Failure.State(MsoneState.Paused, message)
            response.code == 429 || error == "rate_limited" -> Failure.Busy(retryAfter(response.header("Retry-After")))
            response.code == 400 || error == "bad_query" -> Failure.BadQuery
            else -> Failure.Offline
        }
    }

    companion object {
        const val BASE = "https://subs.tmplayer.org"
        private const val PROVIDER = "MSone"

        /** The longest title sent; the gateway caps its inputs too. */
        private const val MAX_QUERY = 120

        /** The most hits read from one answer, and the longest text kept from any field. */
        private const val MAX_HITS = 100
        private const val MAX_TEXT = 500

        private val IMDB = Regex("""tt\d{5,10}""")

        /** Hosts whose download address is fetched directly; anything else goes through the gateway. */
        private val DOWNLOAD_HOSTS = listOf("malayalamsubtitles.org")

        /** `/health`'s MSone entry, or null when the answer is not one. */
        fun parseHealth(text: String, now: Long): MsoneStatus? {
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val msone = json.optJSONObject("providers")?.optJSONObject("msone") ?: return null
            return MsoneStatus(MsoneState.of(msone.opt("state") as? String), cleanMessage(msone.opt("message")), now)
        }

        /** `/msone/search`'s hits, or null when the answer is not a list of them at all. */
        fun parseHits(text: String): List<SubtitleHit>? {
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val hits = json.optJSONArray("hits") ?: return null
            return (0 until minOf(hits.length(), MAX_HITS)).mapNotNull { index -> hit(hits.optJSONObject(index)) }
        }

        private fun hit(o: JSONObject?): SubtitleHit? {
            o ?: return null
            val id = (o.opt("id") as? String ?: (o.opt("id") as? Number)?.toLong()?.toString())
                ?.trim()?.take(64)?.takeIf { it.isNotEmpty() } ?: return null
            val title = text(o.opt("title")) ?: return null
            val translator = o.optJSONObject("translator")
            val season = number(o.opt("season"))?.takeIf { it in 0..999 }
            val episode = number(o.opt("episode"))?.takeIf { it in 0..9999 }
            return SubtitleHit(
                provider = SubtitleProvider.Msone,
                id = id,
                language = "ml",
                // The title the list shows, with the episode code that tells a show's hits apart.
                release = title + (season?.let { " S%02d".format(it) + (episode?.let { e -> "E%02d".format(e) } ?: "") } ?: ""),
                featureTitle = title,
                featureYear = number(o.opt("year"))?.takeIf { it in 1870..2100 },
                featureSeason = season,
                featureEpisode = episode,
                pack = o.opt("kind") == "pack",
                translator = translator?.let { text(it.opt("name")) }.orEmpty(),
                translatorUrl = translator?.let { https(it.opt("url")) }.orEmpty(),
                pageUrl = https(o.opt("pageUrl")).orEmpty(),
                link = https(o.opt("downloadUrl")).orEmpty(),
            )
        }

        private fun text(value: Any?): String? =
            (value as? String)?.replace(CONTROL, " ")?.trim()?.take(MAX_TEXT)?.takeIf { it.isNotEmpty() }

        private fun number(value: Any?): Int? = when (value) {
            is Int -> value
            is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            is Double -> value.takeIf { it.isFinite() && it % 1.0 == 0.0 && it in -1e9..1e9 }?.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }

        /** An https address with a host, or null: a link the screens may open or the app may fetch. */
        internal fun https(value: Any?): String? {
            val url = (value as? String)?.trim()?.takeIf { it.length <= 2_000 } ?: return null
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            return url.takeIf { uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() }
        }

        internal fun trustedDownload(url: String): Boolean {
            val host = https(url)?.let { runCatching { URI(it).host.lowercase(Locale.ROOT) }.getOrNull() } ?: return false
            return DOWNLOAD_HOSTS.any { host == it || host.endsWith(".$it") }
        }

        /** The operator's message, as text a screen can show as is, or null. */
        internal fun cleanMessage(value: Any?): String? = text(value)

        /** `Retry-After` in seconds as milliseconds, between one second and an hour, a minute when unreadable. */
        internal fun retryAfter(header: String?): Long =
            (header?.trim()?.toLongOrNull()?.coerceIn(1, 3_600) ?: 60) * 1_000

        private val CONTROL = Regex("""[\u0000-\u001F\u007F]+""")
    }
}
