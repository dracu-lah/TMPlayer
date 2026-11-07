package com.tmplayer.online

import com.tmplayer.data.MediaItem
import com.tmplayer.data.PosterPick
import com.tmplayer.data.VideoCorrection
import com.tmplayer.data.VideoCorrections
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** "Choose the right poster": the candidate list, its states, and a pick forcing the match. */
class PosterChoicesTest {

    private class FakeHttp : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        val rules = mutableListOf<Pair<String, HttpResponse>>()
        var failure: Exception? = null

        fun on(part: String, code: Int = 200, headers: Map<String, String> = emptyMap(), body: String = "{}") {
            rules += part to HttpResponse(code, headers, body.toByteArray())
        }

        override suspend fun send(request: HttpRequest): HttpResponse {
            requests += request
            failure?.let { throw it }
            return rules.firstOrNull { it.first in request.url }?.second ?: HttpResponse(404, body = "{}".toByteArray())
        }
    }

    private var clock = 1_800_000_000_000L
    private val http = FakeHttp()
    private val dir: File = Files.createTempDirectory("tm-choices").toFile()

    @After
    fun forget() = VideoCorrections.set(emptyMap())

    private fun metadata(key: String = "build-key", enabled: Boolean = true): OnlineMetadata {
        val store = MetadataStore(File(dir, "metadata.properties"))
        store.update { it.copy(enabled = enabled) }
        return OnlineMetadata(key, store, MetadataCache(File(dir, "cache"), now = { clock }), "test", http, { clock }, { clock += it }, { "en" })
    }

    private fun query(name: String) = MetaQuery.of(name) ?: error("no query for $name")

    private val films = """{"results":[
        {"id":1,"title":"Idiot","original_title":"Blbec","release_date":"2022-05-01","original_language":"cs","overview":"A Czech comedy.","poster_path":"/cz.jpg"},
        {"id":2,"title":"Idiot","original_title":"Idiot","release_date":"2021-12-30","original_language":"ta","overview":"A Tamil horror comedy.","poster_path":"/ta.jpg"},
        {"id":3,"title":"The Idiot","original_title":"Idiot","release_date":"1951-01-01","original_language":"ja","overview":"","poster_path":null}]}"""

    private val shows = """{"results":[
        {"id":9,"name":"Idiot","original_name":"Idiot","first_air_date":"2019-01-01","original_language":"en","overview":"A series.","poster_path":"/tv.jpg"},
        {"id":2,"name":"Duplicate of a film id","original_name":"x","first_air_date":"2000-01-01"}]}"""

    @Test
    fun filmsAndShowsWithAndWithoutTheYearAreListedOnceEachClosestFirst() = runBlocking {
        http.on("search/movie", body = films)
        http.on("search/tv", body = shows)
        val result = metadata().candidates(query("Idiot.2022.1080p.WEB-DL.Tamil.mkv")) as PosterChoices.Found
        // The two searches per kind: with the year and without it.
        assertEquals(2, http.requests.count { "search/movie" in it.url })
        assertEquals(2, http.requests.count { "search/tv" in it.url })
        // Same words and year first, then the Tamil one a year off (its language is in the name),
        // then the old film, then the show, then a title sharing only an id with a film.
        assertEquals(listOf("f1", "f2", "f3", "s9", "s2"), result.choices.map { (if (it.show) "s" else "f") + it.tmdbId })
        val first = result.choices.first()
        assertEquals(1, first.tmdbId)
        assertEquals("cs", first.originalLanguage)
        assertEquals(2022, first.year)
        assertEquals("https://image.tmdb.org/t/p/w342/cz.jpg", first.posterUrl)
        assertEquals(PosterPick(1, show = false), first.pick)
        assertTrue(result.choices.size <= PosterChoiceRules.LIMIT)
    }

    @Test
    fun theRightTitleIsThereWhenItsYearDiffers() = runBlocking {
        // TMDB filters by the year it is given: only the search without it has the Tamil film (2021).
        http.on("year=2022", body = """{"results":[]}""")
        http.on("search/movie", body = films)
        http.on("search/tv", body = """{"results":[]}""")
        val result = metadata().candidates(query("Idiot.2022.mkv")) as PosterChoices.Found
        assertTrue(result.choices.any { it.tmdbId == 2 && it.originalLanguage == "ta" })
    }

    @Test
    fun aLanguageTheNameListsBreaksATieBetweenTwoTitlesOfOneYear() {
        val tie = """{"results":[
            {"id":10,"title":"Governor","release_date":"2026-02-01","original_language":"en"},
            {"id":11,"title":"Governor","release_date":"2026-06-01","original_language":"hi"}]}"""
        val candidates = com.tmplayer.json.JSONObject(tie).let { json ->
            val list = json.getJSONArray("results")
            (0 until list.length()).map { i ->
                val o = list.getJSONObject(i)
                MetaCandidate(o.get("id").toString(), listOf(o.getString("title")), o.getString("release_date").take(4).toInt(), "", null, null, originalLanguage = o.getString("original_language"))
            }
        }
        val merged = PosterChoiceRules.merge(query("Governor (2026) Hindi 1080p.mkv"), listOf(true to candidates))
        assertEquals(listOf(11, 10), merged.map { it.tmdbId })
    }

    @Test
    fun mergingKeepsTenAtMostDropsBadIdsAndTrimsTheOverview() {
        val many = (1..30).map { MetaCandidate("$it", listOf("Thing $it"), 2020, "x ".repeat(400), null, null) }
        val bad = listOf(MetaCandidate("abc", listOf("Nope"), 2020, "", null, null), MetaCandidate("0", listOf("Zero"), 2020, "", null, null), MetaCandidate("77", listOf("", " "), 2020, "", null, null))
        val merged = PosterChoiceRules.merge(query("Thing 2020.mkv"), listOf(true to bad + many))
        assertEquals(PosterChoiceRules.LIMIT, merged.size)
        assertTrue(merged.none { it.title.isBlank() || it.tmdbId <= 0 })
        assertTrue(merged.all { it.overview.length <= PosterChoiceRules.OVERVIEW_MAX })
    }

    @Test
    fun theTitleBeingShownIsMarked() {
        val choice = PosterChoice(2, show = false, title = "Idiot", year = 2021, originalLanguage = "ta", overview = "", posterUrl = null)
        val info = MetaInfo(MetaProvider.Tmdb, "2", MetaKind.Film, "Idiot")
        assertTrue(choice.isMatch(info))
        assertFalse(choice.isMatch(info.copy(kind = MetaKind.Show)))
        assertFalse(choice.isMatch(info.copy(id = "3")))
        assertFalse(choice.isMatch(info.copy(provider = MetaProvider.TvMaze)))
        assertFalse(choice.isMatch(null))
    }

    // ---- states ---------------------------------------------------------------------------

    @Test
    fun switchedOffNoKeyAndNoNameEachAnswerWithoutAsking() = runBlocking {
        assertEquals(PosterChoices.Off, metadata(enabled = false).candidates(query("Big.Buck.Bunny.2008.mkv")))
        assertEquals(PosterChoices.NoKey, metadata(key = "").candidates(query("Big.Buck.Bunny.2008.mkv")))
        assertEquals(PosterChoices.Found(emptyList()), metadata().candidates(MetaQuery(title = "  ")))
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun offlineAndAServerErrorAreUnavailableNotAnEmptyList() = runBlocking {
        http.failure = java.io.IOException("offline")
        val online = metadata()
        assertEquals(PosterChoices.Unavailable, online.candidates(query("Big.Buck.Bunny.2008.mkv")))
        // Once the connection is back the same ask works: nothing wrong was kept.
        http.failure = null
        http.rules.clear()
        http.on("search/movie", body = films)
        http.on("search/tv", body = shows)
        assertTrue(online.candidates(query("Big.Buck.Bunny.2008.mkv")) is PosterChoices.Found)
    }

    @Test
    fun aThrottlePausesAskingAndIsUnavailable() = runBlocking {
        http.on("search/", code = 429, headers = mapOf("Retry-After" to "5"))
        val online = metadata()
        assertEquals(PosterChoices.Unavailable, online.candidates(query("Big.Buck.Bunny.2008.mkv")))
        val asked = http.requests.size
        assertEquals(PosterChoices.Unavailable, online.candidates(query("Big.Buck.Bunny.2008.mkv")))
        assertEquals("paused: not asked again", asked, http.requests.size)
    }

    @Test
    fun aRefusedKeyIsItsOwnMessage() = runBlocking {
        http.on("themoviedb", code = 401)
        val online = metadata()
        assertEquals(PosterChoices.NoKey, online.candidates(query("Big.Buck.Bunny.2008.mkv")))
    }

    @Test
    fun aListIsKeptInMemoryForTheRunOnly() = runBlocking {
        http.on("search/movie", body = films)
        http.on("search/tv", body = shows)
        val online = metadata()
        online.candidates(query("Idiot.2022.mkv"))
        val asked = http.requests.size
        online.candidates(query("Idiot.2022.mkv"))
        assertEquals(asked, http.requests.size)
        // Nothing reached the disk cache.
        assertEquals(0L, online.cacheBytes())
        // A new run asks again.
        metadata().candidates(query("Idiot.2022.mkv"))
        assertTrue(http.requests.size > asked)
    }

    // ---- the pick forces the match ----------------------------------------------------------

    @Test
    fun aPickAsksForItsIdAndItsKindWhateverTheNameSays() = runBlocking {
        http.on("movie/2", body = """{"id":2,"title":"Idiot","original_title":"Idiot","release_date":"2021-12-30","original_language":"ta","overview":"Tamil.","poster_path":"/ta.jpg"}""")
        val item = item("Idiot.2022.1080p.mkv")
        val pick = PosterPick(2, show = false)
        VideoCorrections.set(mapOf(item.id to VideoCorrection(pick = pick)))
        val forced = VideoCorrections.queryOf(item) ?: error("no query")
        assertEquals(2, forced.tmdbId)
        assertEquals(MetaKind.Film, forced.kind)
        assertEquals(2022, forced.year)
        val online = metadata()
        val info = (online.lookup(forced) as MetaResult.Found).info
        assertEquals("2", info.id)
        assertEquals(0, http.requests.count { "search" in it.url })
        // The year (2022 against 2021) would not back it; the viewer's word does.
        assertEquals(MatchBasis.Chosen, online.peekMatched(item, item.parsedName, info)?.basis)
        assertEquals(MatchBasis.Chosen, online.matched(item, item.parsedName, info)?.basis)
        // Another title for the same file is not covered by the pick.
        assertFalse(online.peekMatched(item, item.parsedName, info.copy(id = "5"))?.basis == MatchBasis.Chosen)
    }

    @Test
    fun aPickForAShowKeepsTheEpisodeAndAFilmPickDropsIt() {
        val episode = item("Harbour.Notes.S02E03.mkv")
        val show = VideoCorrections.queryOf(episode, all = mapOf(episode.id to VideoCorrection(pick = PosterPick(555, show = true)))) ?: error("none")
        assertEquals(MetaKind.Show, show.kind)
        assertEquals(555, show.tmdbId)
        assertEquals(2, show.season)
        assertEquals(3, show.episode)
        val film = VideoCorrections.queryOf(episode, all = mapOf(episode.id to VideoCorrection(pick = PosterPick(9, show = false)))) ?: error("none")
        assertEquals(MetaKind.Film, film.kind)
        assertNull(film.episode)
        // No pick: the item's own question, untouched.
        assertEquals(episode.metaQuery, VideoCorrections.queryOf(episode, all = emptyMap()))
    }

    @Test
    fun aPickHoldsForAFileWhoseNameAsksNothing() {
        val odd = item("1080p.x264.mkv")
        assertNull(odd.metaQuery)
        val query = VideoCorrections.queryOf(odd, all = mapOf(odd.id to VideoCorrection(pick = PosterPick(7)))) ?: error("none")
        assertEquals(7, query.tmdbId)
    }

    @Test
    fun aRemovedTitleIsNoMatchSoTheFileKeepsItsOwnPicture() = runBlocking {
        // TMDB answers 404 for an id it has since removed.
        http.on("movie/404", code = 404)
        val item = item("Idiot.2022.mkv")
        VideoCorrections.set(mapOf(item.id to VideoCorrection(pick = PosterPick(404))))
        val result = metadata().lookup(VideoCorrections.queryOf(item) ?: error("none"))
        assertEquals(MetaResult.NoMatch, result)
    }

    private var nextId = 1L

    private fun item(fileName: String) = MediaItem(
        chatId = 7, messageId = nextId++, fileId = 1, title = fileName, sizeBytes = 1, durationSec = 5400, mimeType = "video/x-matroska",
        thumbnailFileId = 0, miniThumbnail = null, date = 1, fileName = fileName, caption = "",
    )
}
