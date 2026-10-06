package com.tmplayer.online

import com.tmplayer.platform.Logger
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.util.Locale

/** A provider's answer, sorted into what the caller has to tell apart. */
sealed interface Reply<out T> {
    data class Ok<T>(val value: T) : Reply<T>

    /** 401: the viewer's token (or, at sign in, their password) was not accepted. */
    data object Unauthorized : Reply<Nothing>

    /** 403: the app key itself was refused. */
    data object Forbidden : Reply<Nothing>

    /** The day's downloads are used up. */
    data class Quota(val resetAt: Long?) : Reply<Nothing>

    /** 429 without a quota attached: too many requests, try again after [retryAfterMs]. */
    data class Throttled(val retryAfterMs: Long) : Reply<Nothing>

    /**
     * No connection ([code] 0), a server error, or an answer that could not be read. A 4xx other
     * than the ones above is the provider turning down this one request (OpenSubtitles answers
     * 400 to a title under three letters, for one): see [refusedRequest].
     */
    data class Failed(val code: Int) : Reply<Nothing> {
        /** The provider answered and said no to the request itself, so the network is fine. */
        val refusedRequest: Boolean get() = code in 400..499
    }
}

/**
 * The one log line for a provider call that did not come back Ok: the method, where it went
 * (scheme, host and path only, since SubDL carries its key in the query) and why. Never the key,
 * the account token or the password, none of which reach this function. Tagged `TMPlayer` so a
 * `logcat -s TMPlayer` on a viewer's device shows it.
 */
internal object ProviderLog {
    const val TAG = "TMPlayer"

    fun failed(provider: String, method: String, url: String, why: String, error: Throwable? = null) {
        Logger.w(TAG, "$provider $method ${where(url)} failed: $why", error)
    }

    fun status(provider: String, method: String, url: String, response: HttpResponse) {
        val said = response.text.take(200).replace(Regex("\\s+"), " ").trim()
        failed(provider, method, url, "HTTP ${response.code}" + if (said.isEmpty()) "" else ": $said")
    }

    /** The address without its query string. */
    fun where(url: String): String = url.substringBefore('?').substringBefore('#')

    fun describe(e: Throwable): String = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
}

/**
 * Sends [request], turning any exception into null after logging it. A provider that cannot be
 * reached is an [java.io.IOException], but a malformed address or a platform refusal is not, and
 * none of them should either escape into the screen or be swallowed without a word.
 */
internal suspend fun HttpTransport.sendLogged(provider: String, request: HttpRequest): HttpResponse? =
    try {
        send(request)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ProviderLog.failed(provider, request.method, request.url, ProviderLog.describe(e), e)
        null
    }

/**
 * OpenSubtitles.com's REST API, version 1: sign in and out, the account's quota, search, and the
 * download link. Every call carries the app key and the User-Agent the API asks for, and goes
 * through [limiter] (5 a second); sign in also goes through [loginLimiter] (1 a second).
 *
 * See https://opensubtitles.stoplight.io/docs/opensubtitles-api. Parameters are sent sorted and in
 * lower case, which is what the API wants to avoid a redirect on every search.
 */
