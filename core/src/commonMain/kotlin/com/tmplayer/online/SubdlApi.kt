package com.tmplayer.online

import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/**
 * SubDL, the fallback when OpenSubtitles has nothing or is unavailable: a free key of the viewer's
 * own (2,000 requests a day), searched by name, season and episode. Its files are zip archives;
 * [SubtitleText.fromArchive] takes the subtitle out.
 *
 * See https://subdl.com/api-doc.
 */
class SubdlApi(
    private val http: HttpTransport,
    private val limiter: RateLimiter,
    private val userAgent: String,
) {
    suspend fun search(
        key: String,
        name: String,
        season: Int?,
        episode: Int?,
        languages: List<String>,
    ): Reply<List<SubtitleHit>> {
        val params = sortedMapOf(
            "api_key" to key,
            "film_name" to name,
            "languages" to languages.map { it.uppercase(Locale.ROOT) }.distinct().joinToString(","),
            "subs_per_page" to "30",
            "type" to if (season != null || episode != null) "tv" else "movie",
        )
        season?.let { params["season_number"] = it.toString() }
        episode?.let { params["episode_number"] = it.toString() }
        val query = params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        limiter.acquire()
        val response = http.sendLogged(
            PROVIDER,
            HttpRequest("GET", "$API?$query", mapOf("User-Agent" to userAgent, "Accept" to "application/json")),
        ) ?: return Reply.Failed(0)
        // Status only for SubDL: its address carries the viewer's key, and ProviderLog drops it.
        if (response.code !in 200..299) ProviderLog.failed(PROVIDER, "GET", API, "HTTP ${response.code}")
        if (response.code == 401 || response.code == 403) return Reply.Unauthorized
        if (response.code == 429) return Reply.Throttled(1_000)
        val json = runCatching { JSONObject(response.text) }.getOrNull() ?: return Reply.Failed(response.code)
        if (!json.optBoolean("status")) {
            val error = json.optString("error")
            // "No subtitles found" and friends are an empty answer; a refused key is not.
            return if (error.contains("key", ignoreCase = true)) Reply.Unauthorized else Reply.Ok(emptyList())
        }
        val subtitles = json.optJSONArray("subtitles") ?: return Reply.Ok(emptyList())
        return Reply.Ok(
            (0 until subtitles.length()).mapNotNull { index ->
                val item = subtitles.optJSONObject(index) ?: return@mapNotNull null
                val path = item.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SubtitleHit(
                    provider = SubtitleProvider.Subdl,
                    id = path,
                    language = languageCode(item.optString("lang"), item.optString("language")),
                    release = item.optString("release_name").ifBlank { item.optString("name") },
                    fileName = item.optString("name"),
                    hearingImpaired = item.optBoolean("hi"),
                )
            },
        )
    }

    /** The zip behind a hit's download path. */
    suspend fun fetch(path: String): Reply<ByteArray> {
        limiter.acquire()
        val url = if (path.startsWith("http")) path else DOWNLOAD + "/" + path.trimStart('/')
        val response = http.sendLogged(PROVIDER, HttpRequest("GET", url, mapOf("User-Agent" to userAgent)))
            ?: return Reply.Failed(0)
        if (response.code in 200..299) return Reply.Ok(response.body)
        ProviderLog.failed(PROVIDER, "GET", url, "HTTP ${response.code}")
        return Reply.Failed(response.code)
    }

    /** SubDL answers "english" in `lang` and "EN" in `language`; the two letter code is what we keep. */
    private fun languageCode(lang: String, language: String): String =
        language.takeIf { it.length == 2 }?.lowercase(Locale.ROOT)
            ?: Locale.getAvailableLocales().firstOrNull { it.getDisplayLanguage(Locale.ENGLISH).equals(lang, ignoreCase = true) }?.language
            ?: lang

    companion object {
        const val API = "https://api.subdl.com/api/v1/subtitles"
        const val DOWNLOAD = "https://dl.subdl.com"
        private const val PROVIDER = "SubDL"
    }
}
