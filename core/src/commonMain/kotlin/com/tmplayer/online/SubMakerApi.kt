package com.tmplayer.online

import com.tmplayer.platform.Logger
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

/**
 * The viewer's own SubMaker install (a Stremio subtitles addon, submaker.elfhosted.com or one they
 * host): its address, a manifest URL with a secret token in its path, is what the viewer pastes in
 * Settings. Only the base (the address without `manifest.json`) is kept.
 *
 * The base is a secret. It is never put in a log line, a report, an exception message or any text
 * a screen shows; [SubMakerSecret] takes it out of the log bundle and the crash reports if it still
 * got in. This class logs a status code or an exception class name and nothing else.
 */
object SubMakerUrl {
    /** The longest address taken. */
    const val MAX = 2_000

    /**
     * The base of what the viewer pasted: an `https://` or `stremio://` manifest address, or the
     * base itself, with or without `manifest.json` and a closing slash. Null for anything else: not
     * an address, not https (a loopback address may be http, for tests), carrying a user name, or
     * with a control character in it. The query and the fragment are dropped.
     */
    fun normalize(input: String?): String? {
        var text = input?.trim()?.trim('"', '\'', '<', '>')?.trim().orEmpty()
        if (text.isEmpty() || text.length > MAX || text.any { it.isISOControl() || it.isWhitespace() }) return null
        if (text.startsWith("stremio://", ignoreCase = true)) text = "https://" + text.substring("stremio://".length)
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        if (uri.rawUserInfo != null) return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (!(scheme == "https" || (scheme == "http" && isLoopback(host)))) return null
        var path = uri.rawPath.orEmpty()
        if (path.endsWith("/manifest.json", ignoreCase = true)) path = path.dropLast("/manifest.json".length)
        path = path.trimEnd('/')
        val port = if (uri.port in 1..65_535) ":${uri.port}" else ""
        return "$scheme://${host.lowercase(Locale.ROOT)}$port$path"
    }

    fun isLoopback(host: String): Boolean {
        val h = host.trim('[', ']').lowercase(Locale.ROOT)
        return h == "localhost" || h == "127.0.0.1" || h == "::1"
    }

    /** The manifest address of [base], for the check made when the viewer saves it. */
    fun manifest(base: String): String = "$base/manifest.json"

    /**
     * Whether [url], found in an addon's answer, may be fetched: only from the same host as [base]
     * (an answer cannot send the viewer's device to another server), https, or http where [base]
     * itself is loopback http.
     */
    fun fetchable(url: String, base: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return false
        if (uri.rawUserInfo != null) return false
        val baseHost = runCatching { URI(base).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: return false
        if (!host.trim('[', ']').equals(baseHost.trim('[', ']'), ignoreCase = true)) return false
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            "https" -> true
            "http" -> isLoopback(host) && base.startsWith("http://")
            else -> false
        }
    }
}

/**
 * Keeps the viewer's SubMaker address out of text that leaves the device. The address is
 * registered when it is set; whatever an address of this kind looks like (a `stremio://` link, or
 * an address ending in the addon's own paths) is taken out too, so a copy of it made anywhere is
 * covered.
 */
object SubMakerSecret {
    @Volatile
    private var known: List<String> = emptyList()

    /**
     * Remembers [base], the token in its path, each path segment, and the percent-encoded and
     * decoded forms of those as secret (the same forms as `hideAddon` in the web's logs.ts), or
     * forgets them all when it is blank.
     */
    fun register(base: String) {
        if (base.isBlank()) {
            known = emptyList()
            return
        }
        val found = LinkedHashSet<String>()
        found += base
        val path = base.substringAfter("://").substringAfter('/', "").trim('/')
        if (path.length >= MIN_TOKEN) found += path
        for (segment in path.split('/')) {
            if (segment.length < MIN_SEGMENT) continue
            found += segment
            decoded(segment)?.let { found += it }
        }
        for (form in found.toList()) encoded(form)?.let { found += it }
        known = found.filter { it == base || it.length >= MIN_SEGMENT }.sortedByDescending { it.length }.take(MAX_KNOWN)
    }

    private fun decoded(text: String): String? =
        runCatching { java.net.URLDecoder.decode(text.replace("+", "%2B"), "UTF-8") }.getOrNull()?.takeIf { it != text }

    private fun encoded(text: String): String? =
        runCatching { java.net.URLEncoder.encode(text, "UTF-8").replace("+", "%20") }.getOrNull()?.takeIf { it != text }

