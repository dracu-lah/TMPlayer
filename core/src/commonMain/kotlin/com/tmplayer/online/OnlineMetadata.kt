package com.tmplayer.online

import com.tmplayer.data.MediaItem
import com.tmplayer.i18n.Translator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.URI
import java.security.MessageDigest

/**
 * Posters and overviews for videos, looked up by name: the one place phone, TV and desktop all
 * go through, so the rules below hold the same everywhere.
 *
 * - **Off until asked.** Nothing is looked up until the viewer turns it on in Settings (or from
 *   the detail panel): a lookup sends a video's cleaned name to a provider.
 * - **Providers.** TMDB for films and shows, with the build's key (`TMDB_API_KEY`) or the
 *   viewer's own; TVmaze for shows and their episodes, with no key; AniList for anime, with no
 *   key. A show from TMDB takes its episode from TMDB too, in the UI language; one from TVmaze
 *   from TVmaze. Anime asks AniList first.
 * - **Safe matching.** Only a title with the same words, or most of them and the same year, is
 *   taken ([MetaMatch]); otherwise the file keeps its own name. `{tmdb-123}` in a name forces it.
 * - **Never in the way.** A refused TMDB key turns TMDB off with a sentence saying so (shows still
 *   come from TVmaze and AniList); a 429 pauses that provider for as long as it asks; offline,
 *   whatever is cached keeps showing. No feature of the app waits on any of this.
 * - **Polite.** One request at a time per provider, spaced to its published limit; one lookup per
 *   title at a time; everything kept in [cache] for up to six months, with a purge in Settings.
 * - **Language.** Overviews in the UI language where the provider has one, English otherwise.
 */
