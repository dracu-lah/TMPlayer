package com.tmplayer.online

import com.tmplayer.data.MediaName
import com.tmplayer.data.ParsedName
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
 * - **One app key, an account if the viewer wants one.** The key is TMPlayer's, injected at build
 *   time (`OPENSUBTITLES_API_KEY`); a build without one has [inBuild] false and shows nothing of
 *   this. OpenSubtitles' terms forbid asking viewers for keys of their own.
 * - **Downloads work without signing in.** A download with only the app key (no Authorization)
 *   is answered like a signed in one, against an allowance of its own: 100 a day when this was
 *   tried, whether counted per app key or per address OpenSubtitles does not say. What it reports
 *   ([OnlineAccount.anonRemaining], [OnlineAccount.anonResetAt]) is respected before asking, and
 *   once it is spent ([SubtitleNotice.FreeQuotaUsed]) the lists say when it renews and that a sign
 *   in brings the viewer's own quota. A viewer signed in under Settings, Online subtitles downloads
 *   with their token against their own quota ([OnlineAccount.remaining], [OnlineAccount.resetAt]),
 *   exactly as before.
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
        a.anonQuotaUsed(now()) -> OnlineStatus.FreeQuotaUsed(a.anonResetAt.takeIf { it > 0 })
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
            // OpenSubtitles answers a title under three letters with a 400, "Query is too short".
            if (name.length >= MIN_QUERY) {
                add(
                    "os|name:${name.lowercase(Locale.ROOT)}|${parsed.season}|${parsed.episode}$tail" to
                        OpenSubtitlesApi.Query(name = name, season = parsed.season, episode = parsed.episode, languages = languages, includeMachine = machine),
                )
            }
        }

        var hits = emptyList<SubtitleHit>()
        var fromCache = false
        var problem: SubtitleNotice? = null
        // A search that matches nothing of this video can still answer, with other people's
        // films: a hash nobody has seen brings back whatever is recent in the languages asked for,
        // and a name with a year in it brings back every release of that year. Only an answer
        // with something in it for this video ends the search; anything else is kept, lets the
        // next query have its turn, and is judged on its words with the rest below.
        fun settles(found: List<SubtitleHit>): Boolean = relevant(found, parsed).isNotEmpty()
        if (keyRefused(a)) {
            problem = SubtitleNotice.Unavailable
        } else {
            for ((key, query) in queries) {
                val cached = cache.search(key)
                if (cached != null) {
                    if (cached.isEmpty()) continue
                    hits = (hits + cached).distinctBy { it.id }
                    fromCache = true
                    if (settles(cached)) break
                    continue
                }
                when (val reply = withBackoff { api.search(query, a.host) }) {
                    is Reply.Ok -> {
                        cache.putSearch(key, reply.value)
                        hits = (hits + reply.value).distinctBy { it.id }
                        if (settles(reply.value)) break
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
                        // The service answered and turned this one query down: the next one may
                        // still find something, and the connection is not what failed.
                        if (reply.refusedRequest) continue
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
            (hits.isEmpty() || problem != null || a.quotaUsed(now()) || a.anonQuotaUsed(now()))
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
        val sorted = relevant(hits, parsed).sortedWith(
            compareByDescending<SubtitleHit> { it.hashMatch }
                .thenByDescending { relevance(it, parsed) }
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

    /**
     * The line above OpenSubtitles results: a spent quota, the viewer's or the free one, or a sign
     * in that ran out. An expired sign in does not block anything, downloads carry on without an
     * account meanwhile; [downloadBlock] is what does.
     */
    private fun accountNotice(a: OnlineAccount): SubtitleNotice? =
        downloadBlock(a) ?: SubtitleNotice.SignInExpired.takeIf { !a.signedIn && a.expired }

    /** What stands between the viewer and a download from OpenSubtitles, before asking. */
    private fun downloadBlock(a: OnlineAccount): SubtitleNotice? = when {
        a.quotaUsed(now()) -> SubtitleNotice.QuotaUsed(a.resetAt.takeIf { it > 0 })
        a.anonQuotaUsed(now()) -> SubtitleNotice.FreeQuotaUsed(a.anonResetAt.takeIf { it > 0 })
        else -> null
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
     * from SubDL, or from OpenSubtitles with downloads left (the account's, or the free ones
     * without one) and a key accepted.
     * The lists grey out what this names, and say why above them.
     */
    fun blockedFor(hit: SubtitleHit, a: OnlineAccount = store.now): SubtitleNotice? {
        if (hit.provider != SubtitleProvider.OpenSubtitles || cache.file(hit.provider, hit.id) != null) return null
        if (keyRefused(a)) return SubtitleNotice.Unavailable
        return downloadBlock(a)
    }

    private suspend fun downloadOpenSubtitles(hit: SubtitleHit): DownloadResult {
        val a = store.now
        if (keyRefused(a)) return DownloadResult.Failed(SubtitleNotice.Unavailable)
        if (!a.signedIn) return downloadAnonymously(hit, a)
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

    /**
     * The same download with the app key alone, against the allowance OpenSubtitles gives without
     * an account. Its figures are kept apart from the account's, and a spent allowance says when it
     * renews and that signing in brings the viewer's own.
     */
    private suspend fun downloadAnonymously(hit: SubtitleHit, a: OnlineAccount): DownloadResult {
        if (a.anonQuotaUsed(now())) return DownloadResult.Failed(SubtitleNotice.FreeQuotaUsed(a.anonResetAt.takeIf { it > 0 }))
        return when (val reply = withBackoff { api.download(hit.id, null, OpenSubtitlesApi.DEFAULT_HOST) }) {
            is Reply.Ok -> {
                val link = reply.value
                store.update {
                    val remaining = if (link.remaining >= 0) link.remaining else it.anonRemaining
                    it.copy(
                        anonRemaining = remaining,
                        // A spent allowance with no time given is tried again a day later rather than never.
                        anonResetAt = link.resetAt ?: if (remaining == 0) now() + SubtitleCache.DAY_MS else it.anonResetAt,
                    )
                }
                when (val body = api.fetch(link.url)) {
                    is Reply.Ok -> save(hit, SubtitleText.from(body.value, link.fileName.ifBlank { hit.fileName }))
                    else -> DownloadResult.Failed(SubtitleNotice.Offline)
                }
            }
            is Reply.Quota -> {
                val reset = reply.resetAt ?: (now() + SubtitleCache.DAY_MS)
                store.update { it.copy(anonRemaining = 0, anonResetAt = reset) }
                DownloadResult.Failed(SubtitleNotice.FreeQuotaUsed(reset))
            }
            // OpenSubtitles asking for an account after all: signing in is the way through.
            Reply.Unauthorized -> DownloadResult.Failed(SubtitleNotice.SignInToDownload)
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

    /**
     * [hits] without the ones that are plainly another video: an OpenSubtitles release that
     * shares not one word with the title. A hash match always stays, whatever it is called, and
     * so does SubDL, which is asked for a film by name and answers with releases of that film.
     *
     * A title with no Latin letters in it ("ഹാർബർ നോട്ട്സ്") cannot be compared with release
     * names, which are written in Latin letters, so nothing is dropped for one of those.
     */
    private fun relevant(hits: List<SubtitleHit>, parsed: ParsedName): List<SubtitleHit> {
        val title = titleWords(parsed.title)
        if (title.none { word -> word.any { it in 'a'..'z' } }) return hits
        return hits.filter { hit ->
            if (hit.hashMatch || hit.provider != SubtitleProvider.OpenSubtitles) return@filter true
            val theirs = releaseTitle(hit)
            if (hit.featureTitle.isBlank()) return@filter title.any { it in theirs }
            // OpenSubtitles' own filing: the show or film has to be this one, word for word, or
            // one name has to hold the other whole ("Thor & Loki Blood Brothers" stays, ranked
            // under "Loki"; "Entourage" goes, whatever its release group is called).
            title.any { it in theirs } && (theirs.containsAll(title) || title.containsAll(theirs))
        }
    }

    /**
     * The words of the title a hit is for. OpenSubtitles' own filing ([SubtitleHit.featureTitle])
     * when it gave one; otherwise the release name read the way a file name is ([MediaName.parse]),
     * what comes before its episode code or year, without a release group. "Entourage.S1E04.DVDRip-LOKi"
     * is Entourage, and "Season 1 whole (AC3.DVDRip.XviD-LOKi)" names no show at all.
     */
    private fun releaseTitle(hit: SubtitleHit): Set<String> {
        if (hit.featureTitle.isNotBlank()) return titleWords(hit.featureTitle)
        return buildSet {
            listOf(hit.release, hit.fileName).filter { it.isNotBlank() }.forEach {
                addAll(titleWords(MediaName.parse(it.replace(SUBTITLE_EXTENSION, "").replace(RELEASE_GROUP, ""), maxYear = 2099).title))
            }
        }
    }

    /**
     * How well [hit] fits the video, for the order of the list after hash matches. First the
     * release's own title ([releaseTitle]) against the video's: the share of the video's title
     * words it carries, then how little else it carries, so "Thor & Loki Blood Brothers" sits
     * under "Loki" when the video is Loki, and a title that is exactly the video's gets a bonus on
     * top. Then the right episode and the right year; a wrong one counts against it, so
     * "Show S01E03" sits under "Show S01E02" when the video is the second episode. The exact title
     * with the right episode outranks everything but a hash match.
     */
    private fun relevance(hit: SubtitleHit, parsed: ParsedName): Int {
        val title = titleWords(parsed.title)
        val release = hit.release.ifBlank { hit.fileName }
        val words = releaseTitle(hit)
        var score = 0
        if (title.isNotEmpty()) {
            val shared = title.count { it in words }
            score += 100 * shared / title.size
            score += 50 * shared / (title + words).size
            if (words == title) score += 40
        }
        val read = MediaName.parse(release, maxYear = 2099)
        // OpenSubtitles' own season, episode and year first, the release name's where it gave none.
        val theirs = read.copy(
            season = hit.featureSeason ?: read.season,
            episode = hit.featureEpisode ?: read.episode,
            year = hit.featureYear ?: read.year,
        )
        if (parsed.episode != null && theirs.episode != null) {
            val sameSeason = parsed.season == null || theirs.season == null || parsed.season == theirs.season
            // A wrong episode weighs more than the exact title, so it sits under a release that names no episode.
            score += if (sameSeason && parsed.episode == theirs.episode) 50 else -100
        }
        if (parsed.year != null && theirs.year != null) {
            score += if (parsed.year == theirs.year) 20 else -20
        }
        return score
    }

    private fun wordsOf(text: String): Set<String> =
        text.lowercase(Locale.ROOT).split(NOT_WORD).filter { it.isNotEmpty() }.toSet()

    /** The title's words that say something: "the" and "of" are in every other release name. */
    private fun titleWords(title: String): Set<String> {
        val all = wordsOf(title)
        return (all - FILLER).ifEmpty { all }
    }

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

        private val NOT_WORD = Regex("""[^\p{L}\p{N}]+""")

        /** A release group: the word after the last dash, at the end or before a closing bracket. */
        private val RELEASE_GROUP = Regex("""-[A-Za-z0-9]+(?=\s*(?:$|[)\]]))""")

        /** A subtitle file's own extension, off before the release group is looked for. */
        private val SUBTITLE_EXTENSION = Regex("""\.(srt|ass|ssa|vtt|sub|txt)$""", RegexOption.IGNORE_CASE)

        /** Words too common in release names to say that a release is this video. */
        private val FILLER = setOf("the", "a", "an", "of", "and", "in", "on", "to", "with")

        /** The shortest title OpenSubtitles accepts as a query. */
        internal const val MIN_QUERY = 3

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

    /** Not signed in, and the downloads allowed without an account are spent until [resetAt]. */
    data class FreeQuotaUsed(val resetAt: Long?) : OnlineStatus
}
