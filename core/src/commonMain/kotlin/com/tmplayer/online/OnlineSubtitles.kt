package com.tmplayer.online

import com.tmplayer.data.MediaName
import com.tmplayer.data.valueOrNull
import com.tmplayer.i18n.L
import com.tmplayer.i18n.Translator
import com.tmplayer.i18n.TrackCodes
import com.tmplayer.platform.Logger
import dev.g000sha256.tdl.TdlClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Subtitles from the internet, for a video that has none in the language wanted: the one place
 * phone, TV and desktop all go through, so the rules below hold the same everywhere.
 *
 * - **One app key, the viewer's own account.** The key is TMPlayer's, injected at build time
 *   (`OPENSUBTITLES_API_KEY`); a build without one has [inBuild] false and shows nothing of this.
 *   OpenSubtitles' terms forbid asking viewers for keys of their own. Downloads count against the
 *   viewer's free account, signed into under Settings, Online subtitles, and the quota they report
 *   ([OnlineAccount.remaining], [OnlineAccount.resetAt]) is respected before asking.
 * - **Search** by the file hash first, then by the parsed title (with season and episode). Machine
 *   and AI translations are left out unless the viewer has switched them on.
 * - **Never in the way.** A refused key turns the provider off with a sentence saying so
 *   ([SubtitleNotice.Unavailable]) until an update brings a new key; a 429 backs off and tries
 *   once more; whatever is cached keeps working; an optional SubDL key of the viewer's own is the
 *   fallback.
 * - **Polite.** Five requests a second at most and one sign in a second ([RateLimiter]); one
 *   search per video at a time; everything kept in [cache] for up to six months.
 */