class OnlineMetadata(
    private val buildKey: String,
    val store: MetadataStore,
    val cache: MetadataCache,
    appVersion: String,
    private val http: HttpTransport = UrlConnectionTransport,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val language: () -> String = { Translator.messages.tag },
    /** The viewer's region, for the age rating: the device's country, else the US. */
    private val region: () -> String = { MetaRules.region() },
) {
    private val userAgent = "TMPlayer/$appVersion (+https://tmplayer.org)"
    private val tmdb = TmdbApi(http, RateLimiter(TmdbApi.GAP_MS, now, sleep), userAgent)
    private val tvmaze = TvMazeApi(http, RateLimiter(TvMazeApi.GAP_MS, now, sleep), userAgent)
    private val anilist = AniListApi(http, RateLimiter(AniListApi.GAP_MS, now, sleep), userAgent)
    private val imageLimiter = RateLimiter(IMAGE_GAP_MS, now, sleep)

    private val locks = HashMap<String, Mutex>()
    private val memory = object : LinkedHashMap<String, MetaResult>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MetaResult>?) = size > MEMORY_ENTRIES
    }
    private val pausedUntil = HashMap<MetaProvider, Long>()

    val settings: StateFlow<MetaSettings> get() = store.settings

    /** Whether this build carries a TMDB key of its own. */
    val buildHasKey: Boolean get() = buildKey.isNotBlank()

    fun setEnabled(value: Boolean) = store.update { it.copy(enabled = value) }

    /** The viewer's own TMDB key; blank goes back to the build's. A new key gets a fresh chance. */
    fun setOwnKey(key: String) = store.update { it.copy(ownKey = key.trim(), refusedKey = "", refusedAt = 0) }

    /** What Settings says about TMDB, whether or not lookups are on. */
    fun tmdbState(s: MetaSettings = store.now): TmdbState {
        val own = s.ownKey.isNotBlank()
        val key = activeKey(s)
        return when {
            key.isBlank() -> TmdbState.NoKey
            refused(s, key, own) -> if (own) TmdbState.OwnKeyRefused else TmdbState.AppKeyRefused
            own -> TmdbState.OwnKey
            else -> TmdbState.AppKey
        }
    }

    /** True when TMDB can be asked right now. */
    private fun tmdbUsable(s: MetaSettings): Boolean =
        tmdbState(s).let { it == TmdbState.AppKey || it == TmdbState.OwnKey }

    private fun activeKey(s: MetaSettings): String = s.ownKey.ifBlank { buildKey }

    /** The build's key is retried once a day after a refusal, in case it was a blip; the viewer's own until they change it. */
    private fun refused(s: MetaSettings, key: String, own: Boolean): Boolean =
        s.refusedKey.isNotEmpty() && s.refusedKey == print(key) && (own || now() - s.refusedAt < REFUSED_RETRY_MS)

    private fun print(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(6).joinToString("") { "%02x".format(it) }

    /** The language tag TMDB is asked in: the UI's, with the pseudo-locales and plain English as en-US. */
    fun tmdbLanguage(tag: String = language()): String = when {
        tag.isBlank() || tag.startsWith("en") -> ENGLISH
        else -> tag
    }

    // ---- lookups ------------------------------------------------------------------------------

    /** What is already known for [query] in memory, for the first frame. Never touches the disk or the network. */
    fun peek(query: MetaQuery): MetaResult? {
        if (!store.now.enabled) return MetaResult.Off
        return synchronized(memory) { memory[query.cacheKey(tmdbLanguage())] }
    }

    /**
     * The poster and overview for [query]: from memory, then the disk cache, then the providers.
     * Two callers asking for the same title wait for one lookup.
     */
    suspend fun lookup(query: MetaQuery): MetaResult {
        if (!store.now.enabled) return MetaResult.Off
        val lang = tmdbLanguage()
        val key = query.cacheKey(lang)
        remembered(key)?.let { return it }
        return lockFor(key).withLock {
            remembered(key)?.let { return@withLock it }
            val (result, keep) = resolve(query, lang)
            if (keep) {
                cache.putAnswer(key, result)
                synchronized(memory) { memory[key] = result }
            }
            result
        }
    }

    private fun remembered(key: String): MetaResult? {
        synchronized(memory) { memory[key] }?.let { return it }
        val stored = cache.answer(key) ?: return null
        synchronized(memory) { memory[key] = stored }
        return stored
    }

    private fun lockFor(key: String): Mutex = synchronized(locks) {
        if (locks.size > MEMORY_ENTRIES) locks.entries.removeAll { !it.value.isLocked }
        locks.getOrPut(key) { Mutex() }
    }

    /** The answer, and whether it may be kept: only what every provider could be asked about is. */
    private suspend fun resolve(query: MetaQuery, lang: String): Pair<MetaResult, Boolean> {
        if (query.wantsEpisode) {
            val show = lookup(query.showOnly())
            if (show !is MetaResult.Found) return show to (show is MetaResult.NoMatch)
            val (episode, complete) = episodeFor(show.info, query, lang)
            return MetaResult.Found(show.info.copy(episode = episode)) to complete
        }
        var blocked = false
        val order = when {
            query.kind == MetaKind.Film && query.anime -> listOf(MetaProvider.Tmdb, MetaProvider.AniList)
            query.kind == MetaKind.Film -> listOf(MetaProvider.Tmdb)
            query.anime -> listOf(MetaProvider.AniList, MetaProvider.Tmdb, MetaProvider.TvMaze)
            else -> listOf(MetaProvider.Tmdb, MetaProvider.TvMaze)
        }
        for (provider in order) {
            when (val outcome = ask(provider, query, lang)) {
                is Outcome.Found -> return MetaResult.Found(outcome.info) to true
                Outcome.Nothing -> Unit
                Outcome.Blocked -> blocked = true
            }
        }
        return if (blocked) MetaResult.Unavailable to false else MetaResult.NoMatch to true
    }

    private sealed interface Outcome {
        data class Found(val info: MetaInfo) : Outcome
        data object Nothing : Outcome

        /** Refused, throttled or offline: no answer, so no "nothing matched" may be kept. */
        data object Blocked : Outcome
    }

    private suspend fun ask(provider: MetaProvider, query: MetaQuery, lang: String): Outcome {
        if (paused(provider)) return Outcome.Blocked
        val s = store.now
        return when (provider) {
            MetaProvider.Tmdb -> if (!tmdbUsable(s)) Outcome.Blocked else tmdbLookup(activeKey(s), query, lang)
            MetaProvider.TvMaze -> tvmazeLookup(query)
            MetaProvider.AniList -> anilistLookup(query)
        }
    }

    private fun paused(provider: MetaProvider): Boolean = synchronized(pausedUntil) {
        (pausedUntil[provider] ?: 0L) > now()
    }

    /** Sorts a failed reply: pauses on 429, turns TMDB off on a refused key. Null when [reply] is Ok. */
    private fun <T> trouble(provider: MetaProvider, reply: MetaReply<T>, key: String? = null): Outcome? = when (reply) {
        is MetaReply.Ok -> null
        is MetaReply.Throttled -> {
            synchronized(pausedUntil) { pausedUntil[provider] = now() + reply.retryAfterMs }
            Outcome.Blocked
        }
        MetaReply.Refused -> {
            if (key != null) store.update { it.copy(refusedKey = print(key), refusedAt = now()) }
            Outcome.Blocked
        }
        // A 404 is an answer: that id or episode does not exist.
        is MetaReply.Failed -> if (reply.code == 404) Outcome.Nothing else Outcome.Blocked
    }

    /** One more try after a second for a dropped connection or a server error, then give up. */
    private suspend fun <T> retrying(call: suspend () -> MetaReply<T>): MetaReply<T> {
        val first = call()
        if (first is MetaReply.Failed && (first.code == 0 || first.code >= 500)) {
            sleep(RETRY_MS)
            return call()
        }
        return first
    }

    private suspend fun tmdbLookup(key: String, query: MetaQuery, lang: String): Outcome {
        val film = query.kind == MetaKind.Film
        val candidate: MetaCandidate = if (query.tmdbId != null) {
            val reply = retrying { if (film) tmdb.movie(key, query.tmdbId.toString(), lang) else tmdb.tv(key, query.tmdbId.toString(), lang) }
            trouble(MetaProvider.Tmdb, reply, key)?.let { return it }
            (reply as MetaReply.Ok).value
        } else {
            suspend fun search(year: Int?): MetaReply<List<MetaCandidate>> = retrying {
                if (film) tmdb.searchMovie(key, query.title, year, lang) else tmdb.searchTv(key, query.title, year, lang)
            }
            val first = search(query.year)
            trouble(MetaProvider.Tmdb, first, key)?.let { return it }
            var found = (first as MetaReply.Ok).value.firstOrNull { MetaMatch.accepts(query.title, query.year, it.names, it.year, show = !film) }
            // A year one off (a festival date, a late release) is filtered out by TMDB's own year,
            // so ask once more without it and let the match allow the difference. So is a show's
            // later season, whose file names carry the season's year rather than the premiere's.
            if (found == null && query.year != null) {
                val again = search(null)
                trouble(MetaProvider.Tmdb, again, key)?.let { return it }
                found = (again as MetaReply.Ok).value.firstOrNull { MetaMatch.accepts(query.title, query.year, it.names, it.year, show = !film) }
            }
            found ?: return Outcome.Nothing
        }
        var overview = candidate.overview
        var overviewLang = lang
        if (overview.isBlank() && lang != ENGLISH) {
            val english = retrying { if (film) tmdb.movie(key, candidate.id, ENGLISH) else tmdb.tv(key, candidate.id, ENGLISH) }
            if (english is MetaReply.Ok) {
                overview = english.value.overview
                overviewLang = ENGLISH
            }
        }
        return Outcome.Found(
            MetaInfo(
                provider = MetaProvider.Tmdb,
                id = candidate.id,
                kind = if (film) MetaKind.Film else MetaKind.Show,
                title = candidate.names.firstOrNull { it.isNotBlank() } ?: query.title,
                year = candidate.year,
                overview = overview,
                posterUrl = candidate.posterUrl,
                backdropUrl = candidate.backdropUrl,
                language = overviewLang,
            ),
        )
    }

    private suspend fun tvmazeLookup(query: MetaQuery): Outcome {
        if (query.kind != MetaKind.Show) return Outcome.Nothing
        val reply = retrying { tvmaze.show(query.title) }
        trouble(MetaProvider.TvMaze, reply)?.let { return it }
        val show = (reply as MetaReply.Ok).value ?: return Outcome.Nothing
        if (!MetaMatch.accepts(query.title, query.year, show.names, show.year, show = true)) return Outcome.Nothing
        return Outcome.Found(
            MetaInfo(
                provider = MetaProvider.TvMaze,
                id = show.id,
                kind = MetaKind.Show,
                title = show.names.first(),
                year = show.year,
                overview = show.overview,
                posterUrl = show.posterUrl,
                language = ENGLISH,
            ),
        )
    }

    private suspend fun anilistLookup(query: MetaQuery): Outcome {
        val reply = retrying { anilist.search(query.title) }
        trouble(MetaProvider.AniList, reply)?.let { return it }
        val wantFilm = query.kind == MetaKind.Film
        val found = (reply as MetaReply.Ok).value
            .filter { (it.format == "MOVIE") == wantFilm }
            .firstOrNull { MetaMatch.accepts(query.title, query.year, it.names, it.year, show = !wantFilm) }
            ?: return Outcome.Nothing
        return Outcome.Found(
            MetaInfo(
                provider = MetaProvider.AniList,
                id = found.id,
                kind = query.kind,
                title = found.names.firstOrNull { it.isNotBlank() } ?: query.title,
                year = found.year,
                overview = found.overview,
                posterUrl = found.posterUrl,
                backdropUrl = found.backdropUrl,
                language = ENGLISH,
            ),
        )
    }

    /** The episode from the provider the show came from, and whether that answer is final. */
    private suspend fun episodeFor(show: MetaInfo, query: MetaQuery, lang: String): Pair<MetaEpisode?, Boolean> {
        val season = query.season ?: 1
        val number = query.episode ?: return null to true
        if (paused(show.provider)) return null to false
        return when (show.provider) {
            MetaProvider.Tmdb -> {
                val s = store.now
                if (!tmdbUsable(s)) return null to false
                val key = activeKey(s)
                val reply = retrying { tmdb.episode(key, show.id, season, number, lang) }
                if (reply is MetaReply.Failed && reply.code == 404) return null to true
                trouble(MetaProvider.Tmdb, reply, key)?.let { return null to false }
                var episode = (reply as MetaReply.Ok).value
                if (episode.overview.isBlank() && lang != ENGLISH) {
                    val english = retrying { tmdb.episode(key, show.id, season, number, ENGLISH) }
                    if (english is MetaReply.Ok) {
                        episode = episode.copy(
                            name = episode.name.ifBlank { english.value.name },
                            overview = english.value.overview,
                        )
                    }
                }
                episode to true
            }
            MetaProvider.TvMaze -> {
                val reply = retrying { tvmaze.episode(show.id, season, number) }
                trouble(MetaProvider.TvMaze, reply)?.let { return null to false }
                (reply as MetaReply.Ok).value to true
            }
            // AniList lists no episodes; the show's own words stand for each one.
            MetaProvider.AniList -> null to true
        }
    }

    // ---- the detail page's extras -------------------------------------------------------------

    private val extrasMemory = object : LinkedHashMap<String, MetaExtras>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MetaExtras>?) = size > EXTRAS_MEMORY_ENTRIES
    }

    private fun extrasKey(info: MetaInfo, lang: String, region: String): String =
        listOf("extras", info.provider.name, info.kind.name, info.id, lang, region).joinToString("|")

    /** The extras for [info] already in memory, for the first frame. Never touches the disk or the network. */
    fun peekExtras(info: MetaInfo): MetaExtras? {
        if (!store.now.enabled) return null
        return synchronized(extrasMemory) { extrasMemory[extrasKey(info, tmdbLanguage(), region())] }
    }

    /**
     * The facts, cast, trailer and recommendations for a title already matched: one call to the
     * provider it came from (TMDB with everything appended, TVmaze with the cast embedded, one
     * AniList query), then kept as long as the match is. Null while off, or when the provider
     * could not be asked; an empty [MetaExtras] when it had nothing.
     */
    suspend fun extras(info: MetaInfo): MetaExtras? {
        if (!store.now.enabled) return null
        val lang = tmdbLanguage()
        val key = extrasKey(info, lang, region())
        rememberedExtras(key)?.let { return it }
        return lockFor(key).withLock {
            rememberedExtras(key)?.let { return@withLock it }
            val found = fetchExtras(info, lang) ?: return@withLock null
            cache.putExtras(key, found)
            synchronized(extrasMemory) { extrasMemory[key] = found }
            found
        }
    }

    private fun rememberedExtras(key: String): MetaExtras? {
        synchronized(extrasMemory) { extrasMemory[key] }?.let { return it }
        val stored = cache.extras(key) ?: return null
        synchronized(extrasMemory) { extrasMemory[key] = stored }
        return stored
    }

    /** The provider's answer, an empty one for a 404, or null when it could not be asked. */
    private suspend fun fetchExtras(info: MetaInfo, lang: String): MetaExtras? {
        if (paused(info.provider)) return null
        val reply: MetaReply<MetaExtras>
        var key: String? = null
        when (info.provider) {
            MetaProvider.Tmdb -> {
                val s = store.now
                if (!tmdbUsable(s)) return null
                key = activeKey(s)
                val k = key
                reply = retrying { tmdb.extras(k, info.kind == MetaKind.Film, info.id, lang, region()) }
            }
            MetaProvider.TvMaze -> reply = retrying { tvmaze.extras(info.id) }
            MetaProvider.AniList -> reply = retrying { anilist.extras(info.id) }
        }
        return when (trouble(info.provider, reply, key)) {
            null -> (reply as MetaReply.Ok).value
            Outcome.Nothing -> MetaExtras()
            else -> null
        }
    }

    // ---- a show's episode lists ----------------------------------------------------------------

    private val seasonMemory = object : LinkedHashMap<String, List<MetaEpisode>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MetaEpisode>>?) = size > EXTRAS_MEMORY_ENTRIES
    }

    private fun seasonKey(info: MetaInfo, season: Int, lang: String): String =
        listOf("season", info.provider.name, info.id, season, lang).joinToString("|")

    /** [season]'s list already in memory, for the first frame. */
    fun peekSeason(info: MetaInfo, season: Int): List<MetaEpisode>? {
        if (!store.now.enabled || info.kind != MetaKind.Show) return null
        return synchronized(seasonMemory) { seasonMemory[seasonKey(info, season, tmdbLanguage())] }
    }

    /**
     * Every episode of [season] of the show [info], with names, pictures and air dates, so the
     * detail page can list the ones the chat lacks and the ones still to air. TMDB asks for the
     * season; TVmaze answers every season at once, which is kept per season. AniList numbers an
     * anime's episodes without seasons and is not asked. Null while off or when nobody could be
     * asked; kept a day (see [MetadataCache.season]).
     */
    suspend fun season(info: MetaInfo, season: Int): List<MetaEpisode>? {
        if (!store.now.enabled || info.kind != MetaKind.Show || info.provider == MetaProvider.AniList) return null
        val lang = tmdbLanguage()
        val key = seasonKey(info, season, lang)
        synchronized(seasonMemory) { seasonMemory[key] }?.let { return it }
        return lockFor(key).withLock {
            synchronized(seasonMemory) { seasonMemory[key] }?.let { return@withLock it }
            cache.season(key)?.let { stored ->
                synchronized(seasonMemory) { seasonMemory[key] = stored }
                return@withLock stored
            }
            if (paused(info.provider)) return@withLock null
            when (info.provider) {
                MetaProvider.Tmdb -> {
                    val s = store.now
                    if (!tmdbUsable(s)) return@withLock null
                    val k = activeKey(s)
                    val reply = retrying { tmdb.season(k, info.id, season, lang) }
                    val found = when (trouble(info.provider, reply, k)) {
                        null -> (reply as MetaReply.Ok).value
                        Outcome.Nothing -> emptyList()
                        else -> return@withLock null
                    }
                    cache.putSeason(key, found)
                    synchronized(seasonMemory) { seasonMemory[key] = found }
                    found
                }
                MetaProvider.TvMaze -> {
                    val reply = retrying { tvmaze.episodes(info.id) }
                    val all = when (trouble(info.provider, reply)) {
                        null -> (reply as MetaReply.Ok).value
                        Outcome.Nothing -> emptyList()
                        else -> return@withLock null
                    }
                    // One answer covers every season: keep each, so the next season is no call.
                    all.groupBy { it.season }.forEach { (number, list) ->
                        val k = seasonKey(info, number, lang)
                        cache.putSeason(k, list)
                        synchronized(seasonMemory) { seasonMemory[k] = list }
                    }
                    val found = all.filter { it.season == season }
                    if (found.isEmpty()) {
                        cache.putSeason(key, found)
                        synchronized(seasonMemory) { seasonMemory[key] = found }
                    }
                    found
                }
                MetaProvider.AniList -> null
            }
        }
    }

    /**
     * What is already known for [query], from memory or the disk, with no lookup: how "More like
     * this" learns which title a video is without asking anyone about it.
     */
    fun cachedMatch(query: MetaQuery): MetaInfo? {
        if (!store.now.enabled) return null
        return (remembered(query.cacheKey(tmdbLanguage())) as? MetaResult.Found)?.info
    }

    /**
     * [extras]' recommendations narrowed to [items] the viewer has, by the matches already made
     * for them (no new lookups). A show is opened at its earliest episode. Run it off the main
     * thread: a title not in memory is read from the disk.
     */
    fun moreLikeThis(self: MetaInfo, extras: MetaExtras, items: Collection<MediaItem>): List<KnownTitle> {
        if (extras.similar.isEmpty() || items.isEmpty() || !store.now.enabled) return emptyList()
        val wanted = extras.similar.mapTo(HashSet()) { it.matchKey }
        val lang = tmdbLanguage()
        val asked = HashMap<String, MetaInfo?>()
        val best = LinkedHashMap<String, Pair<KnownTitle, Int>>()
        for (item in items) {
            val full = MetaQuery.of(item.fileName.ifBlank { item.title }, item.caption) ?: continue
            val show = full.showOnly()
            val cacheKey = show.cacheKey(lang)
            val info = if (cacheKey in asked) asked[cacheKey] else cachedMatch(show).also { asked[cacheKey] = it }
            info ?: continue
            val match = MetaRef.matchKey(info.provider, info.kind, info.id)
            if (match !in wanted) continue
            val order = (full.season ?: 0) * 10_000 + (full.episode ?: 0)
            val held = best[match]
            if (held == null || order < held.second) best[match] = KnownTitle(item, info) to order
        }
        return MetaRules.moreLikeThis(extras.similar, self, best.values.map { it.first })
    }

    // ---- pictures -----------------------------------------------------------------------------

    /**
     * The picture at [url] as a file on this device, fetched once and kept with the answers. Only
     * the three providers' own image hosts are fetched from.
     */
    suspend fun image(url: String): File? {
        cache.image(url)?.let { return it }
        if (!store.now.enabled) return null
        val host = runCatching { URI(url).host }.getOrNull() ?: return null
        if (!url.startsWith("https://") || host !in IMAGE_HOSTS) return null
        return lockFor("image|$url").withLock {
            cache.image(url)?.let { return@withLock it }
            imageLimiter.acquire()
            val response = try {
                http.send(HttpRequest("GET", url, mapOf("User-Agent" to userAgent)))
            } catch (e: java.io.IOException) {
                return@withLock null
            }
            if (response.code !in 200..299 || response.body.isEmpty()) return@withLock null
            cache.putImage(url, response.body)
        }
    }

    // ---- the cache ----------------------------------------------------------------------------

    fun cacheBytes(): Long = cache.bytes()

    /** Every answer and picture, from the disk and from memory. */
    fun purge() {
        cache.purge()
        synchronized(memory) { memory.clear() }
        synchronized(extrasMemory) { extrasMemory.clear() }
        synchronized(seasonMemory) { seasonMemory.clear() }
    }

    companion object {
        /** Set once at start by the app. Null in a context that never set it up, such as a test. */
        @Volatile
        var current: OnlineMetadata? = null

        const val ENGLISH = "en-US"
        const val REFUSED_RETRY_MS = 24L * 60 * 60 * 1000
        const val RETRY_MS = 1_000L
        const val IMAGE_GAP_MS = 50L
        private const val MEMORY_ENTRIES = 600

        /** A few detail pages' worth: each holds a cast list and a recommendation list. */
        private const val EXTRAS_MEMORY_ENTRIES = 24

        /** Where posters may come from. Nothing else is fetched, whatever an answer says. */
        val IMAGE_HOSTS = setOf("image.tmdb.org", "static.tvmaze.com", "s4.anilist.co", "img.anili.st")

        /** Lookups are on, in a build that set the feature up. */
        val enabled: Boolean get() = current?.store?.now?.enabled == true
    }
}

/** What Settings says about TMDB. Shows still come from TVmaze and AniList in every state. */
enum class TmdbState {
    /** The build's key. */
    AppKey,

    /** The viewer's own key. */
    OwnKey,

    /** No key at all: a fork or a CI build without the secret, and no key of the viewer's own. */
    NoKey,

    /** TMDB refused the build's key, so films go without until an update or a key of the viewer's own. */
    AppKeyRefused,

    /** TMDB refused the viewer's own key. */
    OwnKeyRefused,
}