    private const val MIN_TOKEN = 8
    private const val MIN_SEGMENT = 6
    private const val MAX_KNOWN = 24
    private const val MARK = "[addon]"

    /** Any address; kept linear: a candidate is cut at whitespace and quotes, then judged by its words. */
    private val ADDRESS = Regex("""(?:stremio|https?)://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
    private val ADDON_PATHS = Regex("/(?:manifest\\.json|subtitles?/|translate|sub-toolbox|configure)", RegexOption.IGNORE_CASE)

    fun scrub(text: String): String {
        var out = text
        for (secret in known) if (secret.isNotEmpty() && out.contains(secret)) out = out.replace(secret, MARK)
        if (!out.contains("://")) return out
        return ADDRESS.replace(out) { match ->
            val value = match.value
            if (value.startsWith("stremio://", ignoreCase = true) || ADDON_PATHS.containsMatchIn(value)) MARK else value
        }
    }
}

/**
 * The calls to the viewer's SubMaker, by the Stremio addon protocol (the subtitles resource):
 * `{base}/subtitles/{type}/{id}.json` lists subtitles, and each entry's own address is fetched
 * for the file. A "Make <Language>" entry's address answers at once with a placeholder, and the
 * same address, fetched again, gives the translation so far ([SubMakerTranslator]).
 *
 * Every field of an answer is read as optional and an entry without what it needs is dropped. The
 * only thing logged is the status code or the exception's class: no address, ever.
 */
class SubMakerApi(
    private val http: HttpTransport,
    private val limiter: RateLimiter,
    private val userAgent: String,
) {
    /** One entry of the addon's list. [translateTo] is the language name of a "Make <Language>" entry. */
    data class Entry(
        val id: String,
        val lang: String,
        val label: String,
        val url: String,
        val translateTo: String? = null,
    )

    /** Why a call gave nothing, sorted into what the screens tell apart. */
    sealed interface Failure {
        /** 429: the wait it asked for. */
        data class Busy(val retryAfterMs: Long) : Failure

        /** 401, 403 or 404: the address is not one the addon takes. */
        data object Rejected : Failure

        /** No answer, or a server error. */
        data object Offline : Failure

        /** An answer that is not what the protocol says. */
        data object Unreadable : Failure
    }

    sealed interface Answer<out T> {
        data class Ok<T>(val value: T) : Answer<T>
        data class Failed(val failure: Failure) : Answer<Nothing>
    }

    /** The entries for a title, [type] being movie, series or anime and [id] the IMDb or Kitsu id. */
    suspend fun list(base: String, type: String, id: String): Answer<List<Entry>> {
        if (type !in TYPES || !ID.matches(id)) return Answer.Failed(Failure.Unreadable)
        return when (val answer = get("$base/subtitles/$type/$id.json", MAX_LIST, "list")) {
            is Answer.Failed -> answer
            is Answer.Ok -> parseList(answer.value.decodeToString(), base)?.let { Answer.Ok(it) } ?: Answer.Failed(Failure.Unreadable)
        }
    }

    /** Whether [base] is an addon that serves subtitles: its manifest, once, when the viewer saves the address. */
    suspend fun check(base: String): Answer<Boolean> =
        when (val answer = get(SubMakerUrl.manifest(base), MAX_LIST, "manifest")) {
            is Answer.Failed -> answer
            is Answer.Ok -> Answer.Ok(parseManifest(answer.value.decodeToString()))
        }

    /** The bytes at an entry's own [url] (a subtitle, or a translation so far), at most [maxBytes]. */
    suspend fun fetch(url: String, maxBytes: Int = SubtitleText.MAX_BYTES): Answer<ByteArray> = get(url, maxBytes, "file")

    private suspend fun get(url: String, maxBytes: Int, what: String): Answer<ByteArray> {
        limiter.acquire()
        val response = try {
            http.send(HttpRequest("GET", url, mapOf("User-Agent" to userAgent, "Accept" to "application/json, text/plain, */*")))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The class only: some connection errors carry the address in their message.
            Logger.w(TAG, "SubMaker $what failed: ${e.javaClass.simpleName}")
            return Answer.Failed(Failure.Offline)
        }
        val code = response.code
        if (code in 200..299) {
            if (response.body.size > maxBytes) {
                Logger.w(TAG, "SubMaker $what failed: ${response.body.size} bytes, over $maxBytes")
                return Answer.Failed(Failure.Unreadable)
            }
            return Answer.Ok(response.body)
        }
        Logger.w(TAG, "SubMaker $what failed: HTTP $code")
        return Answer.Failed(
            when (code) {
                429 -> Failure.Busy(MsoneApi.retryAfter(response.header("Retry-After")))
                401, 403, 404 -> Failure.Rejected
                else -> Failure.Offline
            },
        )
    }

    companion object {
        private const val TAG = "TMPlayer"
        private val TYPES = setOf("movie", "series", "anime")

        /** `tt1234567`, `tt1234567:1:2`, `kitsu:42` or `kitsu:42:3`: the ids the addon takes. */
        private val ID = Regex("""(?:tt\d{5,10}|kitsu:\d{1,9})(?::\d{1,4}){0,2}""")

        /** The most entries read from an answer, and the longest text kept from any field. */
        const val MAX_ENTRIES = 300
        private const val MAX_TEXT = 300
        private const val MAX_URL = 4_000
        private const val MAX_LIST = 2 * 1024 * 1024

        /** The entry that is a web page, not a subtitle. */
        private const val TOOLBOX_ID = "sub_toolbox"

        private val CONTROL = Regex("""[\u0000-\u001F\u007F]+""")

        /** The addon's manifest, true when it offers the subtitles resource. */
        fun parseManifest(text: String): Boolean {
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return false
            val resources = json.optJSONArray("resources") ?: return false
            return (0 until minOf(resources.length(), 50)).any { index ->
                when (val item = resources.opt(index)) {
                    is String -> item == "subtitles"
                    is JSONObject -> item.opt("name") == "subtitles"
                    else -> false
                }
            }
        }

        /** The entries of a list answer, or null when it is not one. */
        fun parseList(text: String, base: String): List<Entry>? {
            if (text.length > MAX_LIST) return null
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val array: JSONArray = json.optJSONArray("subtitles") ?: return null
            val seen = HashSet<String>()
            val out = ArrayList<Entry>()
            for (index in 0 until minOf(array.length(), MAX_ENTRIES)) {
                val entry = entry(array.optJSONObject(index), base) ?: continue
                if (seen.add(entry.id + "|" + entry.url)) out += entry
            }
            return out
        }

        private fun entry(o: JSONObject?, base: String): Entry? {
            o ?: return null
            val id = text(o.opt("id")) ?: return null
            val url = (o.opt("url") as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_URL && it.none(Char::isISOControl) } ?: return null
            if (id == TOOLBOX_ID || url.contains("/sub-toolbox", ignoreCase = true)) return null
            if (!SubMakerUrl.fetchable(url, base)) return null
            val lang = text(o.opt("lang")).orEmpty()
            val label = text(o.opt("label")).orEmpty()
            val make = if (lang.startsWith("Make ", ignoreCase = true)) lang.substring(5).trim().take(60).takeIf { it.isNotEmpty() } else null
            if (lang.isEmpty() && label.isEmpty()) return null
            return Entry(id, lang, label, url, make)
        }

        private fun text(value: Any?): String? = when (value) {
            is String -> value
            is Number -> value.toString()
            else -> null
        }?.replace(CONTROL, " ")?.trim()?.take(MAX_TEXT)?.takeIf { it.isNotEmpty() }

        // ---- the translation answer ----------------------------------------------------------

        /** The text a placeholder answer starts with. */
        const val PLACEHOLDER = "TRANSLATION IN PROGRESS"

        private val TIMING = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""")

        /** How many cues a SubRip text holds. */
        fun cueCount(srt: String): Int = TIMING.findAll(srt).count()

        /**
         * Whether [srt] is the placeholder: its first cue says the translation is in progress. A
         * text whose first cue is anything else is a translation, however short.
         */
        fun isPlaceholder(srt: String): Boolean {
            val first = TIMING.find(srt) ?: return srt.contains(PLACEHOLDER, ignoreCase = true)
            val after = srt.substring(first.range.last + 1)
            val firstCue = after.substringBefore("-->").let { chunk ->
                // The first cue's text runs to the blank line that ends it.
                chunk.split(Regex("""\r?\n\s*\r?\n""")).firstOrNull().orEmpty()
            }
            return firstCue.contains(PLACEHOLDER, ignoreCase = true)
        }

        /** The end of the last cue in milliseconds, or 0 when there is none. */
        fun lastCueEndMs(srt: String): Long {
            var end = 0L
            for (m in TIMING.findAll(srt)) {
                val v = m.groupValues
                val ms = ((v[5].toLong() * 60 + v[6].toLong()) * 60 + v[7].toLong()) * 1000 + v[8].padEnd(3, '0').take(3).toLong()
                if (ms > end) end = ms
            }
            return end
        }
    }
}
