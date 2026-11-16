package com.tmplayer.online

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.system.measureTimeMillis

/** An IMDb or TMDB link in a file's name or caption names the title (TS): the reading, and the one call it costs. */
class CaptionLinkTest {

    private val imdb = "tt0111161"

    // ---- reading the words --------------------------------------------------------------------

    @Test
    fun `every shape of link is read`() {
        val shapes = listOf(
            "https://www.imdb.com/title/tt0111161/" to MetaLinks.Found(imdbId = imdb),
            "http://imdb.com/title/tt0111161" to MetaLinks.Found(imdbId = imdb),
            "https://m.imdb.com/en/title/tt0111161/?ref_=fn_al_tt_1" to MetaLinks.Found(imdbId = imdb),
            "IMDB.COM/TITLE/TT0111161" to MetaLinks.Found(imdbId = imdb),
            "Great film tt0111161 1080p" to MetaLinks.Found(imdbId = imdb),
            "tt0111161" to MetaLinks.Found(imdbId = imdb),
            "tt00111161" to MetaLinks.Found(imdbId = "tt00111161"),
            "https://www.themoviedb.org/movie/603-the-matrix" to MetaLinks.Found(tmdbId = 603, show = false),
            "https://www.themoviedb.org/tv/1399" to MetaLinks.Found(tmdbId = 1399, show = true),
            "see themoviedb.org/tv/1399-game and imdb.com/title/tt0944947" to MetaLinks.Found(tmdbId = 1399, show = true, imdbId = "tt0944947"),
        )
        for ((text, found) in shapes) assertEquals(text, found, MetaLinks.read(text))
    }

    @Test
    fun `a tt inside a longer word, a short or long id, a quote and two ids name nothing`() {
        val none = listOf(
            "pattt0111161x", "att0111161", "tt0111161a", "tt011116", "tt0111161234", "tt 0111161",
            "> https://www.imdb.com/title/tt0111161/\nGood film",
            "https://www.imdb.com/title/tt0111161/ and https://www.imdb.com/title/tt0068646/",
            "tt0111161 tt0068646",
            "themoviedb.org/movie/603 themoviedb.org/movie/604",
            "themoviedb.org/movie/0", "themoviedb.org/movie/99999999999", "themoviedb.org/person/603",
            "", "   ",
        )
        for (text in none) assertEquals(text, MetaLinks.Found(), MetaLinks.read(text))
        // The same id written twice, or in the name and the caption, is one id.
        assertEquals(imdb, MetaLinks.read("tt0111161 imdb.com/title/tt0111161", "tt0111161").imdbId)
        // Two different ids, one in the name and one in the caption, say nothing either.
        assertNull(MetaLinks.read("Film.tt0111161.mkv", "imdb.com/title/tt0068646").imdbId)
    }

    @Test
    fun `a link in a quote does not count but one under it does`() {
        assertEquals(imdb, MetaLinks.read("> imdb.com/title/tt0068646\nimdb.com/title/tt0111161").imdbId)
    }

    @Test
    fun `the query carries the link, a tmdb link fixes the kind, and a forced id or a pick wins`() {
        val withImdb = MetaQuery.of("big_video_720p.mkv", "https://www.imdb.com/title/tt0111161/")!!
        assertEquals(imdb, withImdb.imdbId)
        assertNull(withImdb.tmdbId)
        val show = MetaQuery.of("video.mkv", "themoviedb.org/tv/1399")!!
        assertEquals(1399, show.tmdbId)
        assertEquals(MetaKind.Show, show.kind)
        val film = MetaQuery.of("video.mkv", "themoviedb.org/movie/603")!!
        assertEquals(MetaKind.Film, film.kind)
        assertTrue(film.fromLink)
        val forced = MetaQuery.of("Film {tmdb-77}.mkv", "imdb.com/title/tt0111161")!!
        assertEquals(77, forced.tmdbId)
        assertNull(forced.imdbId)
        // A name with nothing to read but a link is still asked about.
        assertEquals(imdb, MetaQuery.of("1080p.mkv", "tt0111161")!!.imdbId)
        // No link, no change: the cache key of an ordinary name does not mention one.
        assertTrue("imdb" !in MetaQuery.of("Big.Buck.Bunny.2008.mkv")!!.cacheKey("en"))
        assertTrue("imdb:tt0111161" in withImdb.cacheKey("en"))
    }

    @Test
    fun `garbage and huge captions come back quickly`() {
        val texts = listOf(
            "tt0111161 ".repeat(200_000), "imdb.com/title/" + "t".repeat(500_000), "\u0000".repeat(100_000),
            "tt" + "9".repeat(100_000), "themoviedb.org/movie/" + "9".repeat(100_000), "> ".repeat(100_000),
        )
        // The link reader looks at the start of a text only, so its cost does not grow with the text.
        val reading = measureTimeMillis { for (text in texts) MetaLinks.read(text, text) }
        assertTrue("reading took $reading ms", reading < 500)
        // A caption of a few kilobytes goes all the way through the question without trouble.
        val asking = measureTimeMillis { for (text in texts) MetaQuery.of("x.mkv", text.take(5_000)) }
        assertTrue("asking took $asking ms", asking < 2_000)
    }

    // ---- the one call -------------------------------------------------------------------------

    private class Counting : HttpTransport {
        val urls = mutableListOf<String>()