class OpenSubtitlesApi(
    private val apiKey: String,
    private val userAgent: String,
    private val http: HttpTransport,
    private val limiter: RateLimiter,
    private val loginLimiter: RateLimiter,
) {
    data class Session(val token: String, val baseUrl: String, val allowed: Int)
    data class Quota(val allowed: Int, val remaining: Int)
    data class Link(val url: String, val fileName: String, val remaining: Int, val resetAt: Long?)

    /** A search: the hash, or a name with season and episode; never both, see [OnlineSubtitles]. */
    data class Query(
        val hash: String? = null,
        val name: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val languages: List<String>,
        val includeMachine: Boolean = false,
    )

    suspend fun login(username: String, password: String): Reply<Session> {
        loginLimiter.acquire()
        val body = JSONObject().put("username", username).put("password", password).toString()
        return call("POST", url(DEFAULT_HOST, "login"), body = body) { json ->
            val token = json.optString("token")
            if (token.isBlank()) return@call null
            Session(
                token = token,
                baseUrl = json.optString("base_url").ifBlank { DEFAULT_HOST },
                allowed = json.optJSONObject("user")?.optInt("allowed_downloads") ?: 0,
            )
        }
    }

    suspend fun userInfo(token: String, host: String): Reply<Quota> =
        call("GET", url(host, "infos/user"), token = token) { json ->
            val data = json.optJSONObject("data") ?: return@call null
            Quota(data.optInt("allowed_downloads"), data.optInt("remaining_downloads"))
        }

    suspend fun logout(token: String, host: String): Reply<Unit> =
        call("DELETE", url(host, "logout"), token = token) { }

    suspend fun search(query: Query, host: String): Reply<List<SubtitleHit>> {
        val params = sortedMapOf<String, String>()
        // `machine_translated` is no longer a parameter: the API answers every search that sends
        // it with a 301 to the same address without it, one wasted round trip per search. Machine
        // translations are flagged on each hit, and [OnlineSubtitles] filters them there.
        params["ai_translated"] = if (query.includeMachine) "include" else "exclude"
        params["languages"] = query.languages.map { it.lowercase(Locale.ROOT) }.distinct().sorted().joinToString(",")
        query.hash?.let { params["moviehash"] = it.lowercase(Locale.ROOT) }
        query.name?.let { params["query"] = it.lowercase(Locale.ROOT).trim() }
        query.season?.let { params["season_number"] = it.toString() }
        query.episode?.let { params["episode_number"] = it.toString() }
        val encoded = params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        return call("GET", url(host, "subtitles") + "?" + encoded) { json ->
            val data = json.optJSONArray("data") ?: return@call emptyList()
            (0 until data.length()).mapNotNull { index ->
                val attributes = data.optJSONObject(index)?.optJSONObject("attributes") ?: return@mapNotNull null
                val file = attributes.optJSONArray("files")?.optJSONObject(0) ?: return@mapNotNull null
                val fileId = file.optLong("file_id").takeIf { it > 0 } ?: return@mapNotNull null
                // An episode is filed under its own title ("The Nexus Event") with the show as the
                // parent ("Loki"); a film under its title alone.
                val feature = attributes.optJSONObject("feature_details")
                val featureTitle = feature?.let { f ->
                    f.optString("parent_title").takeIf { it.isNotBlank() && it != "null" }
                        ?: f.optString("title").takeIf { it.isNotBlank() && it != "null" }
                }.orEmpty()
                SubtitleHit(
                    provider = SubtitleProvider.OpenSubtitles,
                    id = fileId.toString(),
                    language = attributes.optString("language"),
                    release = attributes.optString("release").ifBlank { file.optString("file_name") },
                    fileName = file.optString("file_name"),
                    downloads = attributes.optInt("download_count"),
                    hashMatch = attributes.optBoolean("moviehash_match"),
                    hearingImpaired = attributes.optBoolean("hearing_impaired"),
                    machine = attributes.optBoolean("ai_translated") || attributes.optBoolean("machine_translated"),
                    featureTitle = featureTitle,
                    featureSeason = feature?.optInt("season_number", 0)?.takeIf { it > 0 },
                    featureEpisode = feature?.optInt("episode_number", 0)?.takeIf { it > 0 },
                    featureYear = feature?.optInt("year", 0)?.takeIf { it > 0 },
                )
            }
        }
    }

    /**
     * Asks for a file's download link. This is the call that spends one of the day's downloads:
     * the account's with a [token], or the allowance OpenSubtitles gives the app key without one.
     */
    suspend fun download(fileId: String, token: String?, host: String): Reply<Link> {
        val body = JSONObject().put("file_id", fileId.toLong()).toString()
        return call("POST", url(host, "download"), token = token, body = body, quotaCall = true) { json ->
            val link = json.optString("link")
            if (link.isBlank()) return@call null
            Link(link, json.optString("file_name"), json.optInt("remaining", -1), resetAt(json))
        }
    }

    /** The file behind a download link. The link is OpenSubtitles' own CDN and needs no key. */
    suspend fun fetch(link: String): Reply<ByteArray> {
        limiter.acquire()
        val response = http.sendLogged(PROVIDER, HttpRequest("GET", link, mapOf("User-Agent" to userAgent)))
            ?: return Reply.Failed(0)
        if (response.code in 200..299) return Reply.Ok(response.body)
        ProviderLog.status(PROVIDER, "GET", link, response)
        return Reply.Failed(response.code)
    }

    private suspend fun <T> call(
        method: String,
        url: String,
        token: String? = null,
        body: String? = null,
        quotaCall: Boolean = false,
        parse: (JSONObject) -> T?,
    ): Reply<T> {
        limiter.acquire()
        val headers = buildMap {
            put("Api-Key", apiKey)
            put("User-Agent", userAgent)
            put("Accept", "application/json")
            if (body != null) put("Content-Type", "application/json")
            if (token != null) put("Authorization", "Bearer $token")
        }
        val response = http.sendLogged(PROVIDER, HttpRequest(method, url, headers, body))
            ?: return Reply.Failed(0)
        val json = runCatching { JSONObject(response.text) }.getOrNull()
        val parsed = if (response.code in 200..299) json?.let(parse) else null
        if (parsed == null) ProviderLog.status(PROVIDER, method, url, response)
        return when {
            parsed != null -> Reply.Ok(parsed)
            response.code in 200..299 -> Reply.Failed(response.code)
            response.code == 401 -> Reply.Unauthorized
            response.code == 403 -> Reply.Forbidden
            // 406 is the documented "download limit reached"; a 429 that carries the quota fields
            // is the same thing said with the rate limit's status.
            quotaCall && (response.code == 406 || (response.code == 429 && json != null && json.looksLikeQuota())) ->
                Reply.Quota(json?.let(::resetAt))
            response.code == 429 -> Reply.Throttled(retryAfter(response))
            else -> Reply.Failed(response.code)
        }
    }

    private fun JSONObject.looksLikeQuota(): Boolean =
        has("remaining") || has("reset_time_utc") || optString("message").contains("allowed", ignoreCase = true)

    private fun retryAfter(response: HttpResponse): Long =
        response.header("Retry-After")?.trim()?.toLongOrNull()?.times(1000)?.coerceIn(200, 10_000) ?: 1_000

    private fun resetAt(json: JSONObject): Long? =
        json.optString("reset_time_utc").takeIf { it.isNotBlank() }
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private fun url(host: String, path: String): String {
        val clean = host.removePrefix("https://").removePrefix("http://").trimEnd('/')
        return "https://$clean/api/v1/$path"
    }

    companion object {
        const val DEFAULT_HOST = "api.opensubtitles.com"
        private const val PROVIDER = "OpenSubtitles"
    }
}