class OnlineSubtitles(
    private val apiKey: String,
    val store: OnlineSubtitlesStore,
    val cache: SubtitleCache,
    appVersion: String,
    http: HttpTransport = UrlConnectionTransport,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val userAgent = "TMPlayer v$appVersion"
    private val api = OpenSubtitlesApi(
        apiKey = apiKey,
        userAgent = userAgent,
        http = http,
        limiter = RateLimiter(200, now, sleep),
        loginLimiter = RateLimiter(1_000, now, sleep),
    )
    private val subdl = SubdlApi(http, RateLimiter(200, now, sleep), userAgent)
    private val locks = HashMap<String, Mutex>()

    /** A short, one way fingerprint of the key, which is all [OnlineAccount.rejectedKey] keeps. */
    private val keyPrint: String = MessageDigest.getInstance("SHA-256")
        .digest(apiKey.toByteArray()).take(6).joinToString("") { "%02x".format(it) }

    /** Whether this build carries an app key at all. Without one the feature is not drawn. */
    val inBuild: Boolean get() = apiKey.isNotBlank()

    val account: StateFlow<OnlineAccount> get() = store.account

    /** True while OpenSubtitles is refusing this build's key. Retried once a day in case it was a blip. */
    fun keyRefused(a: OnlineAccount = store.now): Boolean =
        a.rejectedKey == keyPrint && now() - a.rejectedAt < REFUSED_RETRY_MS

    // ---- account ----------------------------------------------------------------------------

    sealed interface SignIn {
        data object Ok : SignIn
        data object WrongPassword : SignIn
        data class Failed(val notice: SubtitleNotice) : SignIn
    }

    suspend fun signIn(username: String, password: String): SignIn {
        if (keyRefused()) return SignIn.Failed(SubtitleNotice.Unavailable)
        return when (val reply = api.login(username.trim(), password)) {
            is Reply.Ok -> {
                val session = reply.value
                val quota = (api.userInfo(session.token, session.baseUrl) as? Reply.Ok)?.value
                store.update {
                    it.copy(
                        username = username.trim(),
                        token = session.token,
                        host = session.baseUrl,
                        allowed = quota?.allowed ?: session.allowed,
                        remaining = quota?.remaining ?: session.allowed,
                        resetAt = 0,
                        expired = false,
                    )
                }
                SignIn.Ok
            }
            Reply.Unauthorized -> SignIn.WrongPassword
            Reply.Forbidden -> {
                refuseKey()
                SignIn.Failed(SubtitleNotice.Unavailable)
            }
            is Reply.Throttled -> SignIn.Failed(SubtitleNotice.Busy)
            is Reply.Quota, is Reply.Failed -> SignIn.Failed(SubtitleNotice.Offline)
        }
    }

    /** Ends the session here and, as a courtesy, at OpenSubtitles. Never fails from the viewer's side. */
    suspend fun signOut() {
        val a = store.now
        if (a.signedIn && !keyRefused(a)) runCatching { api.logout(a.token, a.host) }
        store.update {
            it.copy(username = "", token = "", host = OpenSubtitlesApi.DEFAULT_HOST, allowed = 0, remaining = -1, resetAt = 0, expired = false)
        }
    }

    fun setIncludeMachine(value: Boolean) = store.update { it.copy(includeMachine = value) }

    fun setSubdlKey(key: String) = store.update { it.copy(subdlKey = key.trim(), subdlRefused = false) }

    /** What Settings says under "Online subtitles". */
    fun status(a: OnlineAccount = store.now): OnlineStatus = when {
        !inBuild -> OnlineStatus.NotInBuild
        keyRefused(a) -> OnlineStatus.Unavailable
        a.signedIn && a.quotaUsed(now()) -> OnlineStatus.QuotaUsed(a.username, a.resetAt.takeIf { it > 0 })
        a.signedIn -> OnlineStatus.SignedIn(a.username, remainingNow(a), a.allowed)
        a.expired -> OnlineStatus.Expired(a.username)
        else -> OnlineStatus.SignedOut
    }

    /** The last remaining figure, or the full allowance once the reset time has passed. */
    private fun remainingNow(a: OnlineAccount): Int =
        if (a.remaining == 0 && a.resetAt > 0 && now() >= a.resetAt) a.allowed else a.remaining

    // ---- search ----------------------------------------------------------------------------

    /**
     * The subtitles on offer for [target], best first: hash matches, then the wanted languages in
     * order, then the most downloaded. One search per video at a time; a second caller waits and
     * then reads the first one's answer from the cache.
     */
    suspend fun search(target: SubtitleTarget): SearchResult {
        val lock = synchronized(locks) { locks.getOrPut(target.hash ?: target.fileName) { Mutex() } }
        return lock.withLock { searchNow(target) }
    }

    private suspend fun searchNow(target: SubtitleTarget): SearchResult {
        val a = store.now
        val parsed = MediaName.parse(target.fileName, target.caption)
        val languages = target.languages.mapNotNull(::iso1).distinct().ifEmpty { listOf("en") }
        val machine = a.includeMachine
        val tail = "|${languages.joinToString(",")}|${if (machine) "m" else "-"}"
        val name = (if (parsed.isSeries) parsed.title else parsed.query).trim()

        val queries = buildList {
            target.hash?.let { add("os|hash:$it$tail" to OpenSubtitlesApi.Query(hash = it, languages = languages, includeMachine = machine)) }
            if (name.isNotBlank()) {
                add(
                    "os|name:${name.lowercase(Locale.ROOT)}|${parsed.season}|${parsed.episode}$tail" to
                        OpenSubtitlesApi.Query(name = name, season = parsed.season, episode = parsed.episode, languages = languages, includeMachine = machine),
                )
            }
        }

        var hits = emptyList<SubtitleHit>()
        var fromCache = false
        var problem: SubtitleNotice? = null
        if (keyRefused(a)) {
            problem = SubtitleNotice.Unavailable
        } else {
            for ((key, query) in queries) {
                val cached = cache.search(key)
                if (cached != null) {
                    if (cached.isEmpty()) continue
                    hits = cached
                    fromCache = true
                    break
                }
                when (val reply = withBackoff { api.search(query, a.host) }) {
                    is Reply.Ok -> {
                        cache.putSearch(key, reply.value)
                        if (reply.value.isNotEmpty()) {
                            hits = reply.value
                            break
                        }
                    }
                    Reply.Forbidden, Reply.Unauthorized -> {
                        refuseKey()
                        problem = SubtitleNotice.Unavailable
                        break
                    }
                    is Reply.Throttled, is Reply.Quota -> {
                        problem = SubtitleNotice.Busy
                        break
                    }
                    is Reply.Failed -> {
                        problem = SubtitleNotice.Offline
                        break
                    }
                }
            }
        }
        hits = hits.filter { machine || !it.machine }

        // SubDL, when the viewer has a key and OpenSubtitles came back empty handed or cannot
        // hand over a file today.
        var subdlProblem: SubtitleNotice? = null
        val fallback = a.subdlKey.isNotBlank() && !a.subdlRefused && name.isNotBlank() &&
            (hits.isEmpty() || problem != null || a.quotaUsed(now()))
        if (fallback) {
            val key = "subdl|${name.lowercase(Locale.ROOT)}|${parsed.season}|${parsed.episode}$tail"
            val cached = cache.search(key)
            if (cached != null) {
                hits = hits + cached
                fromCache = fromCache || hits.isNotEmpty()
            } else {
                when (val reply = withBackoff { subdl.search(a.subdlKey, name, parsed.season, parsed.episode, languages) }) {
                    is Reply.Ok -> {
                        cache.putSearch(key, reply.value)
                        hits = hits + reply.value
                    }
                    Reply.Unauthorized, Reply.Forbidden -> {
                        store.update { it.copy(subdlRefused = true) }
                        subdlProblem = SubtitleNotice.SubdlKeyRefused
                    }
                    else -> Unit
                }
            }
        }

        val order = languages.withIndex().associate { (i, code) -> code to i }
        val sorted = hits.sortedWith(
            compareByDescending<SubtitleHit> { it.hashMatch }
                .thenBy { order[iso1(it.language)] ?: order.size }
                .thenBy { it.provider.ordinal }
                .thenByDescending { it.downloads },
        )
        val anyOpenSubtitles = sorted.any { it.provider == SubtitleProvider.OpenSubtitles }
        val notice = when {
            sorted.isEmpty() -> problem ?: subdlProblem
            problem != null -> problem
            anyOpenSubtitles -> accountNotice(a) ?: subdlProblem
            else -> subdlProblem
        }
        return SearchResult(sorted, notice, fromCache)
    }

    /** What stands between the viewer and a download from OpenSubtitles, before asking. */
    private fun accountNotice(a: OnlineAccount): SubtitleNotice? = when {
        a.signedIn && a.quotaUsed(now()) -> SubtitleNotice.QuotaUsed(a.resetAt.takeIf { it > 0 })
        a.signedIn -> null
        a.expired -> SubtitleNotice.SignInExpired
        else -> SubtitleNotice.SignInToDownload
    }

    // ---- download --------------------------------------------------------------------------

    /**
     * [hit] as a UTF-8 file the player can load. A file fetched before comes from the cache and
     * spends nothing; otherwise this is the call that uses one of the day's downloads.
     */
    suspend fun download(hit: SubtitleHit, target: SubtitleTarget? = null): DownloadResult {
        cache.file(hit.provider, hit.id)?.let { return DownloadResult.Done(it, label(hit), fromCache = true) }
        return when (hit.provider) {
            SubtitleProvider.OpenSubtitles -> downloadOpenSubtitles(hit)
            SubtitleProvider.Subdl -> downloadSubdl(hit, target)
        }
    }

    /**
     * Why [hit] cannot be downloaded right now, or null when it can: kept on this device already,
     * from SubDL, or from OpenSubtitles with a live sign in, downloads left and a key accepted.
     * The lists grey out what this names, and say why above them.
     */
    fun blockedFor(hit: SubtitleHit, a: OnlineAccount = store.now): SubtitleNotice? {
        if (hit.provider != SubtitleProvider.OpenSubtitles || cache.file(hit.provider, hit.id) != null) return null
        if (keyRefused(a)) return SubtitleNotice.Unavailable
        return accountNotice(a)
    }

    private suspend fun downloadOpenSubtitles(hit: SubtitleHit): DownloadResult {
        val a = store.now
        if (keyRefused(a)) return DownloadResult.Failed(SubtitleNotice.Unavailable)
        if (!a.signedIn) {
            return DownloadResult.Failed(if (a.expired) SubtitleNotice.SignInExpired else SubtitleNotice.SignInToDownload)
        }
        if (a.quotaUsed(now())) return DownloadResult.Failed(SubtitleNotice.QuotaUsed(a.resetAt.takeIf { it > 0 }))
        return when (val reply = withBackoff { api.download(hit.id, a.token, a.host) }) {
            is Reply.Ok -> {
                val link = reply.value
                store.update {
                    it.copy(
                        remaining = if (link.remaining >= 0) link.remaining else it.remaining,
                        resetAt = link.resetAt ?: it.resetAt,
                    )
                }
                when (val body = api.fetch(link.url)) {
                    is Reply.Ok -> save(hit, SubtitleText.from(body.value, link.fileName.ifBlank { hit.fileName }))
                    else -> DownloadResult.Failed(SubtitleNotice.Offline)
                }
            }
            is Reply.Quota -> {
                val reset = reply.resetAt ?: (now() + SubtitleCache.DAY_MS)
                store.update { it.copy(remaining = 0, resetAt = reset) }
                DownloadResult.Failed(SubtitleNotice.QuotaUsed(reset))
            }
            Reply.Unauthorized -> {
                store.update { it.copy(token = "", expired = true) }
                DownloadResult.Failed(SubtitleNotice.SignInExpired)
            }
            Reply.Forbidden -> {
                refuseKey()
                DownloadResult.Failed(SubtitleNotice.Unavailable)
            }
            is Reply.Throttled -> DownloadResult.Failed(SubtitleNotice.Busy)
            is Reply.Failed -> DownloadResult.Failed(SubtitleNotice.Offline)
        }
    }

    private suspend fun downloadSubdl(hit: SubtitleHit, target: SubtitleTarget?): DownloadResult {
        val parsed = target?.let { MediaName.parse(it.fileName, it.caption) }
        return when (val body = withBackoff { subdl.fetch(hit.id) }) {
            is Reply.Ok -> save(hit, SubtitleText.from(body.value, hit.fileName, parsed?.season, parsed?.episode))
            is Reply.Throttled -> DownloadResult.Failed(SubtitleNotice.Busy)
            else -> DownloadResult.Failed(SubtitleNotice.Offline)
        }
    }

    private fun save(hit: SubtitleHit, text: SubtitleText.Text?): DownloadResult {
        if (text == null) return DownloadResult.Failed(SubtitleNotice.Unreadable)
        return DownloadResult.Done(cache.putFile(hit.provider, hit.id, text), label(hit), fromCache = false)
    }

    // ---- shared ----------------------------------------------------------------------------

    /** The cache's size on the disk, for the Settings row. */
    fun cacheBytes(): Long = cache.bytes()

    /** Settings' "Clear online subtitles": every search and file kept. The account stays. */
    fun purge() = cache.purge()

    /** One retry after the wait a 429 asks for. */
    private suspend fun <T> withBackoff(call: suspend () -> Reply<T>): Reply<T> {
        val first = call()
        if (first !is Reply.Throttled) return first
        sleep(first.retryAfterMs)
        return call()
    }

    private fun refuseKey() {
        Logger.w(TAG, "OpenSubtitles refused the app key; online subtitles are off until it changes")
        store.update { it.copy(rejectedKey = keyPrint, rejectedAt = now()) }
    }

    companion object {
        private const val TAG = "OnlineSubtitles"

        /** How long a refused key keeps the feature off before one more try. */
        const val REFUSED_RETRY_MS = SubtitleCache.DAY_MS

        /** The instance each app sets up at start, or null in a build or a test that has none. */
        @Volatile
        var current: OnlineSubtitles? = null

        /** Whether the feature exists in this build, for About and Settings. */
        val available: Boolean get() = current?.inBuild == true

        /** The track name a downloaded subtitle gets: its language and where it came from. */
        fun label(hit: SubtitleHit): String =
            L.onlineTrackLabel(languageName(hit.language), hit.provider.label)

        fun languageName(code: String): String =
            Translator.messages.formatter.trackLanguage(code) ?: code.ifBlank { L.onlineUnknownLanguage }

        /**
         * The languages to search in, best first: the one the viewer last chose subtitles in, then
         * the UI language, then English.
         */
        fun languagesFor(preferred: String?): List<String> =
            listOfNotNull(preferred, Translator.messages.tag, "en").mapNotNull(::iso1).distinct()

        /** A track or UI language as OpenSubtitles' two letter code; Portuguese keeps its two kinds. */
        internal fun iso1(code: String?): String? {
            val raw = code?.trim()?.replace('_', '-')?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
            if (raw == "pt-br" || raw == "pob") return "pt-br"
            val base = raw.substringBefore('-')
            if (base in setOf("und", "mul", "zxx", "qaa", "xa")) return null
            return TrackCodes.toIso1(base) ?: base.takeIf { it.length == 2 }
        }

        /**
         * The OpenSubtitles hash of a Telegram file, read from the parts already on the disk.
         * Null unless both ends are there: asking TDLib for them would move the download the
         * player is streaming from, so a file not yet that far along is searched by name.
         */
        suspend fun hashFromTdlib(td: TdlClient, fileId: Int, size: Long): String? {
            if (!MovieHash.hashable(size)) return null
            val chunk = MovieHash.CHUNK.toLong()
            val tail = MovieHash.tailOffset(size)
            val headReady = td.getFileDownloadedPrefixSize(fileId, 0).valueOrNull?.size ?: 0
            val tailReady = td.getFileDownloadedPrefixSize(fileId, tail).valueOrNull?.size ?: 0
            if (headReady < chunk || tailReady < chunk) return null
            val head = td.readFilePart(fileId, 0, chunk).valueOrNull?.data ?: return null
            val end = td.readFilePart(fileId, tail, chunk).valueOrNull?.data ?: return null
            if (head.size < chunk || end.size < chunk) return null
            return MovieHash.of(size, head, end)
        }

        /** The hash of a local file, the same as [MovieHash.of]. */
        fun hashOf(file: File): String? = MovieHash.of(file)
    }
}

/** What Settings shows for the feature, from top to bottom of how much of it works. */
sealed interface OnlineStatus {
    data object NotInBuild : OnlineStatus
    data object Unavailable : OnlineStatus
    data object SignedOut : OnlineStatus
    data class Expired(val username: String) : OnlineStatus
    data class SignedIn(val username: String, val remaining: Int, val allowed: Int) : OnlineStatus
    data class QuotaUsed(val username: String, val resetAt: Long?) : OnlineStatus
}