        override suspend fun send(request: HttpRequest): HttpResponse {
            urls += request.url
            val url = request.url
            val body = when {
                "find/tt0111161" in url -> """{"movie_results":[{"id":278}],"tv_results":[]}"""
                "find/tt0944947" in url -> """{"movie_results":[],"tv_results":[{"id":1399}]}"""
                "find/tt0000000" in url -> """{"movie_results":[],"tv_results":[]}"""
                "movie/278" in url -> """{"id":278,"title":"The Shawshank Redemption","original_title":"The Shawshank Redemption","release_date":"1994-09-23","overview":"Two imprisoned men.","poster_path":"/s.jpg","backdrop_path":null,"original_language":"en"}"""
                "tv/1399" in url -> """{"id":1399,"name":"Game of Thrones","original_name":"Game of Thrones","first_air_date":"2011-04-17","overview":"Noble houses.","poster_path":"/g.jpg","backdrop_path":null,"original_language":"en"}"""
                "movie/404404" in url -> return HttpResponse(404, body = "{}".toByteArray())
                "search/movie" in url && "Big" in url -> """{"results":[{"id":10378,"title":"Big Buck Bunny","original_title":"Big Buck Bunny","release_date":"2008-04-10","overview":"A rabbit.","poster_path":"/b.jpg","backdrop_path":null}]}"""
                "search/movie" in url -> """{"results":[{"id":9,"title":"Plain Video","original_title":"Plain Video","release_date":"2020-01-01","overview":"","poster_path":"/p.jpg","backdrop_path":null}]}"""
                else -> """{"results":[]}"""
            }
            return HttpResponse(200, body = body.toByteArray())
        }
    }

    private val dir: File = Files.createTempDirectory("tm-link").toFile()
    private val http = Counting()
    private var clock = 1_800_000_000_000L

    private fun metadata(): OnlineMetadata {
        val store = MetadataStore(File(dir, "metadata.properties"))
        store.update { it.copy(enabled = true) }
        return OnlineMetadata(
            buildKey = "key", store = store, cache = MetadataCache(File(dir, "cache"), now = { clock }),
            appVersion = "test", http = http, now = { clock }, sleep = { clock += it }, language = { "en" },
        )
    }

    @Test
    fun `an IMDb id is found once and the film it names is shown with its own title`() = runBlocking {
        val online = metadata()
        val first = online.lookup(MetaQuery.of("clip_720p.mkv", "https://www.imdb.com/title/tt0111161/")!!)
        val info = (first as MetaResult.Found).info
        // The data's own title is what the page shows, so a wrong link can be seen and corrected.
        assertEquals("The Shawshank Redemption", info.title)
        // A second video of the same link: one more lookup of the title, never a second find.
        online.lookup(MetaQuery.of("other_1080p.mkv", "imdb.com/title/tt0111161")!!.copy(languages = listOf("hi")))
        assertEquals(1, http.urls.count { "find/tt0111161" in it })
        assertEquals(0, http.urls.count { "search/" in it })
    }

    @Test
    fun `a show's id gives a show, and an id nobody knows falls back to the title guess`() = runBlocking {
        val online = metadata()
        val show = online.lookup(MetaQuery.of("clip_720p.mkv", "imdb.com/title/tt0944947")!!) as MetaResult.Found
        assertEquals(MetaKind.Show, show.info.kind)
        val guess = online.lookup(MetaQuery.of("Plain.Video.2020.1080p.mkv", "imdb.com/title/tt0000000")!!) as MetaResult.Found
        assertEquals("Plain Video", guess.info.title)
        assertEquals(1, http.urls.count { "find/tt0000000" in it })
    }

    @Test
    fun `the link is what backs the match, so a file with no year still shows its poster`() = runBlocking {
        val online = metadata()
        val query = MetaQuery.of("clip_720p.mkv", "imdb.com/title/tt0111161")!!
        val info = (online.lookup(query) as MetaResult.Found).info
        assertTrue(info.linked)
        assertEquals(MatchBasis.Linked, MetaConfidence.basis(com.tmplayer.data.MediaName.parse("clip_720p.mkv"), info))
        // The flag survives the cache's JSON.
        assertTrue(MetaInfo.fromJson(info.toJson())!!.linked)
        // Without a link the same bare name has nothing to rest on.
        assertNull(MetaConfidence.basis(com.tmplayer.data.MediaName.parse("clip_720p.mkv"), info.copy(linked = false)))
    }

    @Test
    fun `a link to another year's title, or a film's link on an episode, is not taken`() = runBlocking {
        val online = metadata()
        val named = MetaQuery.of("Big.Buck.Bunny.2008.1080p.mkv", "imdb.com/title/tt0111161")!!
        val info = (online.lookup(named) as MetaResult.Found).info
        assertEquals("Big Buck Bunny", info.title)
        assertTrue(!info.linked)
        // A film's TMDB link on a file that names an episode: no link in the question at all.
        val episode = MetaQuery.of("Harbour.Notes.S01E02.mkv", "themoviedb.org/movie/278")!!
        assertNull(episode.tmdbId)
        assertTrue(!episode.fromLink)
    }

    @Test
    fun `a tmdb link to a title that does not exist falls back to the title guess`() = runBlocking {
        val online = metadata()
        val info = (online.lookup(MetaQuery.of("Plain.Video.2020.1080p.mkv", "themoviedb.org/movie/404404")!!) as MetaResult.Found).info
        assertEquals("Plain Video", info.title)
        assertTrue(!info.linked)
    }

    @Test
    fun `a file with no link makes no find call`() = runBlocking {
        val online = metadata()
        online.lookup(MetaQuery.of("Plain.Video.2020.1080p.mkv")!!)
        assertEquals(0, http.urls.count { "/find/" in it })
    }

    @Test
    fun `a tmdb link asks for that title directly and searches nothing`() = runBlocking {
        val online = metadata()
        val found = online.lookup(MetaQuery.of("whatever.mkv", "https://www.themoviedb.org/movie/278")!!) as MetaResult.Found
        assertEquals("The Shawshank Redemption", found.info.title)
        assertEquals(0, http.urls.count { "search/" in it || "/find/" in it })
    }
}
