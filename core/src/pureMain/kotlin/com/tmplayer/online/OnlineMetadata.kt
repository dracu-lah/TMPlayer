package com.tmplayer.online

import com.tmplayer.data.MediaItem
import com.tmplayer.i18n.Translator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.tmplayer.platform.IOException
import com.tmplayer.platform.LruMap
import com.tmplayer.platform.PlainLock
import com.tmplayer.platform.nowMillis
import com.tmplayer.platform.sha256
import kotlin.concurrent.Volatile
import kotlinx.coroutines.sync.withPermit

/**
 * Posters and overviews for videos, looked up by name: the one place phone, TV and desktop all
 * go through, so the rules below hold the same everywhere.
 *
 * - **Off until asked.** Nothing is looked up until the viewer turns it on in Settings (or from
 *   the detail panel): a lookup sends a video's cleaned name to a provider.
 * - **Providers.** TMDB for films and shows, with the build's key (`TMDB_API_KEY`) or the
 *   viewer's own; TVmaze for shows and their episodes, with no key; AniList for anime, with no
 *   key. A show from TMDB takes its episode from TMDB too, in the UI language; one from TVmaze
 *   from TVmaze. Anime asks AniList first. A detail page with an IMDb id also asks OMDb, with the
 *   viewer's own key or the build's (`OMDB_API_KEY`), for IMDb's, Rotten Tomatoes' and
 *   Metacritic's scores, and Wikidata, with no key, for Rotten Tomatoes' and Metacritic's where
 *   OMDb could not be asked or lacked them; the answer is kept 30 days.
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
    val store: MetaSettingsStore,
    val cache: MetaCacheStore,
    appVersion: String,
    private val http: HttpTransport = defaultTransport,
    private val now: () -> Long = { nowMillis() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val language: () -> String = { Translator.messages.tag },
    /** The viewer's region, for the age rating: the device's country, else the US. */
    private val region: () -> String = { MetaRules.region() },
    /** The build's OMDb key for the other sites' scores; blank, with no key of the viewer's own, asks OMDb nothing. */
    private val omdbKey: String = "",
    /** The build's anime-skip.com client id; blank (the default until the user registers one) asks that site nothing. */
    animeSkipClientId: String = SkipSources.ANIME_SKIP_CLIENT_ID,
) {
    private val userAgent = "TMPlayer/$appVersion (+https://tmplayer.org)"
    private val tmdb = TmdbApi(http, RateLimiter(TmdbApi.GAP_MS, now, sleep), userAgent)
    private val tvmaze = TvMazeApi(http, RateLimiter(TvMazeApi.GAP_MS, now, sleep), userAgent)
    private val anilist = AniListApi(http, RateLimiter(AniListApi.GAP_MS, now, sleep), userAgent)
    private val kitsu = KitsuApi(http, RateLimiter(KitsuApi.GAP_MS, now, sleep), userAgent)
    private val jikan = JikanApi(http, RateLimiter(JikanApi.GAP_MS, now, sleep), userAgent)
    private val omdb = OmdbApi(http, RateLimiter(OmdbApi.GAP_MS, now, sleep), userAgent)
    private val wikidata = WikidataApi(http, RateLimiter(WikidataApi.GAP_MS, now, sleep), userAgent)
    /**
     * Pictures fetched side by side. They come from the providers' image CDNs, which carry no
     * request limit of their own, so they are not queued behind one another as the API calls are:
     * a row of ten posters spaced 50 ms apart, each over a fresh connection, was most of the wait
     * for Home's posters (2026-10-21).
     */
    private val imageGate = kotlinx.coroutines.sync.Semaphore(IMAGE_PARALLEL)
    private val skipApi = SkipTimesApi(
        http,
        RateLimiter(SkipTimesApi.INTRO_GAP_MS, now, sleep),
        RateLimiter(SkipTimesApi.ANISKIP_GAP_MS, now, sleep),
        userAgent,
    )
    private val animeSkip = AnimeSkipApi(http, RateLimiter(AnimeSkipApi.GAP_MS, now, sleep), userAgent, animeSkipClientId, now)
    private val idMap = IdMapApi(http, RateLimiter(IdMapApi.GAP_MS, now, sleep), userAgent)
    private val idMaps = LruMap<String, AnimeIds>(64)
    private var animeSkipPausedUntil = 0L
    private var idMapPausedUntil = 0L

    private val locks = HashMap<String, Mutex>()
    private val locksLock = PlainLock()
    private val memory = LruMap<String, MetaResult>(MEMORY_ENTRIES)
    private val pausedUntil = HashMap<MetaProvider, Long>()
    private val pausedLock = PlainLock()

    val settings: StateFlow<MetaSettings> get() = store.settings

    /** Whether this build carries a TMDB key of its own. */
    val buildHasKey: Boolean get() = buildKey.isNotBlank()

    /** Whether this build carries an OMDb key of its own. */
    val buildHasOmdbKey: Boolean get() = omdbKey.isNotBlank()

    fun setEnabled(value: Boolean) = store.update { it.copy(enabled = value, offByChoice = !value) }

    fun setSkipTimes(value: Boolean) = store.update { it.copy(skipTimes = value) }

    /** Trailers in TMPlayer's own player ([MetaSettings.trailersInApp]), or in the YouTube app and the browser. */
    fun setTrailersInApp(value: Boolean) = store.update { it.copy(trailersInApp = value) }

    /** What the player does at [group]'s stretches: ask, skip by itself, or leave them alone. */
    fun setSkipMode(group: SkipGroup, mode: SkipMode) = store.update { it.copy(skipModes = it.skipModes.with(group, mode)) }

    /** The viewer's own TMDB key; blank goes back to the build's. A new key gets a fresh chance. */
    fun setOwnKey(key: String) = store.update { it.copy(ownKey = key.trim(), refusedKey = "", refusedAt = 0) }

    /**
     * The viewer's own OMDb key; blank goes back to the build's. A new key gets a fresh chance:
     * the pause is lifted, and pages whose scores came from Wikidata alone ask OMDb on the next visit.
     */
    fun setOmdbKey(key: String) {
        store.update { it.copy(omdbKey = key.trim(), omdbRefusedKey = "") }
        omdbPausedUntil = 0
    }

    /** What Settings says about OMDb. */
    fun omdbState(s: MetaSettings = store.now): OmdbState {
        val own = s.omdbKey.isNotBlank()
        return when {
            own && s.omdbRefusedKey.isNotEmpty() && s.omdbRefusedKey == print(s.omdbKey) -> OmdbState.OwnKeyRefused
            own -> OmdbState.OwnKey
            omdbKey.isNotBlank() -> OmdbState.AppKey
            else -> OmdbState.NoKey
        }
    }

    private fun activeOmdbKey(s: MetaSettings = store.now): String = s.omdbKey.ifBlank { omdbKey }

    /** True when OMDb can be asked right now: a key it has not refused, and no pause. */
    private fun omdbUsable(s: MetaSettings = store.now): Boolean =
        omdbState(s).let { it == OmdbState.AppKey || it == OmdbState.OwnKey } && now() >= omdbPausedUntil

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
        sha256(key.encodeToByteArray()).take(6).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    /**
     * The language tag TMDB is asked in: the UI's as a BCP 47 tag with its region, the pseudo-locales
     * and plain English as en-US. The region matters: bare `pt` returns Portugal's titles (Vaiana),
     * `pt-BR` Brazil's (Moana), and TMDB has no `es-419`, only `es-MX` and `es-ES`. Underscored
     * (`pt_BR`) and lower case (`pt-br`) spellings are put right, and a tag that already has a
     * region is kept.
     */
    fun tmdbLanguage(tag: String = language()): String {
        val parts = tag.trim().replace('_', '-').split('-').filter { it.isNotEmpty() }
        val language = parts.firstOrNull()?.lowercase() ?: return ENGLISH
        if (language.startsWith("en")) return ENGLISH
        val region = parts.drop(1).firstOrNull { it.length == 2 || (it.length == 3 && it.all(Char::isDigit)) }?.uppercase()
        val script = parts.drop(1).firstOrNull { it.length == 4 }?.lowercase()
        return when {
            // Latin America has one Spanish catalogue and TMDB no tag for it: Mexico's is its biggest.
            language == "es" && (region == null || region == "419") -> "es-MX"
            language == "pt" && region == null -> "pt-BR"
            // Hans and Hant without a region: the mainland's and Taiwan's.
            language == "zh" && region == null -> if (script == "hant") "zh-TW" else "zh-CN"
            region != null -> "$language-$region"
            else -> language
        }
    }

    // ---- lookups ------------------------------------------------------------------------------

    /** What is already known for [query] in memory, for the first frame. Never touches the disk or the network. */
    fun peek(query: MetaQuery): MetaResult? {
        if (!store.now.enabled) return MetaResult.Off
        return memory[query.cacheKey(tmdbLanguage())]
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
                memory[key] = result
            }
            result
        }
    }

    // ---- choosing the right title --------------------------------------------------------------

    private val choicesMemory = LruMap<String, List<PosterChoice>>(CHOICES_MEMORY_ENTRIES)

    /**
     * The titles TMDB has that [query] could mean, for "Choose the right poster": films and shows,
     * each searched with the year and without it (so the right one is there when the file's year is
     * off), merged and ranked by [PosterChoiceRules.merge]. Up to four calls through the same rate
     * limiter as every lookup, asked only when the viewer opens the picker, and kept in memory only
     * for this run. Offline, throttled, refused and "no key" are answers of their own
     * ([PosterChoices]); this never throws. A forced id in [query] is ignored: the search is for
     * the name.
     */
    suspend fun candidates(query: MetaQuery): PosterChoices {
        val s = store.now
        if (!s.enabled) return PosterChoices.Off
        if (!tmdbUsable(s)) return PosterChoices.NoKey
        val title = query.title.trim()
        if (title.isEmpty()) return PosterChoices.Found(emptyList())
        val lang = tmdbLanguage()
        val key = listOf(title.lowercase(), query.year?.toString().orEmpty(), query.kind.name, query.languages.joinToString(","), lang).joinToString("|")
        locksLock.locked { choicesMemory[key] }?.let { return PosterChoices.Found(it) }
        return try {
            askCandidates(activeKey(s), query.copy(title = title), lang, key)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            PosterChoices.Unavailable
        }
    }

    private suspend fun askCandidates(apiKey: String, query: MetaQuery, lang: String, memoryKey: String): PosterChoices {
        val lists = ArrayList<Pair<Boolean, List<MetaCandidate>>>()
        var blocked = false
        val years = listOf(query.year, null).distinct()
        // The kind the file looks like first, so a throttle part way still leaves its titles.
        for (film in if (query.kind == MetaKind.Film) listOf(true, false) else listOf(false, true)) {
            for (year in years) {
                if (paused(MetaProvider.Tmdb)) {
                    blocked = true
                    continue
                }
                val reply = retrying { if (film) tmdb.searchMovie(apiKey, query.title, year, lang) else tmdb.searchTv(apiKey, query.title, year, lang) }
                val problem = trouble(MetaProvider.Tmdb, reply, apiKey)
                when {
                    problem == null -> lists += film to (reply as MetaReply.Ok).value
                    problem === Outcome.Blocked -> blocked = true
                }
            }
        }
        // A key TMDB just turned down is a message of its own, not a throttle to wait out.
        if (lists.isEmpty() && !tmdbUsable(store.now)) return PosterChoices.NoKey
        // Nothing learned and something in the way: say so, so Try again is offered; never "no titles".
        if (lists.isEmpty() && blocked) return PosterChoices.Unavailable
        val choices = PosterChoiceRules.merge(query, lists)
        // A partial answer (one search was throttled) is shown but not kept, so a later try fills it in.
        if (!blocked) locksLock.locked { choicesMemory[memoryKey] = choices }
        return PosterChoices.Found(choices)
    }

    private val watchMemory = LruMap<String, WhereToWatch>(CHOICES_MEMORY_ENTRIES)

    /**
     * Where [choice] can be watched legally in the viewer's region (HX41's "Where to watch"): one
     * TMDB call through the same rate limiter, asked only when the viewer opens the sheet, kept in
     * memory for this run. Names of services only, no links. Never throws.
     */
    suspend fun whereToWatch(choice: PosterChoice): WhereToWatch {
        val s = store.now
        if (!s.enabled) return WhereToWatch.Off
        if (!tmdbUsable(s)) return WhereToWatch.NoKey
        if (choice.tmdbId <= 0) return WhereToWatch.Unavailable
        val region = region()
        val key = "${choice.onlineKey}|$region"
        locksLock.locked { watchMemory[key] }?.let { return it }
        if (paused(MetaProvider.Tmdb)) return WhereToWatch.Unavailable
        val apiKey = activeKey(s)
        return try {
            val reply = retrying { tmdb.watchProviders(apiKey, !choice.show, choice.tmdbId.toString(), region) }
            val problem = trouble(MetaProvider.Tmdb, reply, apiKey)
            val answer = when {
                problem == null -> (reply as MetaReply.Ok).value.let { if (it.isEmpty) WhereToWatch.NoneHere(it.region) else WhereToWatch.Found(it) }
                // A 404 is TMDB saying it has no such title: nothing listed, said plainly.
                problem === Outcome.Nothing -> WhereToWatch.NoneHere(region.uppercase())
                !tmdbUsable(store.now) -> WhereToWatch.NoKey
                else -> WhereToWatch.Unavailable
            }
            if (answer is WhereToWatch.Found || answer is WhereToWatch.NoneHere) locksLock.locked { watchMemory[key] = answer }
            answer
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            WhereToWatch.Unavailable
        }
    }

    private fun remembered(key: String): MetaResult? {
        memory[key]?.let { return it }
        val stored = cache.answer(key) ?: return null
        memory[key] = stored
        return stored
    }

    private fun lockFor(key: String): Mutex = locksLock.locked {
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

    private fun paused(provider: MetaProvider): Boolean = pausedLock.locked {
        (pausedUntil[provider] ?: 0L) > now()
    }

    /** Sorts a failed reply: pauses on 429, turns TMDB off on a refused key. Null when [reply] is Ok. */
    private fun <T> trouble(provider: MetaProvider, reply: MetaReply<T>, key: String? = null): Outcome? = when (reply) {
        is MetaReply.Ok -> null
        is MetaReply.Throttled -> {
            pausedLock.locked { pausedUntil[provider] = now() + reply.retryAfterMs }
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

    private val linkedMemory = LruMap<String, LinkedAnswer>(MEMORY_ENTRIES)

    /** What `find` said for an IMDb id, kept for this run (a miss too, so a wrong link is asked once). */
    private class LinkedAnswer(val title: TmdbFound?)

    /** The TMDB title an IMDb id names, asked once per id per run; null for none, offline or refused. */
    private suspend fun linkedTitle(key: String, imdbId: String): TmdbFound? {
        linkedMemory[imdbId]?.let { return it.title }
        val reply = retrying { tmdb.findByImdb(key, imdbId) }
        if (reply !is MetaReply.Ok) return null
        linkedMemory[imdbId] = LinkedAnswer(reply.value)
        return reply.value
    }

    private suspend fun tmdbLookup(key: String, query: MetaQuery, lang: String): Outcome {
        var film = query.kind == MetaKind.Film
        // The title an IMDb id in the post's words names (TS): found once per id, and a miss or a
        // failure leaves the title guess to run below.
        val linked = if (query.tmdbId == null) query.imdbId?.let { linkedTitle(key, it) } else null
        if (linked != null) film = linked.film
        var forcedId = query.tmdbId?.toString() ?: linked?.id
        var forcedCandidate: MetaCandidate? = null
        if (forcedId != null) {
            val reply = retrying { if (film) tmdb.movie(key, forcedId!!, lang) else tmdb.tv(key, forcedId!!, lang) }
            if (query.fromLink && reply is MetaReply.Failed && reply.code == 404) {
                // A link to a title TMDB does not have: the title guess runs.
                forcedId = null
            } else {
                trouble(MetaProvider.Tmdb, reply, key)?.let { return it }
                forcedCandidate = (reply as MetaReply.Ok).value
            }
            // A link that the file's own year or episode contradicts is another title's: not taken.
            if (query.fromLink && forcedCandidate != null && !linkAgrees(query, forcedCandidate, film)) forcedCandidate = null
            if (forcedCandidate == null) film = query.episode == null && query.season == null
        }
        val viaLink = query.fromLink && forcedCandidate != null
        // The match as the English search gave it, when that is where it was found.
        var inEnglish: MetaCandidate? = null
        var candidate: MetaCandidate = if (forcedCandidate != null) {
            forcedCandidate
        } else {
            // In another language the titles TMDB answers with are that language's, or the original
            // where nobody translated it ("Le Fabuleux Destin d'Amélie Poulain" in Arabic), and a
            // file named in English matches neither. So a search that finds nothing in the UI
            // language is asked once more in English.
            var found: MetaCandidate? = null
            // A name with no episode code is asked as a film first; a series posted whole or by
            // season with no S01E01 in its name ("Scam 1992 Complete Hindi") is found as a show
            // once no film matches. The year or the file's length still has to back it
            // ([MetaConfidence]) before it is shown.
            for (asFilm in if (film) listOf(true, false) else listOf(false)) {
                for (searchLang in listOf(lang, ENGLISH).distinct()) {
                    when (val outcome = tmdbSearch(key, query, searchLang, asFilm)) {
                        is SearchOutcome.Found -> {
                            found = outcome.candidate
                            if (searchLang != lang) inEnglish = outcome.candidate
                            break
                        }
                        is SearchOutcome.Stopped -> return outcome.outcome
                        SearchOutcome.None -> Unit
                    }
                }
                if (found != null) {
                    film = asFilm
                    break
                }
            }
            found ?: return Outcome.Nothing
        }
        var poster = candidate.posterUrl
        if (lang != ENGLISH) {
            // The title in the UI language, and its poster chosen by [MetaRules.poster] rather than
            // whichever one TMDB names for this language: one call, with the posters appended. A
            // failure here keeps what the search said.
            val ui = lang.substringBefore('-').lowercase()
            val imageLanguages = listOf(ui, "en", "null", candidate.originalLanguage.lowercase()).filter { it.isNotBlank() }.distinct()
            val localized = retrying { tmdb.localized(key, film, candidate.id, lang, imageLanguages) }
            if (localized is MetaReply.Ok) {
                val (local, posters) = localized.value
                val original = local.originalLanguage.ifBlank { candidate.originalLanguage }
                poster = MetaRules.poster(posters, lang, original)?.let { tmdb.posterUrl(it) } ?: local.posterUrl ?: poster
                candidate = local.copy(
                    names = (local.names + candidate.names).filter { it.isNotBlank() }.distinct(),
                    backdropUrl = local.backdropUrl ?: candidate.backdropUrl,
                    originalLanguage = original,
                )
            } else if (inEnglish != null) {
                // Not localized after all: what is shown came from the English search.
                return Outcome.Found(info(query, candidate, film, poster, candidate.overview, ENGLISH, viaLink))
            }
        }
        var overview = candidate.overview
        var overviewLang = lang
        if (overview.isBlank() && lang != ENGLISH) {
            val english = inEnglish?.let { MetaReply.Ok(it) }
                ?: retrying { if (film) tmdb.movie(key, candidate.id, ENGLISH) else tmdb.tv(key, candidate.id, ENGLISH) }
            if (english is MetaReply.Ok) {
                overview = english.value.overview
                overviewLang = ENGLISH
            }
        }
        return Outcome.Found(info(query, candidate, film, poster, overview, overviewLang, viaLink))
    }

    /**
     * Whether the title a link names can be the video's: not a film for a file that names an
     * episode, and not a title from another year than the file's own (a year one off is allowed,
     * and a later season's year on an older show). Nothing else is compared: a link is the
     * poster's word for a file whose name says nothing ("clip_720p").
     */
    private fun linkAgrees(query: MetaQuery, candidate: MetaCandidate, film: Boolean): Boolean {
        if (film && query.episode != null) return false
        val wanted = query.year
        val year = candidate.year
        if (wanted != null && year != null && kotlin.math.abs(wanted - year) > 1 && !(!film && wanted > year)) return false
        return true
    }

    private fun info(query: MetaQuery, candidate: MetaCandidate, film: Boolean, poster: String?, overview: String, overviewLang: String, linked: Boolean = false) = MetaInfo(
        provider = MetaProvider.Tmdb,
        id = candidate.id,
        kind = if (film) MetaKind.Film else MetaKind.Show,
        title = candidate.names.firstOrNull { it.isNotBlank() } ?: query.title,
        year = candidate.year,
        overview = overview,
        posterUrl = poster,
        backdropUrl = candidate.backdropUrl,
        language = overviewLang,
        linked = linked,
    )

    private sealed interface SearchOutcome {
        data class Found(val candidate: MetaCandidate) : SearchOutcome
        data object None : SearchOutcome

        /** Refused, throttled or offline: the lookup ends with [outcome]. */
        data class Stopped(val outcome: Outcome) : SearchOutcome
    }

    /** TMDB's search in [searchLang] for the title that [MetaMatch] takes, with the year and then without it. */
    private suspend fun tmdbSearch(key: String, query: MetaQuery, searchLang: String, film: Boolean = query.kind == MetaKind.Film): SearchOutcome {
        suspend fun search(year: Int?): MetaReply<List<MetaCandidate>> = retrying {
            if (film) tmdb.searchMovie(key, query.title, year, searchLang) else tmdb.searchTv(key, query.title, year, searchLang)
        }
        val first = search(query.year)
        trouble(MetaProvider.Tmdb, first, key)?.let { return SearchOutcome.Stopped(it) }
        var found = MetaPick.best(query, (first as MetaReply.Ok).value, show = !film)
        // A year one off (a festival date, a late release) is filtered out by TMDB's own year,
        // so ask once more without it and let the match allow the difference. So is a show's
        // later season, whose file names carry the season's year rather than the premiere's.
        if (found == null && query.year != null) {
            val again = search(null)
            trouble(MetaProvider.Tmdb, again, key)?.let { return SearchOutcome.Stopped(it) }
            found = MetaPick.best(query, (again as MetaReply.Ok).value, show = !film)
        }
        return found?.let { SearchOutcome.Found(it) } ?: SearchOutcome.None
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
                aliases = found.names.filter { it.isNotBlank() }.distinct().take(ALIASES),
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
                if (reply is MetaReply.Failed && reply.code == 404) {
                    // A number no season of a TMDB show reaches is an anime counted straight through
                    // ("Show 123.mkv" with nothing to say so): the same title asked of AniList finds the run.
                    return (if (number > ABSOLUTE_FROM) absoluteFallback(query, season, number) else null) to true
                }
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
            // AniList lists no episodes: the guide (TMDB's groups and seasons, Kitsu, Jikan, AniList's
            // streaming list) names this one by its absolute number, or the show's words stand for it.
            MetaProvider.AniList -> animeEpisodeFor(show, season, number, query.title)
        }
    }

    // ---- the detail page's extras -------------------------------------------------------------

    private val extrasMemory = LruMap<String, MetaExtras>(EXTRAS_MEMORY_ENTRIES)

    private fun extrasKey(info: MetaInfo, lang: String, region: String): String =
        listOf("extras2", info.provider.name, info.kind.name, info.id, lang, region).joinToString("|")

    /** The extras for [info] already in memory, for the first frame. Never touches the disk or the network. */
    fun peekExtras(info: MetaInfo): MetaExtras? {
        if (!store.now.enabled) return null
        return extrasMemory[extrasKey(info, tmdbLanguage(), region())]
    }

    /**
     * The facts, cast, trailer and recommendations for a title already matched: one call to the
     * provider it came from (TMDB with everything appended, TVmaze with the cast embedded, one
     * AniList query), then kept as long as the match is. Null while off, or when the provider
     * could not be asked; an empty [MetaExtras] when it had nothing.
     */
    /**
     * Where [item]'s intro, recap and credits are, for the player's skip button: empty when
     * lookups or skip times are off, when the video matched no title, or when nobody has timed it.
     *
     * The title comes from the same lookup as its poster. A TMDB match asks TheIntroDB by TMDB id;
     * a TVmaze match asks it by the IMDb or TheTVDB id TVmaze lists; an AniList match asks AniSkip
     * by MyAnimeList id, with the episode's absolute number. Where TheIntroDB has nothing,
     * IntroDB.app is asked by IMDb id, then for a show AniSkip by the title's AniList match.
     * Answers, empty ones too, are kept.
     */
    suspend fun skipTimes(item: com.tmplayer.data.MediaItem): List<SkipSegment> {
        val settings = store.now
        if (!settings.enabled || !settings.skipTimes) return emptyList()
        val query = item.metaQuery ?: return emptyList()
        // The show alone: the skip lookup needs its ids, not this episode's name, so it does not wake the episode guide.
        val info = (lookup(query.showOnly()) as? MetaResult.Found)?.info ?: return emptyList()
        val show = info.kind == MetaKind.Show
        if (show && query.episode == null) return emptyList()
        val season = if (show) query.season ?: 1 else null
        val episode = if (show) query.episode else null
        val key = listOf("skips", info.provider.name, info.kind.name, info.id, season ?: 0, episode ?: 0).joinToString("|")
        cache.skipTimes(key)?.let { return it }
        return lockFor(key).withLock {
            cache.skipTimes(key)?.let { return@withLock it }
            val reply = when (info.provider) {
                MetaProvider.Tmdb -> {
                    val first = skipApi.theIntroDb(info.id, null, null, season, episode)
                    // TheIntroDB keys some titles by IMDb or TheTVDB id only: the ids TMDB lists are the second try.
                    val ids = if (first is MetaReply.Ok && first.value.isEmpty()) providerExtras(info) else null
                    if (ids == null) {
                        first
                    } else {
                        // IMDb first, then TheTVDB: it keys by one id at a time.
                        var reply: MetaReply<List<SkipSegment>> = first
                        if (ids.imdbId != null) reply = skipApi.theIntroDb(null, ids.imdbId, null, season, episode)
                        if (ids.tvdbId != null && (reply as? MetaReply.Ok)?.value?.isEmpty() == true) {
                            reply = skipApi.theIntroDb(null, null, ids.tvdbId, season, episode)
                        }
                        reply
                    }
                }
                MetaProvider.TvMaze -> {
                    val ids = providerExtras(info) ?: return@withLock emptyList()
                    if (ids.imdbId == null && ids.tvdbId == null) return@withLock emptyList()
                    skipApi.theIntroDb(null, ids.imdbId, ids.tvdbId, season, episode)
                }
                MetaProvider.AniList -> animeSkips(info, episode ?: 1, item.durationSec.coerceAtLeast(0))
            }
            // Only a real answer is kept; a failure is asked about again next time.
            var segments = (reply as? MetaReply.Ok)?.value ?: return@withLock emptyList()
            // TheIntroDB had nothing: IntroDB.app, the other community database, by IMDb id.
            if (segments.isEmpty() && info.provider != MetaProvider.AniList) {
                val imdb = providerExtras(info)?.imdbId
                if (imdb != null) {
                    val second = skipApi.introDbApp(imdb, season, episode)
                    segments = (second as? MetaReply.Ok)?.value ?: return@withLock emptyList()
                }
            }
            // An anime matched on TMDB or TVmaze: TheIntroDB has few of those, and neither gives a
            // MyAnimeList id. The same title asked of AniList first finds it if it is an anime,
            // and AniSkip is asked by that. A show AniList does not know comes back as before.
            if (segments.isEmpty() && show && info.provider != MetaProvider.AniList) {
                val anime = (lookup(query.showOnly().copy(anime = true)) as? MetaResult.Found)?.info
                    ?.takeIf { it.provider == MetaProvider.AniList }
                if (anime != null) {
                    val aniReply = animeSkips(anime, episode ?: 1, item.durationSec.coerceAtLeast(0))
                    segments = (aniReply as? MetaReply.Ok)?.value ?: return@withLock emptyList()
                }
            }
            cache.putSkipTimes(key, segments)
            segments
        }
    }

    /**
     * The anime databases for AniList title [anime]'s episode: AniSkip by MyAnimeList id (AniList's
     * own, else the id mapping service's when AniList has none), then anime-skip.com by AniList id
     * when AniSkip has nothing. Ok with an empty list only when every source asked answered; a
     * source that is paused (it said to wait) or failed makes the whole answer a failure, which is
     * not kept, so it is asked again later rather than remembered as "nobody timed this".
     */
    private suspend fun animeSkips(anime: MetaInfo, episode: Int, lengthSec: Int): MetaReply<List<SkipSegment>> {
        var complete = true
        var mal = providerExtras(anime)?.malId
        if (mal == null) {
            var ids = idMaps[anime.id]
            if (ids == null) {
                if (now() < idMapPausedUntil) {
                    complete = false
                } else {
                    when (val reply = idMap.forAniList(anime.id)) {
                        is MetaReply.Ok -> { ids = reply.value; idMaps[anime.id] = reply.value }
                        is MetaReply.Throttled -> { idMapPausedUntil = now() + reply.retryAfterMs; complete = false }
                        else -> complete = false
                    }
                }
            }
            mal = ids?.mal
        }
        if (mal != null) {
            when (val reply = skipApi.aniSkip(mal, episode, lengthSec)) {
                is MetaReply.Ok -> if (reply.value.isNotEmpty()) return reply
                else -> complete = false
            }
        }
        if (animeSkip.configured) {
            if (now() < animeSkipPausedUntil) {
                complete = false
            } else {
                when (val reply = animeSkip.segments(anime.id, episode, lengthSec)) {
                    is MetaReply.Ok -> if (reply.value.isNotEmpty()) return reply
                    is MetaReply.Throttled -> { animeSkipPausedUntil = now() + reply.retryAfterMs; complete = false }
                    else -> complete = false
                }
            }
        }
        return if (complete) MetaReply.Ok(emptyList()) else MetaReply.Failed(0)
    }

    /**
     * [info] for [item], if anything backs it beyond the title ([MetaConfidence]): the year, the
     * episode code, or failing both the file's length against the runtime, which takes the
     * provider's extras (one call, kept like any other). Null leaves the video with its own name
     * and picture. [name] is how the file reads, the viewer's own title where they gave one.
     */
    suspend fun matched(item: MediaItem, name: com.tmplayer.data.ParsedName, info: MetaInfo): MatchedMeta? {
        chosen(item, info)?.let { return it }
        MetaConfidence.basis(name, info)?.let { return MatchedMeta(info, it) }
        if (!MetaConfidence.needsRuntime(name, info, item.durationSec)) return null
        val key = runtimeKey(item, name, info)
        runtimeVerdicts[key]?.let { return it.matched }
        val extras = providerExtras(info) ?: return null
        val verdict = MetaConfidence.basis(name, info, item.durationSec, extras.runtimeMin)?.let { MatchedMeta(info, it) }
        runtimeVerdicts[key] = RuntimeVerdict(verdict)
        return verdict
    }

    /**
     * What [matched] would answer, from memory only, for a tile's first frame: the title and year
     * or the episode code at once, else a verdict on the runtime reached before this run. Without
     * it a tile whose match rests on the runtime drew its own thumbnail first on every visit, and
     * the poster faded in over it a moment later, even with both decoded already.
     */
    fun peekMatched(item: MediaItem, name: com.tmplayer.data.ParsedName, info: MetaInfo): MatchedMeta? {
        chosen(item, info)?.let { return it }
        MetaConfidence.basis(name, info)?.let { return MatchedMeta(info, it) }
        if (!MetaConfidence.needsRuntime(name, info, item.durationSec)) return null
        return runtimeVerdicts[runtimeKey(item, name, info)]?.matched
    }

    /**
     * [info] as the viewer's own pick for [item], when they picked exactly this title: it needs no
     * year or runtime to back it, since the viewer's word is the backing.
     */
    private fun chosen(item: MediaItem, info: MetaInfo): MatchedMeta? {
        val pick = com.tmplayer.data.VideoCorrections.current[item.id]?.pick ?: return null
        if (info.provider != MetaProvider.Tmdb || info.id != pick.tmdbId.toString() || info.kind != pick.kind) return null
        return MatchedMeta(info, MatchBasis.Chosen)
    }

    /** [matched]'s verdicts that took the provider's runtime, a "no" included, for this run. */
    private val runtimeVerdicts = LruMap<String, RuntimeVerdict>(MEMORY_ENTRIES)

    private class RuntimeVerdict(val matched: MatchedMeta?)

    private fun runtimeKey(item: MediaItem, name: com.tmplayer.data.ParsedName, info: MetaInfo): String =
        listOf(info.provider.name, info.kind.name, info.id, item.durationSec, name.hashCode()).joinToString("|")

    /**
     * The detail page's extras: the provider's, with the other sites' scores from OMDb the first
     * time the page is opened ([withScores]). Matching and skip times take [providerExtras], so
     * only an opened page spends an OMDb request.
     */
    suspend fun extras(info: MetaInfo): MetaExtras? {
        val found = providerExtras(info) ?: return null
        return withScores(extrasKey(info, tmdbLanguage(), region()), info, found)
    }

    /**
     * [item]'s TMDB and IMDb ids, for a subtitle search that takes them (MSone): the match the
     * details found, and the IMDb id from its extras (kept like a detail page's). Nulls when the
     * details are off or found nothing; never throws.
     */
    suspend fun idsFor(item: MediaItem): VideoIds = runCatching {
        val query = com.tmplayer.data.VideoCorrections.queryOf(item) ?: return VideoIds()
        val info = (lookup(query) as? MetaResult.Found)?.info ?: return VideoIds()
        val imdb = providerExtras(info)?.imdbId?.trim()?.lowercase()?.takeIf { IMDB_ID.matches(it) }
        VideoIds(tmdb = info.id.takeIf { info.provider == MetaProvider.Tmdb && it.all(Char::isDigit) }, imdb = imdb)
    }.getOrDefault(VideoIds())

    /**
     * The provider's extras alone, from memory, the disk cache or the provider. A TMDB show of one
     * season kept from before its episode groups were read is asked once more, for its split
     * ([SeasonSplit]); the scores it had are carried over, and while TMDB cannot be asked it stays.
     */
    private suspend fun providerExtras(info: MetaInfo): MetaExtras? {
        if (!store.now.enabled) return null
        val lang = tmdbLanguage()
        val key = extrasKey(info, lang, region())
        rememberedExtras(key, info)?.let { if (!wantsGroups(info, it)) return it }
        return lockFor(key).withLock {
            val kept = rememberedExtras(key, info)
            if (kept != null && !wantsGroups(info, kept)) return@withLock kept
            val fetched = fetchExtras(info, lang) ?: return@withLock kept
            val found = if (kept == null) {
                fetched.extras
            } else {
                fetched.extras.copy(
                    scores = MetaScore.merge(fetched.extras.scores, kept.scores.filter { it.source != ScoreSource.Tmdb }),
                    scoresAsked = kept.scoresAsked,
                    scoresAt = kept.scoresAt,
                    scoresFromOmdb = kept.scoresFromOmdb,
                )
            }
            // A split whose group could not be fetched is shown unsplit and not kept, so the next visit asks again.
            if (fetched.keep) {
                cache.putExtras(key, found)
                extrasMemory[key] = found
            }
            found
        }
    }

    /** [extras] are a TMDB show's of one season kept from before the groups were read, so a split was never looked for. */
    private fun wantsGroups(info: MetaInfo, extras: MetaExtras): Boolean =
        info.provider == MetaProvider.Tmdb && info.kind == MetaKind.Show && !extras.groupsRead &&
            extras.seasonList.count { it.number > 0 } == 1

    /**
     * The one score a card shows for [info] ([MetaScore.cardPick]), if this run has it: from the
     * extras in memory, else from an earlier [cachedCardScore]. Null when it has not been looked
     * for; a [CardScore] with no score when nothing is cached. Never the disk or the network.
     */
    fun peekCardScore(info: MetaInfo): CardScore? {
        if (!store.now.enabled) return CardScore(null)
        val key = extrasKey(info, tmdbLanguage(), region())
        extrasMemory[key]?.let { return CardScore(MetaScore.cardPick(it.withProviderScore(info.provider).scores)) }
        return cardScores[key]
    }

    /**
     * [peekCardScore], else the extras kept on the disk by an earlier detail page: never the
     * network, so a grid of 20,000 videos asks nobody for its cards. Reads a file; call it off the
     * main thread. The answer is kept in [cardScores], a few bytes a title, and not in the extras
     * memory, so scrolling a grid never pushes out the detail pages' answers.
     */
    fun cachedCardScore(info: MetaInfo): MetaScore? {
        peekCardScore(info)?.let { return it.score }
        val key = extrasKey(info, tmdbLanguage(), region())
        val score = cache.extras(key)?.let { MetaScore.cardPick(it.withProviderScore(info.provider).scores) }
        cardScores[key] = CardScore(score)
        return score
    }

    /**
     * [info]'s extras from memory or the disk cache, never the network: for a screen too short to
     * wait for a lookup (the opening loader). Reads a file; call it off the main thread.
     */
    fun storedExtras(info: MetaInfo): MetaExtras? {
        if (!store.now.enabled) return null
        return rememberedExtras(extrasKey(info, tmdbLanguage(), region()), info)
    }

    private val cardScores = LruMap<String, CardScore>(CARD_SCORE_ENTRIES)

    private fun rememberedExtras(key: String, info: MetaInfo): MetaExtras? {
        extrasMemory[key]?.let { return it }
        val stored = cache.extras(key)?.withProviderScore(info.provider) ?: return null
        extrasMemory[key] = stored
        return stored
    }

    // ---- the other sites' scores, from OMDb and Wikidata ---------------------------------------

    /** Asks still running, by extras key, so a page opened twice asks once. */
    private val scoreJobs = HashMap<String, Deferred<MetaExtras?>>()
    private val scoreJobsLock = PlainLock()

    /** Outlives a caller that stopped waiting, so a slow answer is still kept for next time. */
    private val scoreScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + com.tmplayer.platform.JobFailures.handler("OnlineMetadata"))

    /** Until when OMDb is left alone: the build's key was refused or the day's requests are spent. */
    @Volatile
    private var omdbPausedUntil = 0L

    /** Until when Wikidata is left alone, after it asked for a wait. */
    @Volatile
    private var wikidataPausedUntil = 0L

    /**
     * Whether [extras] should ask for the other sites' scores: never asked, asked more than
     * [SCORES_KEEP_S] ago, or answered by Wikidata alone while OMDb can now be asked (a key was
     * added, or the build's key has a new day).
     */
    private fun scoresDue(extras: MetaExtras): Boolean = when {
        !extras.scoresAsked -> true
        now() / 1000 - extras.scoresAt > SCORES_KEEP_S -> true
        !extras.scoresFromOmdb && omdbUsable() -> true
        else -> false
    }

    /**
     * [extras] with OMDb's and Wikidata's scores in front of the provider's, the first time a
     * title with an IMDb id is opened and again once they are 30 days old (answers kept from
     * before they were asked gain them then too). The page waits at most [OMDB_WAIT_MS]; an
     * answer that comes later is kept for the next visit.
     */
    private suspend fun withScores(key: String, info: MetaInfo, extras: MetaExtras): MetaExtras {
        val imdb = extras.imdbId
        if (imdb == null || !scoresDue(extras)) return extras
        if (!omdbUsable() && now() < wikidataPausedUntil) return extras
        val job = scoreJobsLock.locked {
            scoreJobs.getOrPut(key) { scoreScope.async(start = CoroutineStart.LAZY) { askScores(key, info, imdb) } }
        }
        job.start()
        return withTimeoutOrNull(OMDB_WAIT_MS) { job.await() } ?: extras
    }

    private suspend fun askScores(key: String, info: MetaInfo, imdb: String): MetaExtras? = try {
        val fromOmdb = if (omdbUsable()) askOmdb(imdb) else null
        val wanted = listOf(ScoreSource.RottenTomatoes, ScoreSource.Metacritic)
        val fromWikidata = if (fromOmdb == null || wanted.any { s -> fromOmdb.none { it.source == s } }) askWikidata(imdb) else null
        val current = rememberedExtras(key, info)
        when {
            current == null || !scoresDue(current) -> current
            // Nobody answered: offline, a server error or a pause. Asked again on the next visit.
            fromOmdb == null && fromWikidata == null -> null
            else -> {
                val merged = current.copy(
                    // OMDb's first, then Wikidata's, then what was kept (the provider's own, and any
                    // site that did not answer this time).
                    scores = MetaScore.merge(fromOmdb.orEmpty(), MetaScore.merge(fromWikidata.orEmpty(), current.scores)),
                    scoresAsked = true,
                    scoresAt = now() / 1000,
                    scoresFromOmdb = fromOmdb != null,
                )
                cache.putExtras(key, merged)
                extrasMemory[key] = merged
                merged
            }
        }
    } finally {
        scoreJobsLock.locked { scoreJobs.remove(key) }
    }

    /** OMDb's scores for [imdb], or null when it did not answer (and why is noted for next time). */
    private suspend fun askOmdb(imdb: String): List<MetaScore>? {
        val s = store.now
        val key = activeOmdbKey(s)
        val own = s.omdbKey.isNotBlank()
        return when (val reply = omdb.scores(key, imdb)) {
            is MetaReply.Ok -> reply.value
            // A refused key of the viewer's own stays off until they change it, as Settings says;
            // the build's is left alone until the next UTC day, in case it was a blip.
            MetaReply.Refused -> {
                if (own) {
                    store.update { it.copy(omdbRefusedKey = print(key)) }
                } else {
                    omdbPausedUntil = nextDay()
                }
                null
            }
            // A spent day: nothing more is asked until the next UTC day.
            is MetaReply.Throttled -> {
                omdbPausedUntil = if (reply.retryAfterMs == OmdbApi.SPENT) nextDay() else now() + reply.retryAfterMs
                null
            }
            is MetaReply.Failed -> null
        }
    }

    /** Wikidata's Rotten Tomatoes and Metacritic scores for [imdb], or null when it did not answer. */
    private suspend fun askWikidata(imdb: String): List<MetaScore>? {
        if (now() < wikidataPausedUntil) return null
        return when (val reply = wikidata.scores(imdb)) {
            is MetaReply.Ok -> reply.value
            is MetaReply.Throttled -> {
                wikidataPausedUntil = now() + reply.retryAfterMs
                null
            }
            MetaReply.Refused, is MetaReply.Failed -> null
        }
    }

    private fun nextDay(): Long = (now() / DAY_MS + 1) * DAY_MS

    /** Extras as fetched, and whether they may be kept: not while a split's group could not be fetched. */
    private class FetchedExtras(val extras: MetaExtras, val keep: Boolean = true)

    /** The provider's answer, an empty one for a 404, or null when it could not be asked. */
    private suspend fun fetchExtras(info: MetaInfo, lang: String): FetchedExtras? {
        if (paused(info.provider)) return null
        val reply: MetaReply<MetaExtras>
        when (info.provider) {
            MetaProvider.Tmdb -> {
                val s = store.now
                if (!tmdbUsable(s)) return null
                val k = activeKey(s)
                val film = info.kind == MetaKind.Film
                val both = retrying { tmdb.extrasAndGroups(k, film, info.id, lang, region()) }
                return when (trouble(info.provider, both, k)) {
                    null -> {
                        val got = (both as MetaReply.Ok).value
                        if (film) FetchedExtras(got.extras) else splitShow(info, k, lang, got)
                    }
                    Outcome.Nothing -> FetchedExtras(MetaExtras())
                    else -> null
                }
            }
            MetaProvider.TvMaze -> reply = retrying { tvmaze.extras(info.id) }
            MetaProvider.AniList -> reply = retrying { anilist.extras(info.id) }
        }
        return when (trouble(info.provider, reply)) {
            null -> FetchedExtras((reply as MetaReply.Ok).value)
            Outcome.Nothing -> FetchedExtras(MetaExtras())
            else -> null
        }
    }

    /**
     * A TMDB show's extras with its seasons split the way its episode group splits them, where it
     * lists one season that a group splits in several ([SeasonSplit.choose]); unchanged otherwise.
     * The group costs one call, only for such a show. A group that could not be fetched leaves the
     * extras unsplit and not to be kept, so the next visit asks again.
     */
    private suspend fun splitShow(info: MetaInfo, key: String, lang: String, got: TmdbExtras): FetchedExtras {
        val extras = got.extras.copy(groupsRead = true)
        val group = SeasonSplit.choose(extras.seasonList, got.groups) ?: return FetchedExtras(extras)
        return when (val load = loadSplit(info, key, group.id, lang, extras.seasonList)) {
            is SplitLoad.Found -> FetchedExtras(load.split.applyTo(extras))
            SplitLoad.None -> FetchedExtras(extras)
            SplitLoad.Blocked -> FetchedExtras(extras, keep = false)
        }
    }

    private sealed interface SplitLoad {
        class Found(val split: SeasonSplit.Split) : SplitLoad
        /** The group is gone (404) or splits nothing: TMDB's own seasons stand. */
        data object None : SplitLoad
        /** Throttled, refused, paused or offline: nothing learned, nothing kept. */
        data object Blocked : SplitLoad
    }

    private fun splitKey(groupId: String, lang: String): String = listOf("split", groupId, lang).joinToString("|")

    private fun splitSeasonKey(info: MetaInfo, groupId: String, season: Int, lang: String): String =
        listOf("season", info.provider.name, info.id, "split", groupId, season, lang).joinToString("|")

    /**
     * The split [groupId] makes of [listed] (the show's own seasons): from the day's cache, else one
     * call for the group, kept a day like a season. Each of its seasons goes into [seasonMemory]
     * for [peekSeason]. A failed or throttled call is never kept.
     */
    private suspend fun loadSplit(info: MetaInfo, key: String?, groupId: String, lang: String, listed: List<MetaSeason>): SplitLoad {
        val cacheKey = splitKey(groupId, lang)
        val episodes = SeasonSplit.read(cache.season(cacheKey)) ?: run {
            val k = key ?: store.now.takeIf { tmdbUsable(it) }?.let { activeKey(it) } ?: return SplitLoad.Blocked
            if (paused(MetaProvider.Tmdb)) return SplitLoad.Blocked
            val reply = retrying { tmdb.episodeGroup(k, groupId, lang) }
            when (trouble(MetaProvider.Tmdb, reply, k)) {
                null -> (reply as MetaReply.Ok).value.also { cache.putSeason(cacheKey, SeasonSplit.store(it)) }
                // A 404 is an answer: kept as an empty group, so it is not asked again within the day.
                Outcome.Nothing -> {
                    cache.putSeason(cacheKey, SeasonSplit.store(emptyList()))
                    return SplitLoad.None
                }
                else -> return SplitLoad.Blocked
            }
        }
        val split = SeasonSplit.build(groupId, episodes, listed) ?: return SplitLoad.None
        split.episodes.forEach { (n, list) -> seasonMemory[splitSeasonKey(info, groupId, n, lang)] = list }
        return SplitLoad.Found(split)
    }

    /**
     * [season] of the split [extras] name: from memory, else the group kept for the day, else one
     * call. Empty when the group is gone or splits nothing now (the counts still stand), null
     * when TMDB could not be asked: never TMDB's own season under the split's number.
     */
    private suspend fun splitSeason(info: MetaInfo, extras: MetaExtras, season: Int, lang: String): List<MetaEpisode>? {
        val group = extras.seasonGroup ?: return null
        val key = splitSeasonKey(info, group, season, lang)
        seasonMemory[key]?.let { return it }
        return lockFor(splitKey(group, lang)).withLock {
            seasonMemory[key]?.let { return@withLock it }
            when (val load = loadSplit(info, null, group, lang, extras.listedSeasons)) {
                is SplitLoad.Found -> load.split.episodes[season].orEmpty()
                SplitLoad.None -> emptyList()
                SplitLoad.Blocked -> null
            }
        }
    }

    /** The group [info]'s kept extras split its seasons by, or null: memory only when [memoryOnly], else the disk too. */
    private fun splitGroupOf(info: MetaInfo, lang: String, memoryOnly: Boolean): MetaExtras? {
        if (info.provider != MetaProvider.Tmdb || info.kind != MetaKind.Show) return null
        val key = extrasKey(info, lang, region())
        val extras = if (memoryOnly) extrasMemory[key] else rememberedExtras(key, info)
        return extras?.takeIf { it.seasonGroup != null }
    }

    // ---- a show's episode lists ----------------------------------------------------------------

    private val seasonMemory = LruMap<String, List<MetaEpisode>>(EXTRAS_MEMORY_ENTRIES)

    private fun seasonKey(info: MetaInfo, season: Int, lang: String): String = seasonKey(info.provider, info.id, season, lang)

    private fun seasonKey(provider: MetaProvider, id: String, season: Int, lang: String): String =
        listOf("season", provider.name, id, season, lang).joinToString("|")

    /** [season]'s list already in memory, for the first frame. [split] as in [season]. */
    fun peekSeason(info: MetaInfo, season: Int, split: Boolean = true): List<MetaEpisode>? {
        if (!store.now.enabled || info.kind != MetaKind.Show) return null
        if (info.provider == MetaProvider.AniList) return peekAnimeGuide(info)?.let { AnimeEpisodes.seasonEpisodes(it, season) }
        val lang = tmdbLanguage()
        val group = if (split) splitGroupOf(info, lang, memoryOnly = true)?.seasonGroup else null
        if (group != null) return seasonMemory[splitSeasonKey(info, group, season, lang)]
        return seasonMemory[seasonKey(info, season, lang)]
    }

    /**
     * Every episode of [season] of the show [info], with names, pictures and air dates, so the
     * detail page can list the ones the chat lacks and the ones still to air. TMDB asks for the
     * season; TVmaze answers every season at once, which is kept per season. AniList lists no
     * episodes, so an anime from it is answered by [animeGuide]: season 1 is the whole run counted
     * straight through. Null while off or when nobody could be asked; kept a day (see
     * [MetadataCache.season]).
     *
     * A TMDB show whose extras follow an episode group's split ([MetaExtras.seasonGroup]) answers
     * in the split's numbering, the group's episodes numbered from one in each season; [split]
     * false asks for TMDB's own season instead, for a chat numbered TMDB's way
     * ([com.tmplayer.data.EpisodeGuide.numbering]).
     */
    suspend fun season(info: MetaInfo, season: Int, split: Boolean = true): List<MetaEpisode>? {
        if (!store.now.enabled || info.kind != MetaKind.Show) return null
        if (info.provider == MetaProvider.AniList) return animeGuide(info)?.let { AnimeEpisodes.seasonEpisodes(it, season) }
        val lang = tmdbLanguage()
        if (split) splitGroupOf(info, lang, memoryOnly = false)?.let { return splitSeason(info, it, season, lang) }
        val key = seasonKey(info, season, lang)
        seasonMemory[key]?.let { return it }
        return lockFor(key).withLock {
            seasonMemory[key]?.let { return@withLock it }
            cache.season(key)?.let { stored ->
                seasonMemory[key] = stored
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
                    seasonMemory[key] = found
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
                        seasonMemory[k] = list
                    }
                    val found = all.filter { it.season == season }
                    if (found.isEmpty()) {
                        cache.putSeason(key, found)
                        seasonMemory[key] = found
                    }
                    found
                }
                MetaProvider.AniList -> null
            }
        }
    }

    // ---- a long anime's episodes (T11) ---------------------------------------------------------

    private val guideMemory = LruMap<String, AnimeEpisodes.Guide>(GUIDE_MEMORY_ENTRIES)

    private fun animeShow(info: MetaInfo): Boolean = info.kind == MetaKind.Show && info.provider == MetaProvider.AniList

    private fun guideKey(info: MetaInfo, lang: String): String = listOf("anime", info.id, lang).joinToString("|")

    /** [info]'s episode guide already in memory, for the first frame. Never touches the disk or the network. */
    fun peekAnimeGuide(info: MetaInfo): AnimeEpisodes.Guide? {
        if (!store.now.enabled || !animeShow(info)) return null
        return guideMemory[guideKey(info, tmdbLanguage())]
    }

    /**
     * The episodes of the anime [info] (an AniList match) numbered the way its files are, with a
     * title, an overview, a picture and a date wherever a source has them, and each episode's place
     * in TMDB's seasons. One answer for the show: each source is asked once (not once a row), in
     * the order TMDB's absolute episode group, TMDB's seasons, Kitsu, Jikan, AniList's streaming
     * list, and a later one only while the earlier left titles missing. Every answer is kept for a
     * day, so a guide cut short by the network resumes where it stopped. Null while off, for a film,
     * offline with nothing kept, or when no source knows the show. [hint] is another name for the
     * show (the file's), tried for TMDB beside the provider's own.
     */
    suspend fun animeGuide(info: MetaInfo, hint: String? = null): AnimeEpisodes.Guide? =
        animeAnswer(info, hint)?.guide?.takeIf { !it.isEmpty }

    private class GuideAnswer(val guide: AnimeEpisodes.Guide, val complete: Boolean)

    private suspend fun animeAnswer(info: MetaInfo, hint: String? = null): GuideAnswer? {
        if (!store.now.enabled || !animeShow(info)) return null
        val lang = tmdbLanguage()
        val key = guideKey(info, lang)
        guideMemory[key]?.let { return GuideAnswer(it, true) }
        return lockFor(key).withLock {
            guideMemory[key]?.let { return@withLock GuideAnswer(it, true) }
            val built = buildGuide(info, hint, lang) ?: return@withLock null
            // Only a guide every source answered for is kept in memory; a cut one is asked for the rest next time.
            if (built.complete) guideMemory[key] = built.guide
            built
        }
    }

    private suspend fun buildGuide(info: MetaInfo, hint: String?, lang: String): GuideAnswer? {
        var complete = true
        // AniList's own facts: how many episodes the run has, the next to air, the MyAnimeList id, the streaming list.
        val runKey = "anime|run|${info.id}"
        val run = AnimeStored.readRun(cache.season(runKey)) ?: run {
            if (paused(MetaProvider.AniList)) return null
            val reply = retrying { anilist.episodes(info.id) }
            val found = when (trouble(MetaProvider.AniList, reply)) {
                null -> (reply as MetaReply.Ok).value
                Outcome.Nothing -> AniListRun(null, null, null, emptyList(), null)
                else -> return null
            }
            cache.putSeason(runKey, AnimeStored.run(found))
            found
        }
        val expected = run.episodes ?: run.next?.let { it.episode - 1 }?.takeIf { it > 0 }
        val lists = ArrayList<AnimeEpisodes.SourceList>()
        var counts = emptyList<MetaSeason>()

        val tmdbPart = tmdbAnime(info, hint, lang, expected)
        complete = complete && tmdbPart.complete
        lists += tmdbPart.lists
        counts = tmdbPart.counts

        fun missingTitles(): Boolean {
            val sofar = AnimeEpisodes.merge(lists, counts, run.next)
            val total = expected ?: sofar.total
            if (total <= 0) return true
            val named = sofar.entries.count { it.absolute <= total && it.name.isNotBlank() }
            return total - named > maxOf(MISSING_TITLES_OK, total / 50)
        }

        val mal = run.malId
        if (mal != null && missingTitles()) {
            val part = kitsuEpisodes(mal, expected)
            complete = complete && part.complete
            part.list?.let { lists += it }
        }
        if (mal != null && missingTitles()) {
            val part = jikanEpisodes(mal, expected)
            complete = complete && part.complete
            part.list?.let { lists += it }
        }
        if (run.streaming.isNotEmpty()) lists += AnimeEpisodes.SourceList(AnimeEpisodes.Source.AniList, run.streaming)
        return GuideAnswer(AnimeEpisodes.merge(lists, counts, run.next, run.episodes ?: 0), complete)
    }

    private class AnimePart(val list: AnimeEpisodes.SourceList?, val complete: Boolean)

    private class TmdbAnimePart(val lists: List<AnimeEpisodes.SourceList>, val counts: List<MetaSeason>, val complete: Boolean)

    /**
     * TMDB's side of the guide: the show found by the provider's names (kept, a miss too), its
     * seasons with their counts, and then its "Absolute order" episode group, or failing that its
     * seasons' episodes, twenty to a call. A list too short or too long for the run ([AnimeEpisodes.plausible])
     * is another show of a similar name and is dropped with its counts.
     */
    private suspend fun tmdbAnime(info: MetaInfo, hint: String?, lang: String, expected: Int?): TmdbAnimePart {
        val none = TmdbAnimePart(emptyList(), emptyList(), true)
        val s = store.now
        if (!tmdbUsable(s) || paused(MetaProvider.Tmdb)) return TmdbAnimePart(emptyList(), emptyList(), !tmdbUsable(s))
        val key = activeKey(s)
        val idKey = "anime|tmdbid|${info.id}"
        var id = AnimeStored.readId(cache.season(idKey))
        if (id == AnimeStored.MISSING) {
            val names = listOfNotNull(hint) + info.title + info.aliases
            id = null
            var answered = true
            for (name in names.filter { it.isNotBlank() }.distinct().take(TMDB_NAMES)) {
                when (val outcome = tmdbSearch(key, MetaQuery(title = name, kind = MetaKind.Show), ENGLISH, film = false)) {
                    is SearchOutcome.Found -> {
                        id = outcome.candidate.id
                        break
                    }
                    is SearchOutcome.Stopped -> {
                        answered = false
                        break
                    }
                    SearchOutcome.None -> Unit
                }
            }
            if (!answered) return TmdbAnimePart(emptyList(), emptyList(), false)
            cache.putSeason(idKey, AnimeStored.id(id))
        }
        val tmdbId = id ?: return none

        val showKey = "anime|tmdbshow|$tmdbId|$lang"
        val show = AnimeStored.readShowGuide(cache.season(showKey)) ?: run {
            val reply = retrying { tmdb.showGuide(key, tmdbId, lang) }
            when (trouble(MetaProvider.Tmdb, reply, key)) {
                null -> (reply as MetaReply.Ok).value.also { cache.putSeason(showKey, AnimeStored.showGuide(it)) }
                Outcome.Nothing -> return none
                else -> return TmdbAnimePart(emptyList(), emptyList(), false)
            }
        }
        val layout = AnimeEpisodes.layout(show.seasons)
        val total = layout.sumOf { it.episodeCount }
        val wrong = !AnimeEpisodes.plausible(expected, AnimeEpisodes.SourceList(AnimeEpisodes.Source.TmdbSeasons, listOf(AnimeEpisodes.Piece(total.coerceAtLeast(1)))))
        if (layout.isEmpty() || wrong) return none

        var complete = true
        // The absolute order group, the one with the most episodes where TMDB has several.
        val group = show.groups.filter { it.absolute }.maxByOrNull { it.episodeCount }
        if (group != null) {
            val groupKey = "anime|tmdbgroup|${group.id}|$lang"
            val stored = AnimeStored.readGroup(cache.season(groupKey))
            val list = stored ?: run {
                val reply = retrying { tmdb.episodeGroup(key, group.id, lang) }
                when (trouble(MetaProvider.Tmdb, reply, key)) {
                    null -> {
                        val episodes = (reply as MetaReply.Ok).value
                        cache.putSeason(groupKey, AnimeStored.group(episodes))
                        AnimeEpisodes.fromGroup(episodes)
                    }
                    Outcome.Nothing -> null
                    else -> {
                        complete = false
                        null
                    }
                }
            }
            if (list != null && AnimeEpisodes.plausible(expected, list)) return TmdbAnimePart(listOf(list), layout, complete)
        }
        if (!complete) return TmdbAnimePart(emptyList(), layout, false)

        // The seasons counted in order: one call for each twenty not yet kept.
        val bySeason = HashMap<Int, List<MetaEpisode>>()
        val needed = ArrayList<Int>()
        for (s in layout) {
            val kept = cache.season(seasonKey(MetaProvider.Tmdb, tmdbId, s.number, lang))
            if (kept != null) bySeason[s.number] = kept else needed += s.number
        }
        for (chunk in needed.chunked(TmdbApi.APPEND_LIMIT)) {
            val reply = retrying { tmdb.seasons(key, tmdbId, chunk, lang) }
            when (trouble(MetaProvider.Tmdb, reply, key)) {
                null -> (reply as MetaReply.Ok).value.forEach { (number, list) ->
                    bySeason[number] = list
                    cache.putSeason(seasonKey(MetaProvider.Tmdb, tmdbId, number, lang), list)
                }
                Outcome.Nothing -> Unit
                else -> {
                    complete = false
                    break
                }
            }
        }
        val list = AnimeEpisodes.fromSeasons(bySeason, layout)
        return TmdbAnimePart(listOf(list), layout, complete)
    }

    /** Kitsu's episodes for the MyAnimeList id [mal], twenty a page, kept whole once all pages came. */
    private suspend fun kitsuEpisodes(mal: String, expected: Int?): AnimePart {
        val idKey = "anime|kitsuid|$mal"
        var id = AnimeStored.readId(cache.season(idKey))
        if (id == AnimeStored.MISSING) {
            // Kitsu has no place among the three providers a 429 pauses: a throttled or failed call only leaves this source for next time.
            val reply = retrying { kitsu.idFor(mal) }
            id = when {
                reply is MetaReply.Ok -> reply.value
                reply is MetaReply.Failed && reply.code == 404 -> null
                else -> return AnimePart(null, false)
            }
            cache.putSeason(idKey, AnimeStored.id(id))
        }
        val kitsuId = id ?: return AnimePart(null, true)
        val listKey = "anime|kitsu|$kitsuId"
        cache.season(listKey)?.let { kept ->
            val list = AnimeEpisodes.fromNumbered(AnimeEpisodes.Source.Kitsu, kept)
            return AnimePart(list.takeIf { AnimeEpisodes.plausible(expected, it) }, true)
        }
        val all = ArrayList<MetaEpisode>()
        var offset = 0
        var pages = 0
        while (pages < KitsuApi.MAX_PAGES) {
            val reply = retrying { kitsu.episodes(kitsuId, offset) }
            when (reply) {
                is MetaReply.Ok -> {
                    all += reply.value.first
                    pages++
                    offset += KitsuApi.PAGE
                    if (!reply.value.second) break
                }
                is MetaReply.Failed -> if (reply.code == 404 && pages == 0) return AnimePart(null, true) else return partial(AnimeEpisodes.Source.Kitsu, all, expected)
                else -> return partial(AnimeEpisodes.Source.Kitsu, all, expected)
            }
        }
        cache.putSeason(listKey, all)
        val list = AnimeEpisodes.fromNumbered(AnimeEpisodes.Source.Kitsu, all)
        return AnimePart(list.takeIf { AnimeEpisodes.plausible(expected, it) }, true)
    }

    /** Jikan's episodes for MyAnimeList's [mal], a hundred a page, kept whole once all pages came. */
    private suspend fun jikanEpisodes(mal: String, expected: Int?): AnimePart {
        val listKey = "anime|jikan|$mal"
        cache.season(listKey)?.let { kept ->
            val list = AnimeEpisodes.fromNumbered(AnimeEpisodes.Source.Jikan, kept)
            return AnimePart(list.takeIf { AnimeEpisodes.plausible(expected, it) }, true)
        }
        val all = ArrayList<MetaEpisode>()
        var page = 1
        while (page <= JikanApi.MAX_PAGES) {
            val reply = retrying { jikan.episodes(mal, page) }
            when (reply) {
                is MetaReply.Ok -> {
                    all += reply.value.first
                    page++
                    if (!reply.value.second) break
                }
                is MetaReply.Failed -> if (reply.code == 404 && page == 1) return AnimePart(null, true) else return partial(AnimeEpisodes.Source.Jikan, all, expected)
                else -> return partial(AnimeEpisodes.Source.Jikan, all, expected)
            }
        }
        cache.putSeason(listKey, all)
        val list = AnimeEpisodes.fromNumbered(AnimeEpisodes.Source.Jikan, all)
        return AnimePart(list.takeIf { AnimeEpisodes.plausible(expected, it) }, true)
    }

    /** What came before a page failed: used for now, kept for nothing, and the guide is marked cut short. */
    private fun partial(source: AnimeEpisodes.Source, got: List<MetaEpisode>, expected: Int?): AnimePart =
        AnimePart(got.takeIf { it.isNotEmpty() }?.let { AnimeEpisodes.fromNumbered(source, it) }, false)

    /** The guide's name for episode [number] of a TMDB show that has no such episode, found through AniList; null when it is no anime. */
    private suspend fun absoluteFallback(query: MetaQuery, season: Int, number: Int): MetaEpisode? {
        val anime = (lookup(query.showOnly().copy(anime = true)) as? MetaResult.Found)?.info?.takeIf { animeShow(it) } ?: return null
        return animeEpisodeFor(anime, season, number, query.title).first
    }

    /**
     * Episode [number] of [season] as the guide names it, for [show] (an AniList match): its title,
     * overview and picture, carrying the file's own season and number so [EpisodeNames.named] takes
     * it. The second part says whether the answer is final (not kept while a source was cut short).
     */
    private suspend fun animeEpisodeFor(show: MetaInfo, season: Int, number: Int, hint: String): Pair<MetaEpisode?, Boolean> {
        val answer = animeAnswer(show, hint) ?: return null to false
        val guide = answer.guide
        val entry = guide.absoluteOf(season, number)?.let { guide.entry(it) }
            ?.takeIf { it.name.isNotBlank() || it.overview.isNotBlank() || it.stillUrl != null }
        val found = entry?.let { MetaEpisode(season, number, it.name, it.overview, it.stillUrl, it.airDate, it.runtimeMin, it.source?.label.orEmpty()) }
        return found to answer.complete
    }

    /**
     * [extras] with the anime's run in them, for a show AniList lists no seasons for: one season
     * counted straight through and the next episode, so the series page can list what the chats
     * lack as it does for a TMDB show. [extras] as it was for any other show, and while the guide
     * is not known.
     */
    suspend fun animeExtras(info: MetaInfo, extras: MetaExtras): MetaExtras =
        animeGuide(info)?.let { AnimeEpisodes.overlay(extras, it) } ?: extras

    /** [animeExtras] from what is in memory, for the first frame. */
    fun peekAnimeExtras(info: MetaInfo, extras: MetaExtras): MetaExtras =
        peekAnimeGuide(info)?.let { AnimeEpisodes.overlay(extras, it) } ?: extras

    /**
     * What is already known for [query], from memory or the disk, with no lookup: how "More like
     * this" learns which title a video is without asking anyone about it.
     */
    fun cachedMatch(query: MetaQuery): MetaInfo? {
        if (!store.now.enabled) return null
        return (remembered(query.cacheKey(tmdbLanguage())) as? MetaResult.Found)?.info
    }

    /**
     * [cachedMatch] with "nothing matched" kept apart from "not asked yet": the answer kept in
     * memory or on the disk, [MetaResult.Off] while lookups are off, null when it was never asked.
     * Reads the disk: run it off the main thread.
     */
    fun cachedAnswer(query: MetaQuery): MetaResult? {
        if (!store.now.enabled) return MetaResult.Off
        return remembered(query.cacheKey(tmdbLanguage()))
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
            val full = com.tmplayer.data.VideoCorrections.queryOf(item) ?: continue
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
    suspend fun image(url: String): CachedImage? {
        cache.image(url)?.let { return it }
        if (!store.now.enabled) return null
        val host = hostOf(url) ?: return null
        if (!url.startsWith("https://") || host !in IMAGE_HOSTS) return null
        return lockFor("image|$url").withLock {
            cache.image(url)?.let { return@withLock it }
            val response = imageGate.withPermit {
                try {
                    http.send(HttpRequest("GET", url, mapOf("User-Agent" to userAgent)))
                } catch (e: IOException) {
                    null
                }
            } ?: return@withLock null
            if (response.code !in 200..299 || response.body.isEmpty()) return@withLock null
            cache.putImage(url, response.body)
        }
    }

    /**
     * Opens the connections to TMDB's API and picture hosts ahead of the first lookup, while the
     * app is still starting and Telegram connecting: the first secure connection of a process
     * (the TLS set up, the certificates read, the handshake) took over a second, and every poster
     * on Home waited behind it (2026-10-21). One small request to each host, whose answer does not
     * matter; the connection stays in the keep-alive pool for the lookups. Nothing while off.
     * The logo answers kept on the disk are read into memory first ([warmLogos]).
     */
    suspend fun warmUp() {
        warmLogos()
        if (!store.now.enabled) return
        listOf("https://api.themoviedb.org/3/", "https://image.tmdb.org/t/p/w92/").forEach { url ->
            runCatching { http.send(HttpRequest("HEAD", url, mapOf("User-Agent" to userAgent))) }
        }
    }

    /** Whether the picture at [url] is on this device already: a screen then shows it sharp at once, with no small copy first. */
    fun hasImage(url: String): Boolean = cache.image(url) != null

    // ---- title logos --------------------------------------------------------------------------

    private val logoMemory = LruMap<String, LogoAnswer>(MEMORY_ENTRIES)

    private fun logoKey(info: MetaInfo, lang: String): String =
        listOf("logo", info.provider.name, info.kind.name, info.id, lang).joinToString("|")

    /** Whether [warmLogos] has run, so it reads the disk once a run. */
    private var logosWarm = false

    /**
     * Reads the logo answers kept on this device into memory, the newest first, as many as memory
     * holds: [peekLogo] reads memory only, and on the first frame after a cold start it found
     * none, so the carousel waited on the disk for a logo it had. Called once at start, off the
     * main thread; an answer learnt meanwhile is not overwritten.
     */
    fun warmLogos() {
        if (logosWarm) return
        logosWarm = true
        runCatching { cache.logos(MEMORY_ENTRIES) }.getOrNull()?.asReversed()?.forEach { (key, answer) ->
            logoMemory.locked { if (this[key] == null) this[key] = answer }
        }
    }

    /**
     * What is known of [info]'s title logo without asking anyone: a [LogoAnswer] (its url may be
     * null, meaning the title has none), or null when it has not been looked up yet.
     */
    fun peekLogo(info: MetaInfo): LogoAnswer? {
        if (!store.now.enabled || info.provider != MetaProvider.Tmdb) return LogoAnswer(null)
        return logoMemory[logoKey(info, tmdbLanguage())]
    }

    /**
     * The address of [info]'s title logo, the film's or show's name drawn as a picture with a
     * clear ground (TMDB's `logos`), for the Home carousel to set in place of the name: in the UI
     * language, else English, else one with no words, PNG only, since an SVG would not decode.
     * One call per title, kept with the answers ([MetadataCache.logo]), a title with none
     * included. Null while off, for TVmaze and AniList (who have no logos), or when TMDB could
     * not be asked.
     */
    suspend fun logo(info: MetaInfo): String? {
        peekLogo(info)?.let { return it.url }
        val lang = tmdbLanguage()
        val key = logoKey(info, lang)
        cache.logo(key)?.let { stored ->
            logoMemory[key] = stored
            return stored.url
        }
        return lockFor(key).withLock {
            logoMemory[key]?.let { return@withLock it.url }
            if (paused(MetaProvider.Tmdb)) return@withLock null
            val s = store.now
            if (!tmdbUsable(s)) return@withLock null
            val apiKey = activeKey(s)
            val ui = lang.substringBefore('-').lowercase()
            val languages = listOf(ui, "en", "null").distinct()
            val reply = retrying { tmdb.logos(apiKey, info.kind == MetaKind.Film, info.id, languages) }
            val answer = when (trouble(MetaProvider.Tmdb, reply, apiKey)) {
                null -> LogoAnswer(MetaRules.logo((reply as MetaReply.Ok).value, ui)?.let { tmdb.logoUrl(it) })
                Outcome.Nothing -> LogoAnswer(null)
                else -> return@withLock null
            }
            cache.putLogo(key, answer)
            logoMemory[key] = answer
            answer.url
        }
    }

    // ---- the cache ----------------------------------------------------------------------------

    fun cacheBytes(): Long = cache.bytes()

    /** What the poster and backdrop pictures take, which Free up space counts with the thumbnails. */
    fun imageBytes(): Long = cache.imageBytes()

    /** The pictures, and nothing the titles were matched with. */
    fun purgeImages() {
        cache.purgeImages()
    }

    /** Every answer and picture, from the disk and from memory. */
    fun purge() {
        cache.purge()
        memory.clear()
        extrasMemory.clear()
        seasonMemory.clear()
        guideMemory.clear()
        logoMemory.clear()
        runtimeVerdicts.clear()
    }

    companion object {
        /** Set once at start by the app. Null in a context that never set it up, such as a test. */
        @Volatile
        var current: OnlineMetadata? = null

        const val ENGLISH = "en-US"
        const val REFUSED_RETRY_MS = 24L * 60 * 60 * 1000
        const val RETRY_MS = 1_000L
        /** How long a detail page waits for OMDb's scores before showing the provider's alone. */
        const val OMDB_WAIT_MS = 4_000L
        /** How long the other sites' scores are kept before a visit asks for them again: 30 days. */
        const val SCORES_KEEP_S = 30L * 24 * 60 * 60
        private const val DAY_MS = 24L * 60 * 60 * 1000
        /** Pictures fetched at once: a row's worth on screen, and few enough to leave the video its line. */
        const val IMAGE_PARALLEL = 6
        private const val MEMORY_ENTRIES = 600

        /** Pickers' lists kept for this run: a few dozen opened by hand, never a tile's. */
        private const val CHOICES_MEMORY_ENTRIES = 24

        /**
         * A screen or two of Home's tiles: each holds a cast list and a recommendation list, a few
         * kilobytes. [matched] reads the runtime from here for every tile whose year is missing,
         * and at 24 a scroll down Home and back read them all from the disk again.
         */
        private const val EXTRAS_MEMORY_ENTRIES = 96

        /** Cards' scores ([cachedCardScore]): a score or a "none" each, so thousands cost little. */
        private const val CARD_SCORE_ENTRIES = 4_096

        /** Where posters may come from. Nothing else is fetched, whatever an answer says. */
        val IMAGE_HOSTS = setOf("image.tmdb.org", "static.tvmaze.com", "s4.anilist.co", "img.anili.st", "media.kitsu.app", "media.kitsu.io", "img1.ak.crunchyroll.com")

        /** An episode number past this that a TMDB show lacks is tried as an anime numbered straight through. */
        private const val ABSOLUTE_FROM = 24

        /** Episode guides kept for this run: a few shows opened by hand, a few hundred rows each. */
        private const val GUIDE_MEMORY_ENTRIES = 8

        /** The other names an AniList match keeps (English, romaji, native, synonyms), for finding the same show elsewhere. */
        private const val ALIASES = 8

        /** A guide with this few episodes untitled is complete enough not to ask another source. */
        private const val MISSING_TITLES_OK = 2

        /** The names tried for TMDB when finding an anime's show: the file's, the provider's, then the aliases. */
        private const val TMDB_NAMES = 4

        /** Lookups are on, in a build that set the feature up. */
        val enabled: Boolean get() = current?.store?.now?.enabled == true
    }
}

/** The host of an `https://` URL, as `URI(url).host`, or null. */
internal fun hostOf(url: String): String? {
    val rest = url.substringAfter("://", "").takeIf { it.isNotEmpty() } ?: return null
    val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
    return authority.substringBefore(':').lowercase().takeIf { it.isNotEmpty() }
}

/** A video's ids on TMDB and IMDb, either unknown. */
data class VideoIds(val tmdb: String? = null, val imdb: String? = null)

private val IMDB_ID = Regex("""tt\d{5,10}""")
