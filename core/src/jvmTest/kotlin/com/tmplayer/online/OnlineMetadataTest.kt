package com.tmplayer.online

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The metadata rules over a fake network: matching films, shows and anime, the safe "no match",
 * a 429, a refused key, the cache and its TTL, the purge, and the language fallback. Nothing here
 * reaches the internet.
 */
class OnlineMetadataTest {

    /** Answers by the first rule whose text the URL (or the POST body) contains; records every request. */
    private class FakeHttp : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        val rules = mutableListOf<Pair<(HttpRequest) -> Boolean, (HttpRequest) -> HttpResponse>>()

        fun on(part: String, code: Int = 200, headers: Map<String, String> = emptyMap(), body: () -> String) {
            rules += { r: HttpRequest -> part in r.url || (r.body?.contains(part) == true) } to { _: HttpRequest ->
                HttpResponse(code, headers, body().toByteArray())
            }
        }

        fun count(part: String) = requests.count { part in it.url || it.body?.contains(part) == true }

        override suspend fun send(request: HttpRequest): HttpResponse {
            requests += request
            val rule = rules.firstOrNull { it.first(request) } ?: return HttpResponse(404, body = "{}".toByteArray())
            return rule.second(request)
        }
    }

    private var clock = 1_800_000_000_000L
    private val http = FakeHttp()
    private val dir: File = Files.createTempDirectory("tm-meta").toFile()

    private fun metadata(
        key: String = "build-key",
        lang: String = "en",
        enabled: Boolean = true,
        cache: MetadataCache = MetadataCache(File(dir, "cache"), now = { clock }),
    ): OnlineMetadata {
        val store = MetadataStore(File(dir, "metadata.properties"))
        store.update { it.copy(enabled = enabled) }
        return OnlineMetadata(
            buildKey = key,
            store = store,
            cache = cache,
            appVersion = "test",
            http = http,
            now = { clock },
            sleep = { clock += it },
            language = { lang },
        )
    }

    private fun query(name: String, caption: String? = null) = MetaQuery.of(name, caption) ?: error("no query for $name")

    // ---- canned answers ---------------------------------------------------------------------

    private val bunnySearch = """{"results":[{"id":10378,"title":"Big Buck Bunny","original_title":"Big Buck Bunny",
        "release_date":"2008-04-10","overview":"A giant rabbit has had enough.","poster_path":"/bbb.jpg","backdrop_path":"/bbb-wide.jpg"}]}"""

    private val harbourSearch = """{"results":[{"id":555,"name":"Harbour Notes","original_name":"Harbour Notes",
        "first_air_date":"2021-03-01","overview":"A town by the sea.","poster_path":"/hn.jpg","backdrop_path":"/hn-wide.jpg"}]}"""

    private val harbourEpisode = """{"name":"The Storm","overview":"The storm reaches the harbour.","still_path":"/hn-1-2.jpg"}"""

    private val frieren = """{"data":{"Page":{"media":[{"id":154587,"format":"TV","seasonYear":2023,
        "title":{"romaji":"Sousou no Frieren","english":"Frieren: Beyond Journey's End","native":"x"},
        "synonyms":[],"description":"An elf mage<br>outlives her friends.","coverImage":{"large":"https://s4.anilist.co/f.jpg"},
        "bannerImage":"https://s4.anilist.co/fb.jpg"}]}}}"""

    // ---- matching ---------------------------------------------------------------------------

    @Test
    fun aFilmIsMatchedByTitleAndYear() = runBlocking {
        http.on("search/movie") { bunnySearch }
        val result = metadata().lookup(query("Big.Buck.Bunny.2008.1080p.BluRay.x264.mkv"))
        val info = (result as MetaResult.Found).info
        assertEquals(MetaProvider.Tmdb, info.provider)
        assertEquals(MetaKind.Film, info.kind)
        assertEquals("Big Buck Bunny", info.title)
        assertEquals(2008, info.year)
        assertEquals("A giant rabbit has had enough.", info.overview)
        assertEquals("https://image.tmdb.org/t/p/w342/bbb.jpg", info.posterUrl)
        assertEquals("https://image.tmdb.org/t/p/w780/bbb-wide.jpg", info.backdropUrl)
        val sent = http.requests.single().url
        assertTrue(sent, "query=Big+Buck+Bunny" in sent && "year=2008" in sent && "language=en-US" in sent)
        assertTrue("the key travels as api_key", "api_key=build-key" in sent)
    }

    @Test
    fun aReadTokenTravelsAsABearerHeaderNotInTheAddress() = runBlocking {
        http.on("search/movie") { bunnySearch }
        val token = "eyJ" + "a".repeat(60)
        metadata(key = token).lookup(query("Big Buck Bunny (2008).mp4"))
        val sent = http.requests.single()
        assertFalse(token in sent.url)
        assertEquals("Bearer $token", sent.headers["Authorization"])
    }

    @Test
    fun aYearOneOffIsStillTakenAfterASecondSearchWithoutIt() = runBlocking {
        http.on("year=2009") { """{"results":[]}""" }
        http.on("search/movie") { bunnySearch }
        val result = metadata().lookup(query("Big.Buck.Bunny.2009.mkv"))
        assertTrue(result is MetaResult.Found)
        assertEquals(2, http.count("search/movie"))
    }

    @Test
    fun aSeriesEpisodeTakesTheShowAndItsEpisodeFromTmdb() = runBlocking {
        http.on("search/tv") { harbourSearch }
        http.on("tv/555/season/2/episode/3") { harbourEpisode }
        val info = (metadata().lookup(query("Harbour.Notes.S02E03.1080p.WEB-DL.mkv")) as MetaResult.Found).info
        assertEquals(MetaKind.Show, info.kind)
        assertEquals("Harbour Notes", info.title)
        assertEquals("A town by the sea.", info.overview)
        val episode = info.episode ?: error("no episode")
        assertEquals(2, episode.season)
        assertEquals(3, episode.number)
        assertEquals("The Storm", episode.name)
        assertEquals("https://image.tmdb.org/t/p/w300/hn-1-2.jpg", info.wideUrl)
    }

    @Test
    fun withoutAKeyShowsComeFromTvMazeWithTheirEpisodes() = runBlocking {
        http.on("singlesearch/shows") {
            """{"id":169,"name":"Harbour Notes","premiered":"2021-03-01","summary":"<p><b>Harbour Notes</b> is set by the sea.</p>",
            "image":{"medium":"https://static.tvmaze.com/hn.jpg"}}"""
        }
        http.on("shows/169/episodebynumber?season=1&number=2") {
            """{"name":"Low Tide","summary":"<p>The boats stay in.</p>","image":{"medium":"https://static.tvmaze.com/e.jpg"}}"""
        }
        val online = metadata(key = "")
        assertEquals(TmdbState.NoKey, online.tmdbState())
        val info = (online.lookup(query("Harbour Notes 1x02.mp4")) as MetaResult.Found).info
        assertEquals(MetaProvider.TvMaze, info.provider)
        assertEquals("Harbour Notes is set by the sea.", info.overview)
        assertEquals("Low Tide", info.episode?.name)
        assertEquals("The boats stay in.", info.episode?.overview)
        assertEquals(0, http.count("themoviedb"))
    }

    @Test
    fun animeGoesToAniListFirst() = runBlocking {
        http.on("graphql.anilist.co") { frieren }
        val q = query("[SubsPlease] Sousou no Frieren - 05 (1080p) [ABCD1234].mkv")
        assertTrue("fansub shapes read as anime", q.anime)
        val info = (metadata().lookup(q) as MetaResult.Found).info
        assertEquals(MetaProvider.AniList, info.provider)
        assertEquals("Frieren: Beyond Journey's End", info.title)
        assertEquals("An elf mage\noutlives her friends.", info.overview)
        assertEquals("https://s4.anilist.co/fb.jpg", info.wideUrl)
        assertEquals(0, http.count("themoviedb"))
        assertEquals("one POST to AniList", 1, http.requests.size)
        assertEquals("POST", http.requests.single().method)
    }

    @Test
    fun aForcedTmdbIdSkipsTheSearch() = runBlocking {
        http.on("movie/10378") { bunnySearch.substringAfter("[").substringBeforeLast("]") }
        val info = (metadata().lookup(query("my-copy {tmdb-10378}.mkv")) as MetaResult.Found).info
        assertEquals("10378", info.id)
        assertEquals(0, http.count("search"))
    }

    // ---- no match, safely ---------------------------------------------------------------------

    @Test
    fun aHomeVideoDoesNotTurnIntoSomebodysFilm() = runBlocking {
        // TMDB always finds something for common words; none of it is this video.
        http.on("search/movie") {
            """{"results":[{"id":1,"title":"The Coast","release_date":"1999-01-01","overview":"x","poster_path":"/c.jpg"},
            {"id":2,"title":"Walk the Line","release_date":"2005-01-01","overview":"y","poster_path":"/w.jpg"}]}"""
        }
        val online = metadata()
        val q = query("coast-walk-day-2-1080p.mp4")
        assertEquals(MetaResult.NoMatch, online.lookup(q))
        // Kept, so the next look costs nothing.
        assertEquals(MetaResult.NoMatch, online.lookup(q))
        assertEquals(1, http.count("search/movie"))
    }

    @Test
    fun matchingIsStrict() {
        assertTrue(MetaMatch.accepts("Big Buck Bunny", 2008, listOf("Big Buck Bunny"), 2008))
        assertTrue(MetaMatch.accepts("the office", null, listOf("The Office"), 2005))
        assertTrue("one year apart", MetaMatch.accepts("Night Train", 2019, listOf("Night Train"), 2020))
        assertFalse("years disagree", MetaMatch.accepts("Night Train", 2019, listOf("Night Train"), 1999))
        assertFalse("some words only, no year", MetaMatch.accepts("coast walk day", null, listOf("The Coast"), 1999))
        assertTrue("most words and the year", MetaMatch.accepts("Harbour Notes Revisited", 2021, listOf("Harbour Notes: Revisited"), 2021))
        assertTrue("accents", MetaMatch.accepts("Amelie", 2001, listOf("Amélie"), 2001))
        assertNull("nothing to ask", MetaQuery.of("1080p.mkv"))
    }

    @Test
    fun queriesAreReadFromTheName() {
        val film = query("Night.Train.2019.2160p.BluRay.DV.HDR10.mkv")
        assertEquals("Night Train", film.title)
        assertEquals(2019, film.year)
        assertEquals(MetaKind.Film, film.kind)
        val show = query("Harbour.Notes.S01E04.1080p.WEB-DL.mkv")
        assertEquals(MetaKind.Show, show.kind)
        assertEquals(1, show.season)
        assertEquals(4, show.episode)
        assertFalse(show.anime)
        assertEquals(MetaKind.Show, show.showOnly().kind)
        assertFalse(show.showOnly().wantsEpisode)
        val captioned = query("kitchen_journal_720p_part2.mp4", "Kitchen Journal Ep 2\nNew every Friday")
        assertEquals(MetaKind.Show, captioned.kind)
        assertEquals(10378, query("x {tmdb-10378}.mkv").tmdbId)
    }

    // ---- trouble ------------------------------------------------------------------------------

    @Test
    fun aRateLimitPausesTheProviderAndKeepsNothing() = runBlocking {
        http.on("search/movie", code = 429, headers = mapOf("Retry-After" to "5")) { "{}" }
        val online = metadata()
        val q = query("Big.Buck.Bunny.2008.mkv")
        assertEquals(MetaResult.Unavailable, online.lookup(q))
        assertEquals(1, http.count("search/movie"))
        // Within the five seconds TMDB is not asked again.
        assertEquals(MetaResult.Unavailable, online.lookup(q))
        assertEquals(1, http.count("search/movie"))
        // Afterwards it is, and the answer is kept.
        clock += 6_000
        http.rules.clear()
        http.on("search/movie") { bunnySearch }
        assertTrue(online.lookup(q) is MetaResult.Found)
        assertEquals(2, http.count("search/movie"))
    }

    @Test
    fun aRefusedKeyTurnsTmdbOffButShowsStillComeFromTvMaze() = runBlocking {
        http.on("themoviedb", code = 401) { """{"status_code":7,"status_message":"Invalid API key"}""" }
        http.on("singlesearch/shows") { """{"id":9,"name":"Harbour Notes","premiered":"2021-01-01","summary":"By the sea."}""" }
        val online = metadata()
        assertEquals(MetaResult.Unavailable, online.lookup(query("Big.Buck.Bunny.2008.mkv")))
        assertEquals(TmdbState.AppKeyRefused, online.tmdbState())
        assertEquals(L_KEY_REFUSED, MetaWords.key(online.tmdbState()))
        // Films wait for a key; TMDB is not asked again for the next one.
        val before = http.count("themoviedb")
        assertEquals(MetaResult.Unavailable, online.lookup(query("Sintel.2010.mkv")))
        assertEquals(before, http.count("themoviedb"))
        // A show still finds its poster.
        assertEquals(MetaProvider.TvMaze, (online.lookup(query("Harbour.Notes.S01E01.mkv")) as MetaResult.Found).info.provider)
        // A key of the viewer's own gets a fresh chance.
        online.setOwnKey("mine")
        assertEquals(TmdbState.OwnKey, online.tmdbState())
        // The build's key is tried again after a day.
        online.setOwnKey("")
        assertEquals(TmdbState.AppKey, online.tmdbState())
    }

    @Test
    fun aDroppedConnectionIsTriedOnceMoreThenLeftUnkept() = runBlocking {
        var calls = 0
        val flaky = object : HttpTransport {
            override suspend fun send(request: HttpRequest): HttpResponse {
                calls++
                throw java.io.IOException("connection reset")
            }
        }
        val store = MetadataStore(File(dir, "flaky.properties")).apply { update { it.copy(enabled = true) } }
        val online = OnlineMetadata("k", store, MetadataCache(File(dir, "flaky"), now = { clock }), "t", flaky, { clock }, { clock += it }, { "en" })
        assertEquals(MetaResult.Unavailable, online.lookup(query("Big.Buck.Bunny.2008.mkv")))
        assertEquals(2, calls)
        assertEquals(0L, online.cacheBytes())
    }

    @Test
    fun switchedOffNothingIsAsked() = runBlocking {
        http.on("search/movie") { bunnySearch }
        val online = metadata(enabled = false)
        assertEquals(MetaResult.Off, online.lookup(query("Big.Buck.Bunny.2008.mkv")))
        assertEquals(MetaResult.Off, online.peek(query("Big.Buck.Bunny.2008.mkv")))
        assertNull(online.image("https://image.tmdb.org/t/p/w342/bbb.jpg"))
        assertTrue(http.requests.isEmpty())
    }

    // ---- the cache ------------------------------------------------------------------------------

    @Test
    fun aSecondLookupIsACacheHitEvenAfterARestart() = runBlocking {
        http.on("search/movie") { bunnySearch }
        val q = query("Big.Buck.Bunny.2008.mkv")
        assertTrue(metadata().lookup(q) is MetaResult.Found)
        val restarted = metadata()
        assertNull("memory starts empty", restarted.peek(q))
        assertTrue(restarted.lookup(q) is MetaResult.Found)
        assertTrue("then it is in memory", restarted.peek(q) is MetaResult.Found)
        assertEquals(1, http.count("search/movie"))
    }

    @Test
    fun episodesOfOneShowShareItsLookup() = runBlocking {
        http.on("search/tv") { harbourSearch }
        http.on("/episode/") { harbourEpisode }
        val online = metadata()
        for (n in 1..4) online.lookup(query("Harbour.Notes.S01E0$n.mkv"))
        assertEquals(1, http.count("search/tv"))
        assertEquals(4, http.count("/episode/"))
    }

    @Test
    fun entriesExpireAfterTheirTtl() = runBlocking {
        http.on("search/movie") { bunnySearch }
        val q = query("Big.Buck.Bunny.2008.mkv")
        metadata().lookup(q)
        clock += MetadataCache.FOUND_TTL_MS + 1
        metadata().lookup(q)
        assertEquals("asked again once stale", 2, http.count("search/movie"))

        http.rules.clear()
        http.on("search/movie") { """{"results":[]}""" }
        val miss = query("Nothing.Like.This.2001.mkv")
        metadata().lookup(miss)
        clock += MetadataCache.MISS_TTL_MS - 1
        metadata().lookup(miss)
        val asked = http.count("Nothing")
        clock += 2
        metadata().lookup(miss)
        assertTrue("a miss is asked again after a week", http.count("Nothing") > asked)
    }

    @Test
    fun noTtlMayPassSixMonths() {
        try {
            MetadataCache(dir, foundTtlMs = MetadataCache.MAX_TTL_MS + 1)
            fail("a TTL past six months was accepted")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun purgeEmptiesTheDiskAndMemory() = runBlocking {
        http.on("search/movie") { bunnySearch }
        http.on("image.tmdb.org") { "jpeg bytes" }
        val online = metadata()
        val q = query("Big.Buck.Bunny.2008.mkv")
        val info = (online.lookup(q) as MetaResult.Found).info
        val poster = online.image(info.posterUrl!!)
        assertNotNull(poster)
        assertEquals("jpeg bytes", poster!!.readText())
        online.image(info.posterUrl!!)
        assertEquals("a poster is fetched once", 1, http.count("image.tmdb.org"))
        assertTrue(online.cacheBytes() > 0)
        online.purge()
        assertEquals(0L, online.cacheBytes())
        assertNull(online.peek(q))
        online.lookup(q)
        assertEquals(2, http.count("search/movie"))
    }

    @Test
    fun picturesComeOnlyFromTheProvidersOwnHosts() = runBlocking {
        http.on("evil.example") { "x" }
        val online = metadata()
        assertNull(online.image("https://evil.example/p.jpg"))
        assertNull(online.image("http://image.tmdb.org/t/p/w342/p.jpg"))
        assertTrue(http.requests.isEmpty())
    }

    // ---- language -------------------------------------------------------------------------------

    @Test
    fun anOverviewMissingInTheUiLanguageFallsBackToEnglish() = runBlocking {
        http.on("search/movie") {
            bunnySearch.replace("A giant rabbit has had enough.", "")
        }
        http.on("movie/10378?language=en-US") { """{"id":10378,"title":"Big Buck Bunny","overview":"In English."}""" }
        val info = (metadata(lang = "ml").lookup(query("Big.Buck.Bunny.2008.mkv")) as MetaResult.Found).info
        assertEquals("In English.", info.overview)
        assertEquals("en-US", info.language)
        assertTrue("asked in Malayalam first", http.requests.first().url.contains("language=ml"))
    }

    @Test
    fun anOverviewInTheUiLanguageIsKept() = runBlocking {
        http.on("search/movie") { bunnySearch.replace("A giant rabbit has had enough.", "Un conejo gigante.") }
        val info = (metadata(lang = "es").lookup(query("Big.Buck.Bunny.2008.mkv")) as MetaResult.Found).info
        assertEquals("Un conejo gigante.", info.overview)
        assertEquals("es", info.language)
        assertEquals(1, http.requests.size)
    }

    @Test
    fun anEpisodeMissingInTheUiLanguageFallsBackToEnglish() = runBlocking {
        http.on("search/tv") { harbourSearch }
        http.on("episode/1?language=pt-BR") { """{"name":"","overview":"","still_path":null}""" }
        http.on("episode/1?language=en-US") { harbourEpisode }
        val info = (metadata(lang = "pt-BR").lookup(query("Harbour.Notes.S01E01.mkv")) as MetaResult.Found).info
        assertEquals("The Storm", info.episode?.name)
        assertEquals("The storm reaches the harbour.", info.episode?.overview)
    }

    @Test
    fun pseudoLocalesAskInEnglish() {
        val online = metadata()
        assertEquals("en-US", online.tmdbLanguage("en-XA"))
        assertEquals("en-US", online.tmdbLanguage(""))
        assertEquals("ml", online.tmdbLanguage("ml"))
        assertEquals("pt-BR", online.tmdbLanguage("pt-BR"))
    }

    @Test
    fun answersAreKeptPerLanguage() = runBlocking {
        http.on("search/movie") { bunnySearch }
        metadata(lang = "en").lookup(query("Big.Buck.Bunny.2008.mkv"))
        metadata(lang = "es").lookup(query("Big.Buck.Bunny.2008.mkv"))
        assertEquals(2, http.count("search/movie"))
    }

    private companion object {
        val L_KEY_REFUSED: String get() = com.tmplayer.i18n.L.metadataKeyAppRefused
    }
}
