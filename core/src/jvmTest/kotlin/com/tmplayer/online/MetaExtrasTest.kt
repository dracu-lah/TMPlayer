package com.tmplayer.online

import com.tmplayer.data.MediaItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The detail page's extras over a fake network: what each provider's answer is read into, the
 * rules that choose among it (rating, age rating, trailer), "More like this" narrowed to what the
 * viewer has, the cache, and nothing asked while lookups are off.
 */
class MetaExtrasTest {

    private class FakeHttp : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        val rules = mutableListOf<Pair<String, String>>()

        fun on(part: String, body: () -> String) {
            rules += part to body()
        }

        override suspend fun send(request: HttpRequest): HttpResponse {
            requests += request
            val body = rules.firstOrNull { (part, _) -> part in request.url || request.body?.contains(part) == true }?.second
                ?: return HttpResponse(404, body = "{}".toByteArray())
            return HttpResponse(200, body = body.toByteArray())
        }
    }

    private var clock = 1_800_000_000_000L
    private val http = FakeHttp()
    private val dir: File = Files.createTempDirectory("tm-extras").toFile()

    private fun metadata(enabled: Boolean = true, lang: String = "en", region: String = "GB") = OnlineMetadata(
        buildKey = "build-key",
        store = MetadataStore(File(dir, "metadata.properties")).also { s -> s.update { it.copy(enabled = enabled) } },
        cache = MetadataCache(File(dir, "cache"), now = { clock }),
        appVersion = "test",
        http = http,
        now = { clock },
        sleep = { clock += it },
        language = { lang },
        region = { region },
    )

    private val film = MetaInfo(MetaProvider.Tmdb, "10378", MetaKind.Film, "Big Buck Bunny", 2008)
    private val show = MetaInfo(MetaProvider.Tmdb, "1396", MetaKind.Show, "Breaking Bad", 2008)

    private val filmPage = """{"id":10378,"genres":[{"id":16,"name":"Animation"},{"id":35,"name":"Comedy"}],"runtime":10,
        "vote_average":6.46,"vote_count":200,
        "credits":{"cast":[{"name":"Second","character":"B","profile_path":"/b.jpg","order":1},{"name":"First","character":"A","profile_path":"/a.jpg","order":0},{"name":"Nobody","character":"","profile_path":null,"order":2}],
          "crew":[{"name":"Sacha Goedegebure","job":"Director"},{"name":"Someone","job":"Writer"}]},
        "videos":{"results":[
          {"site":"YouTube","key":"clip","type":"Clip","official":true,"iso_639_1":"en","published_at":"2020-01-01"},
          {"site":"Vimeo","key":"vimeo","type":"Trailer","official":true,"iso_639_1":"en","published_at":"2020-01-01"},
          {"site":"YouTube","key":"fan","type":"Trailer","official":false,"iso_639_1":"en","published_at":"2021-01-01"},
          {"site":"YouTube","key":"official","type":"Trailer","official":true,"iso_639_1":"en","published_at":"2009-01-01"}]},
        "release_dates":{"results":[
          {"iso_3166_1":"US","release_dates":[{"certification":"PG","type":3}]},
          {"iso_3166_1":"GB","release_dates":[{"certification":"","type":1},{"certification":"12A","type":4},{"certification":"U","type":3}]}]},
        "recommendations":{"results":[{"id":1,"title":"One"},{"id":2,"title":"Two"}]},
        "similar":{"results":[{"id":9,"title":"Nine"}]}}"""

    private val showPage = """{"id":1396,"genres":[{"name":"Drama"},{"name":"Crime"}],"episode_run_time":[],
        "last_episode_to_air":{"runtime":56},"number_of_seasons":5,"status":"Returning Series",
        "seasons":[{"season_number":0,"episode_count":9,"air_date":"2009-02-17"},{"season_number":1,"episode_count":7,"air_date":"2008-01-20"},{"season_number":2,"episode_count":13,"air_date":null}],
        "next_episode_to_air":{"season_number":3,"episode_number":1,"air_date":"2027-03-12","name":"Pilot Two"},"vote_average":8.9,"vote_count":18000,
        "created_by":[{"name":"Vince Gilligan"}],
        "aggregate_credits":{"cast":[
          {"name":"Aaron Paul","total_episode_count":62,"profile_path":"/ap.jpg","roles":[{"character":"Jesse Pinkman","episode_count":62}]},
          {"name":"Bryan Cranston","total_episode_count":63,"profile_path":"/bc.jpg","roles":[{"character":"Cameo","episode_count":1},{"character":"Walter White","episode_count":62}]}]},
        "content_ratings":{"results":[{"iso_3166_1":"DE","rating":"16"},{"iso_3166_1":"US","rating":"TV-MA"}]},
        "videos":{"results":[{"site":"YouTube","key":"bb","type":"Trailer","official":false,"iso_639_1":"en","published_at":"2024-09-17"}]},
        "recommendations":{"results":[]},
        "similar":{"results":[{"id":60059,"name":"Better Call Saul"}]}}"""

    // ---- each provider's fields -------------------------------------------------------------


    @Test
    fun aShowsSeasonsLeaveOutSpecialsAndKeepTheNextEpisode() = runBlocking {
        http.on("tv/1396") { showPage }
        val extras = metadata().extras(show) ?: error("no extras")
        assertEquals(listOf(MetaSeason(1, 7, "2008-01-20"), MetaSeason(2, 13, null)), extras.seasonList)
        assertEquals(MetaAiring(3, 1, "2027-03-12", "Pilot Two"), extras.nextEpisode)
        assertEquals(false, extras.ended)
        // And through the cache's JSON unchanged.
        assertEquals(extras, MetaExtras.fromJson(extras.toJson()))
    }

    @Test
    fun aTmdbSeasonIsListedOnceThenCameFromTheCache() = runBlocking {
        http.on("tv/1396/season/2") {
            """{"episodes":[{"episode_number":2,"name":"Grilled","air_date":"2009-03-15","runtime":48,"still_path":"/g.jpg"},
            {"episode_number":1,"name":"Seven Thirty-Seven","air_date":"2009-03-08","runtime":47,"still_path":null}]}"""
        }
        val meta = metadata()
        val first = meta.season(show, 2) ?: error("no season")
        assertEquals(listOf(1, 2), first.map { it.number })
        assertEquals("2009-03-15", first[1].airDate)
        assertEquals(48, first[1].runtimeMin)
        assertEquals("https://image.tmdb.org/t/p/w300/g.jpg", first[1].stillUrl)
        // A fresh instance reads the disk, not the network.
        val again = metadata().season(show, 2)
        assertEquals(first, again)
        assertEquals(1, http.requests.size)
    }

    @Test
    fun aTvMazeShowListsEverySeasonFromOneCall() = runBlocking {
        val tvmazeShow = MetaInfo(MetaProvider.TvMaze, "169", MetaKind.Show, "Breaking Bad")
        http.on("shows/169/episodes") {
            """[{"season":1,"number":1,"name":"Pilot","airdate":"2008-01-20","runtime":58,"image":{"medium":"https://static.tvmaze.com/e1.jpg"}},
            {"season":2,"number":1,"name":"Seven Thirty-Seven","airdate":"2009-03-08","runtime":47,"image":null}]"""
        }
        val meta = metadata()
        assertEquals(listOf("Pilot"), meta.season(tvmazeShow, 1)?.map { it.name })
        assertEquals(listOf("Seven Thirty-Seven"), meta.season(tvmazeShow, 2)?.map { it.name })
        assertEquals(1, http.requests.size)
    }

    @Test
    fun aTvMazeShowsSeasonsAndNextEpisodeComeEmbedded() = runBlocking {
        http.on("shows/170?embed") {
            """{"id":170,"genres":[],"status":"Running","_embedded":{"cast":[],
            "seasons":[{"number":1,"episodeOrder":10,"premiereDate":"2024-01-01"},{"number":2,"episodeOrder":null,"premiereDate":""}],
            "nextepisode":{"season":2,"number":4,"airdate":"2026-10-14","name":"Four"}}}"""
        }
        val extras = metadata().extras(MetaInfo(MetaProvider.TvMaze, "170", MetaKind.Show, "Something")) ?: error("no extras")
        assertTrue(http.requests.single().url, "embed%5B%5D=seasons" in http.requests.single().url)
        assertEquals(listOf(MetaSeason(1, 10, "2024-01-01"), MetaSeason(2, 0, null)), extras.seasonList)
        assertEquals(2, extras.seasons)
        assertEquals(MetaAiring(2, 4, "2026-10-14", "Four"), extras.nextEpisode)
    }
    @Test
    fun aFilmsExtrasComeFromOneTmdbCallWithEverythingAppended() = runBlocking {
        http.on("movie/10378") { filmPage }
        val extras = metadata().extras(film) ?: error("no extras")
        val sent = http.requests.single().url
        assertTrue(sent, "append_to_response=credits,videos,release_dates,recommendations,similar" in sent)
        assertTrue(sent, "include_video_language=en,null" in sent && "language=en-US" in sent)
        assertEquals(listOf("Animation", "Comedy"), extras.genres)
        assertEquals(10, extras.runtimeMin)
        assertEquals(6.5, extras.rating!!, 0.0)
        assertEquals("U", extras.certification)
        assertEquals(listOf("Sacha Goedegebure"), extras.directors)
        assertEquals(listOf("First", "Second", "Nobody"), extras.cast.map { it.name })
        assertEquals("https://image.tmdb.org/t/p/w185/a.jpg", extras.cast[0].photoUrl)
        assertEquals("A", extras.cast[0].role)
        assertNull(extras.cast[2].photoUrl)
        assertEquals("official", extras.trailer?.key)
        assertEquals("vnd.youtube:official", extras.trailer?.appUri)
        assertEquals("https://www.youtube.com/watch?v=official", extras.trailer?.watchUrl)
        assertEquals(listOf("1", "2"), extras.similar.map { it.id })
    }

    @Test
    fun aShowsExtrasReadTheAggregateCastCreatorsSeasonsAndFallBackToSimilar() = runBlocking {
        http.on("tv/1396") { showPage }
        val extras = metadata(lang = "es-ES", region = "FR").extras(show) ?: error("no extras")
        val sent = http.requests.single().url
        assertTrue(sent, "append_to_response=aggregate_credits,videos,content_ratings,recommendations,similar" in sent)
        assertTrue(sent, "include_video_language=es,en,null" in sent)
        assertEquals(56, extras.runtimeMin)
        assertEquals(5, extras.seasons)
        assertEquals(listOf("Vince Gilligan"), extras.creators)
        // No French rating: the US one.
        assertEquals("TV-MA", extras.certification)
        assertEquals(listOf("Bryan Cranston", "Aaron Paul"), extras.cast.map { it.name })
        assertEquals("Walter White", extras.cast[0].role)
        assertEquals(listOf(MetaRef(MetaProvider.Tmdb, MetaKind.Show, "60059", "Better Call Saul")), extras.similar)
        assertEquals("bb", extras.trailer?.key)
    }

    @Test
    fun aTvMazeShowGetsItsCastAndGenresFromOneCall() = runBlocking {
        http.on("shows/169?embed") {
            """{"id":169,"genres":["Drama","Crime"],"runtime":null,"averageRuntime":60,"rating":{"average":9.2},
            "_embedded":{"cast":[{"person":{"name":"Bryan Cranston","image":{"medium":"https://static.tvmaze.com/p.jpg"}},"character":{"name":"Walter White"}},
            {"person":{"name":"No Picture","image":null},"character":{"name":"Someone"}}]}}"""
        }
        val extras = metadata().extras(MetaInfo(MetaProvider.TvMaze, "169", MetaKind.Show, "Breaking Bad")) ?: error("no extras")
        assertEquals(1, http.requests.size)
        assertEquals(listOf("Drama", "Crime"), extras.genres)
        assertEquals(60, extras.runtimeMin)
        assertEquals(MetaPerson("Bryan Cranston", "Walter White", "https://static.tvmaze.com/p.jpg"), extras.cast[0])
        assertNull(extras.cast[1].photoUrl)
        assertNull("no vote count, so no rating", extras.rating)
    }

    @Test
    fun anAnimeGetsCharactersScoreTrailerAndRecommendationsFromAniList() = runBlocking {
        http.on("graphql.anilist.co") {
            """{"data":{"Media":{"genres":["Adventure","Fantasy"],"averageScore":91,"duration":24,
            "trailer":{"id":"tR8YH0G67Rk","site":"youtube"},
            "characters":{"edges":[{"node":{"name":{"full":"Frieren"},"image":{"medium":"https://s4.anilist.co/c.png"}},"voiceActors":[{"name":{"full":"Atsumi Tanezaki"}}]}]},
            "recommendations":{"nodes":[{"mediaRecommendation":{"id":21827,"format":"TV","isAdult":false,"title":{"romaji":"Violet Evergarden","english":null}}},
              {"mediaRecommendation":{"id":5,"format":"MOVIE","isAdult":true,"title":{"romaji":"x","english":"x"}}},{"mediaRecommendation":null}]}}}}"""
        }
        val extras = metadata().extras(MetaInfo(MetaProvider.AniList, "154587", MetaKind.Show, "Frieren")) ?: error("no extras")
        assertTrue(http.requests.single().body!!.contains("\"id\":154587"))
        assertEquals(listOf("Adventure", "Fantasy"), extras.genres)
        assertEquals(9.1, extras.rating!!, 0.0)
        assertEquals(24, extras.runtimeMin)
        assertEquals(MetaPerson("Frieren", "Atsumi Tanezaki", "https://s4.anilist.co/c.png"), extras.cast.single())
        assertEquals("tR8YH0G67Rk", extras.trailer?.key)
        assertEquals(listOf(MetaRef(MetaProvider.AniList, MetaKind.Show, "21827", "Violet Evergarden")), extras.similar)
    }

    // ---- the rules --------------------------------------------------------------------------

    @Test
    fun aRatingNeedsEnoughVotes() {
        assertNull("one vote of ten is not a 10.0", MetaRules.rating(10.0, 1))
        assertNull(MetaRules.rating(7.0, MetaRules.MIN_VOTES - 1))
        assertEquals(7.0, MetaRules.rating(7.0, MetaRules.MIN_VOTES)!!, 0.0)
        assertEquals(8.3, MetaRules.rating(8.26, 5000)!!, 0.0)
        assertNull("no average at all", MetaRules.rating(0.0, 5000))
    }

    @Test
    fun theAgeRatingIsTheViewersRegionsThenTheUsOnes() {
        val ratings = mapOf("us" to "PG-13", "IN" to "UA", "GB" to " ")
        assertEquals("UA", MetaRules.certification(ratings, "IN"))
        assertEquals("PG-13", MetaRules.certification(ratings, "DE"))
        assertEquals("a blank one is none", "PG-13", MetaRules.certification(ratings, "GB"))
        assertNull(MetaRules.certification(mapOf("FR" to "TP"), "DE"))
        // A film's: the theatrical release's before a digital one, whatever the order.
        assertEquals(mapOf("GB" to "U"), MetaRules.filmCertifications(mapOf("GB" to listOf("12A" to 4, "" to 1, "U" to 3))))
        assertEquals("US", MetaRules.region(java.util.Locale("en")))
        assertEquals("IN", MetaRules.region(java.util.Locale("ml", "IN")))
    }

    @Test
    fun theTrailerIsOfficialFirstThenTheUiLanguageThenEnglish() {
        fun v(key: String, official: Boolean, lang: String, type: String = "Trailer", site: String = "YouTube", at: String = "2020") =
            MetaVideo(site, key, type, official, lang, at)
        val videos = listOf(
            v("teaser-es", true, "es", type = "Teaser"),
            v("fan-es", false, "es"),
            v("official-fr", true, "fr"),
            v("official-en", true, "en"),
            v("official-es", true, "es"),
            v("vimeo-es", true, "es", site = "Vimeo"),
        )
        assertEquals("official-es", MetaRules.trailer(videos, "es-ES")?.key)
        assertEquals("official-en", MetaRules.trailer(videos, "de-DE")?.key)
        assertEquals("unofficial only: the UI language", "fan-es", MetaRules.trailer(listOf(v("fan-en", false, "en"), v("fan-es", false, "es")), "es")?.key)
        assertEquals("the newest of equals", "new", MetaRules.trailer(listOf(v("old", true, "en", at = "2010"), v("new", true, "en", at = "2024")), "en")?.key)
        assertEquals("a teaser when there is no trailer", "teaser-es", MetaRules.trailer(listOf(videos[0], videos[5]), "es")?.key)
        assertNull(MetaRules.trailer(listOf(v("clip", true, "en", type = "Clip"), videos[5]), "en"))
    }

    @Test
    fun moreLikeThisListsOnlyTitlesTheViewerHasAndAsksNobodyAboutThem() = runBlocking {
        val online = metadata()
        // What the app already matched: two films and a show, each a lookup kept in the cache.
        http.on("search/movie?query=Big+Buck+Bunny") { """{"results":[{"id":10378,"title":"Big Buck Bunny","release_date":"2008-04-10"}]}""" }
        http.on("search/movie?query=Sintel") { """{"results":[{"id":45745,"title":"Sintel","release_date":"2010-09-27","poster_path":"/s.jpg"}]}""" }
        http.on("search/movie?query=Tears+of+Steel") { """{"results":[{"id":133701,"title":"Tears of Steel","release_date":"2012-09-26"}]}""" }
        http.on("search/tv?query=Harbour+Notes") { """{"results":[{"id":555,"name":"Harbour Notes","first_air_date":"2021-03-01"}]}""" }
        fun item(id: Long, name: String) = MediaItem(
            chatId = 7, messageId = id, fileId = 0, title = name, sizeBytes = 1, durationSec = 1, mimeType = "video/mp4",
            thumbnailFileId = 0, miniThumbnail = null, date = 0, fileName = name,
        )
        val bunny = item(1, "Big.Buck.Bunny.2008.mkv")
        val sintel = item(2, "Sintel.2010.1080p.mkv")
        val tears = item(3, "Tears.of.Steel.2012.mkv")
        val harbour2 = item(4, "Harbour.Notes.S01E02.mkv")
        val harbour1 = item(5, "Harbour.Notes.S01E01.mkv")
        val homeVideo = item(6, "coast walk day 2.mp4")
        for (it in listOf(bunny, sintel, tears, harbour2)) online.lookup(MetaQuery.of(it.fileName)!!.showOnly())
        val asked = http.requests.size

        val bunnyInfo = (online.lookup(MetaQuery.of(bunny.fileName)!!) as MetaResult.Found).info
        val extras = MetaExtras(
            similar = listOf(
                MetaRef(MetaProvider.Tmdb, MetaKind.Film, "999", "Not here"),
                MetaRef(MetaProvider.Tmdb, MetaKind.Show, "45745", "A show with Sintel's id"),
                MetaRef(MetaProvider.Tmdb, MetaKind.Show, "555", "Harbour Notes"),
                MetaRef(MetaProvider.Tmdb, MetaKind.Film, "10378", "Itself"),
                MetaRef(MetaProvider.Tmdb, MetaKind.Film, "45745", "Sintel"),
            ),
        )
        val known = online.moreLikeThis(bunnyInfo, extras, listOf(bunny, homeVideo, sintel, harbour2, harbour1, tears))
        assertEquals("in the provider's order, never itself, a film id not taken for a show's", listOf("555", "45745"), known.map { it.info.id })
        assertEquals("a show opens at its first episode", harbour1, known[0].item)
        assertEquals(sintel, known[1].item)
        assertEquals("https://image.tmdb.org/t/p/w342/s.jpg", known[1].info.posterUrl)
        assertEquals("nothing new was looked up", asked, http.requests.size)

        assertTrue(MetaRules.moreLikeThis(extras.similar, bunnyInfo, emptyList()).isEmpty())
    }

    // ---- the cache and the switch -----------------------------------------------------------

    @Test
    fun extrasAreAskedForOnceThenComeFromTheCache() = runBlocking {
        http.on("movie/10378") { filmPage }
        val first = metadata().extras(film)
        metadata().extras(film)
        assertEquals(1, http.requests.size)
        // A fresh start reads them from the disk.
        val again = metadata().extras(film)
        assertEquals(1, http.requests.size)
        assertEquals(first, again)
        // Past the TTL they are asked for again.
        clock += MetadataCache.FOUND_TTL_MS + 1
        metadata().extras(film)
        assertEquals(2, http.requests.size)
    }

    @Test
    fun aNewRegionAsksAgainForItsOwnAgeRating() = runBlocking {
        http.on("movie/10378") { filmPage }
        assertEquals("U", metadata(region = "GB").extras(film)?.certification)
        assertEquals("PG", metadata(region = "US").extras(film)?.certification)
        assertEquals(2, http.requests.size)
    }

    @Test
    fun withLookupsOffNothingIsAskedAndNothingShown() = runBlocking {
        http.on("movie/10378") { filmPage }
        val online = metadata(enabled = false)
        assertNull(online.extras(film))
        assertNull(online.peekExtras(film))
        assertTrue(online.moreLikeThis(film, MetaExtras(similar = listOf(MetaRef(MetaProvider.Tmdb, MetaKind.Film, "1", "One"))), emptyList()).isEmpty())
        assertEquals(0, http.requests.size)
    }

    @Test
    fun aTitleTheProviderDoesNotKnowIsKeptAsEmpty() = runBlocking {
        val online = metadata()
        // TMDB's 404 for the title: nothing, and that is kept.
        assertEquals(MetaExtras(), online.extras(film))
        assertTrue(online.extras(film)!!.isEmpty)
        assertEquals(1, http.requests.size)
    }

    @Test
    fun extrasSurviveTheirJson() {
        val extras = MetaExtras(
            genres = listOf("Drama"), runtimeMin = 50, seasons = 2, rating = 8.1, certification = "15",
            directors = listOf("A"), creators = listOf("B"), cast = listOf(MetaPerson("C", "D", "https://image.tmdb.org/t/p/w185/c.jpg"), MetaPerson("E")),
            trailer = MetaTrailer("k"), similar = listOf(MetaRef(MetaProvider.AniList, MetaKind.Film, "3", "F")),
        )
        assertEquals(extras, MetaExtras.fromJson(extras.toJson()))
        assertEquals(MetaExtras(), MetaExtras.fromJson(MetaExtras().toJson()))
    }
}
