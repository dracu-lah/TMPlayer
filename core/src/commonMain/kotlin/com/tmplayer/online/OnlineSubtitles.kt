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
    /** The build's key for TMPlayer's subtitle gateway; blank and MSone is not offered at all. */
    msoneKey: String = "",
    /** Which subtitle was picked for which video (HX25); null where nothing is remembered (tests, a build without it). */
    val picks: SubtitlePicks? = null,
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
    private val msone = MsoneApi(http, RateLimiter(500, now, sleep), userAgent, msoneKey)
    private val submaker = SubMakerApi(http, RateLimiter(500, now, sleep), userAgent)

    /** The SubMaker translations running now, one per language and video; see [SubMakerJobs]. */
    val translations = SubMakerJobs(SubMakerTranslator(submaker, sleep), now = now)

    init {
        // The viewer's address is a secret: from the first moment it is kept out of logs and reports.
        SubMakerSecret.register(store.now.submakerBase)
    }

    /** What the screens keep of one list answer, so reopening the picker does not ask again within [SUBMAKER_LIST_MS]. */
    private class KeptList(val key: String, val at: Long, val hits: List<SubtitleHit>)

    @Volatile
    private var submakerKept: KeptList? = null

    /** Whether this build can reach MSone at all: it has the gateway's key. */
    val msoneInBuild: Boolean = msoneKey.isNotBlank()

    /** Whether the screens offer MSone: a build with the key, and not hidden on this device. */
    val msoneShown: Boolean get() = msoneInBuild && store.now.msoneShown
    private val msoneLock = Mutex()

    /** When the last MSone check failed, so a gateway that is down is asked once a minute, not on every open. */
    @Volatile
    private var msoneFailedAt = 0L
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

    /** Settings' "Find subtitles for me" (U5): off by default, and a viewer's own choice to turn on. */
    fun setSuggest(value: Boolean) = store.update { it.copy(suggest = value) }

    /**
     * Keeps [key] as the viewer's SubDL key. The word `msone` is not a key: it hides MSone's search
     * on this device, or shows it again, and the answer is whether MSone is now shown; null for an
     * ordinary key.
     */
    fun setSubdlKey(key: String): Boolean? {
        if (key.trim().equals(MSONE_CODE, ignoreCase = true)) {
            val shown = !store.now.msoneShown
            store.update { it.copy(msoneShown = shown) }
            return shown
        }
        store.update { it.copy(subdlKey = key.trim(), subdlRefused = false) }
        return null
    }

    // ---- SubMaker settings ------------------------------------------------------------------

    /** Whether the viewer has set an address for their own SubMaker. */
    val submakerOn: Boolean get() = store.now.submakerBase.isNotBlank()

    /** What saving the viewer's SubMaker address came to. */
    enum class SubMakerSet { Saved, Cleared, Invalid }

    /**
     * Keeps what the viewer pasted as their SubMaker address: a manifest address (`https://` or
     * `stremio://`), stored as its base. Blank removes it. An address that is not one is
     * [SubMakerSet.Invalid] and changes nothing. A changed address ends the translations running
     * and forgets the list kept for the old one.
     */
    fun setSubMakerUrl(input: String): SubMakerSet {
        if (input.isBlank()) {
            applySubMaker("")
            return SubMakerSet.Cleared
        }
        val base = SubMakerUrl.normalize(input) ?: return SubMakerSet.Invalid
        applySubMaker(base)
        return SubMakerSet.Saved
    }

    private fun applySubMaker(base: String) {
        if (base == store.now.submakerBase) return
        SubMakerSecret.register(base)
        translations.cancelAll()
        submakerKept = null
        store.update { it.copy(submakerBase = base) }
    }

    /** Asks the addon's manifest whether the saved address is one that serves subtitles: null when it is, else why not. */
    suspend fun checkSubMaker(): SubtitleNotice? {
        val base = store.now.submakerBase.ifBlank { return SubtitleNotice.SubMakerOff }
        return when (val answer = submaker.check(base)) {
            is SubMakerApi.Answer.Ok -> if (answer.value) null else SubtitleNotice.SubMakerRejected
            is SubMakerApi.Answer.Failed -> submakerNotice(answer.failure)
        }
    }

    /** Whether the viewer already agreed to spend their AI quota on a translation into [language]. */
    fun submakerAsked(language: String): Boolean = language.trim().lowercase(Locale.ROOT) in store.now.submakerAsked

    fun markSubMakerAsked(language: String) {
        val name = language.trim().lowercase(Locale.ROOT).take(60).takeIf { it.isNotEmpty() } ?: return
        store.update { it.copy(submakerAsked = it.submakerAsked + name) }
    }

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
            SubtitleProvider.Msone -> downloadMsone(hit, target)
            SubtitleProvider.SubMaker -> downloadSubMaker(hit)
        }
    }

    /** What opening a video finds of its remembered subtitle ([restorePick]). */
    sealed interface Restored {
        /** Nothing is remembered for this video. */
        data object None : Restored

        /** The viewer turned subtitles off for this video. */
        data object Off : Restored

        /** The remembered subtitle, ready to lay over the video; [fromDevice] when no download was needed. */
        data class Ready(val file: java.io.File, val label: String, val fromDevice: Boolean) : Restored

        /** Remembered but not to be had now: [notice] says why when there is a sentence for it (null: not asked, offline mode). */
        data class Missing(val hit: SubtitleHit, val notice: SubtitleNotice?) : Restored
    }

    /** Remembers [hit], downloaded as [file], for the video under [keys]; the file itself is kept apart from the cache. */
    fun rememberPick(keys: List<String>, hit: SubtitleHit, file: java.io.File?) {
        runCatching { picks?.remember(keys, hit, file) }
            .onFailure { com.tmplayer.platform.Logger.w("OnlineSubtitles", "pick not kept: ${it.message}") }
    }

    /** Remembers that subtitles are off for the video under [keys]. */
    fun rememberOff(keys: List<String>) {
        runCatching { picks?.rememberOff(keys) }
    }

    /** The viewer chose another subtitle (an embedded track, a file of their own): whatever was remembered for the video no longer stands. */
    fun forgetPick(keys: List<String>) {
        runCatching { picks?.forget(keys) }
    }

    /** The viewer chose a track again: an "Off" kept for the video no longer stands. */
    fun clearOff(keys: List<String>) {
        runCatching { picks?.clearOff(keys) }
    }

    /**
     * The subtitle remembered for the video, in the order that spends least: the cache's file
     * (the same download costs nothing), else the pick's own copy, else, when [allowDownload],
     * the download again once (the quota and key rules are the ordinary ones, and a blocked
     * download is [Restored.Missing] with its sentence rather than an attempt). Never throws.
     */
    suspend fun restorePick(keys: List<String>, target: SubtitleTarget? = null, allowDownload: Boolean = true): Restored {
        val pick = runCatching { picks?.recall(keys) }.getOrNull() ?: return Restored.None
        val hit = pick.hit ?: return Restored.Off
        cache.file(hit.provider, hit.id)?.let { return Restored.Ready(it, label(hit), fromDevice = true) }
        pick.kept?.let { return Restored.Ready(it, label(hit), fromDevice = true) }
        if (!allowDownload) return Restored.Missing(hit, null)
        blockedFor(hit)?.let { return Restored.Missing(hit, it) }
        return when (val done = runCatching { download(hit, target) }.getOrNull()) {
            is DownloadResult.Done -> {
                rememberPick(keys, hit, done.file)
                Restored.Ready(done.file, done.label, fromDevice = false)
            }
            is DownloadResult.Failed -> Restored.Missing(hit, done.notice)
            null -> Restored.Missing(hit, SubtitleNotice.Offline)
        }
    }

    /**
     * Why [hit] cannot be downloaded right now, or null when it can: kept on this device already,
     * from SubDL, or from OpenSubtitles with downloads left (the account's, or the free ones
     * without one) and a key accepted.
     * The lists grey out what this names, and say why above them.
     */
    fun blockedFor(hit: SubtitleHit, a: OnlineAccount = store.now): SubtitleNotice? {
        if (hit.provider == SubtitleProvider.Msone) {
            return SubtitleNotice.MsoneOff(a.msoneState, a.msone.message)
                .takeIf { a.msoneState != MsoneState.On && cache.file(hit.provider, hit.id) == null }
        }
        if (hit.provider == SubtitleProvider.SubMaker) {
            return SubtitleNotice.SubMakerOff.takeIf { a.submakerBase.isBlank() && cache.file(hit.provider, hit.id) == null }
        }
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
                    is Reply.Ok -> save(hit, SubtitleText.from(body.value, link.fileName.ifBlank { hit.fileName }, language = hit.language))
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
                    is Reply.Ok -> save(hit, SubtitleText.from(body.value, link.fileName.ifBlank { hit.fileName }, language = hit.language))
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
            is Reply.Ok -> save(hit, SubtitleText.from(body.value, hit.fileName, parsed?.season, parsed?.episode, hit.language))
            is Reply.Throttled -> DownloadResult.Failed(SubtitleNotice.Busy)
            else -> DownloadResult.Failed(SubtitleNotice.Offline)
        }
    }

    private fun save(hit: SubtitleHit, text: SubtitleText.Text?, key: String = hit.id): DownloadResult {
        if (text == null) return DownloadResult.Failed(SubtitleNotice.Unreadable)
        // A full disk or a folder gone is a failed download (HX10), not an exception for the picker.
        val file = runCatching { cache.putFile(hit.provider, key, text) }
            .onFailure { com.tmplayer.platform.Logger.w("OnlineSubtitles", "subtitle not saved: ${it.message}") }
            .getOrNull() ?: return DownloadResult.Failed(SubtitleNotice.Unreadable)
        return DownloadResult.Done(file, label(hit), fromCache = false)
    }

    // ---- MSone -----------------------------------------------------------------------------

    /**
     * MSone's state at the gateway, asked at most every [MSONE_CHECK_MS] (or a minute after a
     * [force]d check, the "Check again" button). A check that fails changes nothing: the last
     * answer stays, so `stopped` is never lost to a bad connection, and a device that never had an
     * answer stays [MsoneState.Unknown]. Turning `stopped` deletes the MSone subtitles kept here;
     * `paused` keeps them for watching offline.
     */
    suspend fun msoneStatus(force: Boolean = false): MsoneStatus = msoneLock.withLock {
        val a = store.now
        val t = now()
        val age = t - a.msoneCheckedAt
        val due = a.msoneCheckedAt == 0L || age !in 0 until MSONE_CHECK_MS || (force && age !in 0 until MSONE_FORCE_MS)
        val failedRecently = t - msoneFailedAt in 0 until MSONE_FORCE_MS
        if (!due || failedRecently) return@withLock a.msone
        when (val answer = msone.health()) {
            is MsoneApi.Answer.Ok -> {
                msoneFailedAt = 0
                setMsone(answer.value.state, answer.value.message)
            }
            is MsoneApi.Answer.Failed -> msoneFailedAt = t
        }
        store.now.msone
    }

    /** Keeps what the gateway said, and acts on a stop. */
    private fun setMsone(state: MsoneState, message: String?) {
        store.update { it.copy(msoneState = state, msoneMessage = message.orEmpty(), msoneCheckedAt = now()) }
        if (state == MsoneState.Stopped) {
            runCatching { cache.purgeFiles(SubtitleProvider.Msone) }
                .onFailure { Logger.w(TAG, "MSone subtitles not deleted: ${it.message}") }
        }
    }

    /**
     * MSone's subtitles for [target], searched by its IMDb id when known, else by title, year,
     * season and episode. Only answers while the gateway says MSone is on; otherwise the
     * [SubtitleNotice.MsoneOff] the picker shows where the results would be, with the subtitles
     * already downloaded from a paused MSone still listed.
     */
    suspend fun searchMsone(target: SubtitleTarget): SearchResult {
        val status = msoneStatus()
        val parsed = MediaName.parse(target.fileName, target.caption)
        val query = msoneQuery(target)
        val key = msoneSearchKey(query)
        if (status.state != MsoneState.On) {
            val kept = if (status.state == MsoneState.Paused) {
                cache.search(key).orEmpty().filter { cache.file(it.provider, it.id) != null }
            } else {
                emptyList()
            }
            return SearchResult(kept, SubtitleNotice.MsoneOff(status.state, status.message), fromCache = kept.isNotEmpty())
        }
        if (query.imdb == null && query.tmdb == null && query.title == null) return SearchResult(emptyList(), SubtitleNotice.MsoneTooShort)
        cache.search(key)?.takeIf { it.isNotEmpty() }?.let { return SearchResult(rankMsone(it, parsed), fromCache = true) }
        return when (val answer = msone.search(query)) {
            is MsoneApi.Answer.Ok -> {
                // An empty answer is not kept: MSone's volunteers may finish this one tomorrow.
                if (answer.value.isNotEmpty()) cache.putSearch(key, answer.value)
                SearchResult(rankMsone(answer.value, parsed))
            }
            is MsoneApi.Answer.Failed -> SearchResult(emptyList(), msoneNotice(answer.failure))
        }
    }

    private fun msoneQuery(target: SubtitleTarget): MsoneApi.Query {
        val parsed = MediaName.parse(target.fileName, target.caption)
        return MsoneApi.Query(
            imdb = target.imdb,
            tmdb = target.tmdb,
            title = parsed.title.trim().takeIf { it.length >= MIN_QUERY },
            year = parsed.year,
            season = parsed.season,
            episode = parsed.episode,
        )
    }

    private fun msoneSearchKey(query: MsoneApi.Query): String =
        "msone|${query.imdb ?: query.tmdb?.let { "tmdb$it" } ?: query.title?.lowercase(Locale.ROOT)}|${query.year}|${query.season}|${query.episode}"

    /** The right episode first, then the right year, then the order MSone gave. */
    private fun rankMsone(hits: List<SubtitleHit>, parsed: ParsedName): List<SubtitleHit> =
        hits.sortedWith(
            compareByDescending<SubtitleHit> { parsed.episode != null && it.featureEpisode == parsed.episode && (it.featureSeason == null || it.featureSeason == parsed.season) }
                .thenByDescending { parsed.year != null && it.featureYear == parsed.year },
        )

    private fun msoneNotice(failure: MsoneApi.Failure): SubtitleNotice = when (failure) {
        is MsoneApi.Failure.State -> {
            setMsone(failure.state, failure.message)
            SubtitleNotice.MsoneOff(failure.state, failure.message)
        }
        MsoneApi.Failure.BadQuery -> SubtitleNotice.MsoneTooShort
        // Not kept as the state: the next /health says on again, and a newer build has a new key.
        MsoneApi.Failure.Refused -> SubtitleNotice.MsoneOff(MsoneState.Off)
        MsoneApi.Failure.Gone -> SubtitleNotice.MsoneGone
        is MsoneApi.Failure.Busy -> SubtitleNotice.MsoneBusy
        MsoneApi.Failure.Offline -> SubtitleNotice.Offline
    }

    /**
     * One MSone subtitle, a single file per video or episode from MSone's own link. The download
     * is what MSone counts, so it is made once and then kept on the device.
     */
    private suspend fun downloadMsone(hit: SubtitleHit, target: SubtitleTarget?): DownloadResult {
        val a = store.now
        if (a.msoneState != MsoneState.On) return DownloadResult.Failed(SubtitleNotice.MsoneOff(a.msoneState, a.msone.message))
        return when (val answer = msone.fetch(hit, SubtitleText.MAX_BYTES)) {
            is MsoneApi.Answer.Ok -> {
                // MSone's links end in a query (`?wpdmdl=`), so the name the gateway gave comes first.
                val name = hit.fileName.ifBlank { hit.link.substringBefore('?').substringAfterLast('/') }
                val text = SubtitleText.from(answer.value, name.ifBlank { "msone.srt" }, language = hit.language)
                save(hit, text, hit.id)
            }
            is MsoneApi.Answer.Failed -> {
                // A link MSone stopped honouring: forget the kept search, so the next one asks afresh.
                if (answer.failure == MsoneApi.Failure.Gone && target != null) cache.removeSearch(msoneSearchKey(msoneQuery(target)))
                DownloadResult.Failed(msoneNotice(answer.failure))
            }
        }
    }

    // ---- SubMaker --------------------------------------------------------------------------

    /**
     * What the viewer's own SubMaker lists for [target], found by its IMDb id: a movie as
     * `movie/tt...`, an episode as `series/tt...:season:episode`. Ready subtitles come first, in
     * the languages wanted, then the "Make <Language>" entries ([SubtitleHit.translateTo]), which
     * are AI translations to run on the viewer's own key ([translate]). The answer is kept for a
     * few minutes only and in memory: its addresses hold the viewer's secret.
     */
    suspend fun searchSubMaker(target: SubtitleTarget): SearchResult {
        val base = store.now.submakerBase.ifBlank { return SearchResult(emptyList(), SubtitleNotice.SubMakerOff) }
        val imdb = target.imdb?.trim()?.lowercase(Locale.ROOT)?.takeIf { SUBMAKER_IMDB.matches(it) }
            ?: return SearchResult(emptyList(), SubtitleNotice.SubMakerNoId)
        val parsed = MediaName.parse(target.fileName, target.caption)
        val series = parsed.episode != null
        val type = if (series) "series" else "movie"
        val id = if (series) "$imdb:${parsed.season ?: 1}:${parsed.episode}" else imdb
        val key = "$type/$id"
        submakerKept?.takeIf { it.key == key && now() - it.at in 0 until SUBMAKER_LIST_MS }?.let { return SearchResult(it.hits, fromCache = true) }
        return when (val answer = submaker.list(base, type, id)) {
            is SubMakerApi.Answer.Failed -> SearchResult(emptyList(), submakerNotice(answer.failure))
            is SubMakerApi.Answer.Ok -> {
                val languages = target.languages.mapNotNull(::iso1).distinct().ifEmpty { listOf("en") }
                val hits = rankSubMaker(answer.value.map(::submakerHit), languages)
                if (hits.isNotEmpty()) submakerKept = KeptList(key, now(), hits)
                SearchResult(hits, SubtitleNotice.SubMakerNoResults.takeIf { hits.isEmpty() })
            }
        }
    }

    private fun submakerHit(entry: SubMakerApi.Entry): SubtitleHit {
        val make = entry.translateTo
        return SubtitleHit(
            provider = SubtitleProvider.SubMaker,
            // The id keeps the file apart in the cache: the addon's own ids are not promised to be unique.
            id = entry.id.take(100) + "|" + sha(entry.url).take(12),
            language = if (make == null) entry.lang else "",
            release = entry.label.ifBlank { entry.lang },
            fileName = entry.label,
            link = entry.url,
            translateTo = make.orEmpty(),
        )
    }

    /** Ready subtitles in the order of the languages wanted, then the translations to run, wanted languages first. */
    private fun rankSubMaker(hits: List<SubtitleHit>, languages: List<String>): List<SubtitleHit> {
        val order = languages.withIndex().associate { (i, code) -> code to i }
        val names = languages.associateWith { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT) }
        fun rank(hit: SubtitleHit): Int {
            if (hit.translateTo.isEmpty()) return order[iso1(hit.language)] ?: order.size
            val wanted = names.entries.firstOrNull { it.value == hit.translateTo.lowercase(Locale.ROOT) }?.key
            return order.size + 1 + (wanted?.let { order[it] } ?: order.size)
        }
        return hits.sortedBy(::rank)
    }

    private suspend fun downloadSubMaker(hit: SubtitleHit): DownloadResult {
        // A translation is a job, not a download: [translate] runs it.
        if (hit.translateTo.isNotEmpty() || hit.link.isBlank()) return DownloadResult.Failed(SubtitleNotice.SubMakerOff)
        if (store.now.submakerBase.isBlank()) return DownloadResult.Failed(SubtitleNotice.SubMakerOff)
        return when (val answer = submaker.fetch(hit.link)) {
            is SubMakerApi.Answer.Ok -> save(hit, SubtitleText.from(answer.value, hit.fileName.ifBlank { "subtitle.srt" }, language = hit.language))
            is SubMakerApi.Answer.Failed -> DownloadResult.Failed(submakerNotice(answer.failure))
        }
    }

    private fun submakerNotice(failure: SubMakerApi.Failure): SubtitleNotice = when (failure) {
        is SubMakerApi.Failure.Busy -> SubtitleNotice.SubMakerBusy
        SubMakerApi.Failure.Rejected -> SubtitleNotice.SubMakerRejected
        SubMakerApi.Failure.Offline -> SubtitleNotice.Offline
        SubMakerApi.Failure.Unreadable -> SubtitleNotice.Unreadable
    }

    /** The job key for a translation of [hit] on the video [video] (its hash or name). */
    fun translationKey(video: String, hit: SubtitleHit): String = SubMakerJobs.keyOf(video, hit.translateTo)

    /** The video part of every job key, for [SubMakerJobs.cancelVideo]. */
    fun translationPrefix(video: String): String = SubMakerJobs.keyOf(video, "")

    /**
     * Runs the translation behind a "Make <Language>" [hit] as a background job (the player and the
     * picker carry on) unless one runs for it already (false). A translation finished before for
     * this very entry is laid over the video at once. [onTrack] is told of each file to load over
     * the video, in place of the last one, [final] at the end; it may run on a background thread.
     */
    fun translate(video: String, hit: SubtitleHit, endMs: Long?, onTrack: (File, Boolean) -> Unit): Boolean {
        if (hit.translateTo.isEmpty() || hit.link.isBlank() || store.now.submakerBase.isBlank()) return false
        val key = translationKey(video, hit)
        cache.file(hit.provider, hit.id)?.let {
            translations.finished(key, SubMakerApi.cueCount(runCatching { it.readText() }.getOrDefault("")))
            onTrack(it, true)
            return true
        }
        return translations.start(
            key = key,
            url = hit.link,
            endMs = endMs,
            write = { srt, final ->
                runCatching {
                    // The half translation lives under its own name, so a stopped job never leaves it as the finished one.
                    val file = cache.putFile(hit.provider, if (final) hit.id else hit.id + PART, SubtitleText.Text("srt", srt))
                    if (final) cache.removeFile(hit.provider, hit.id + PART)
                    file
                }.onFailure { Logger.w(TAG, "translation not saved: ${it.javaClass.simpleName}") }.getOrNull()
            },
            onTrack = onTrack,
        )
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

    private fun sha(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

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

        /** What typed as the SubDL key shows or hides MSone's search instead. */
        private const val MSONE_CODE = "msone"

        /** How often MSone's state is asked at most, and how soon a manual "Check again" may ask again. */
        const val MSONE_CHECK_MS = 6 * 60 * 60 * 1000L
        const val MSONE_FORCE_MS = 60 * 1000L

        /** How long a SubMaker list is kept in memory, and the name of a half translation in the cache. */
        private const val SUBMAKER_LIST_MS = 5 * 60 * 1000L
        private const val PART = "~part"
        private val SUBMAKER_IMDB = Regex("""tt\d{5,10}""")

        /** How long a refused key keeps the feature off before one more try. */
        const val REFUSED_RETRY_MS = SubtitleCache.DAY_MS

        /** The instance each app sets up at start, or null in a build or a test that has none. */
        @Volatile
        var current: OnlineSubtitles? = null

        /** Whether the feature exists in this build, for About and Settings. */
        val available: Boolean get() = current?.inBuild == true

        /** The track name a downloaded subtitle gets: its language and where it came from. */
        fun label(hit: SubtitleHit): String =
            L.onlineTrackLabel(hit.translateTo.ifEmpty { languageName(hit.language) }, hit.provider.label)

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
