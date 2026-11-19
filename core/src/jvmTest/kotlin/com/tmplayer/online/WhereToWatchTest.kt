package com.tmplayer.online

import com.tmplayer.json.JSONObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** HX41's "Where to watch": TMDB's legal streaming and rental list for the viewer's region, names only. */
class WhereToWatchTest {

    private class FakeTmdb : HttpTransport {
        val requests = mutableListOf<String>()
        var answer: () -> HttpResponse = { HttpResponse(200, body = "{}".toByteArray()) }
        override suspend fun send(request: HttpRequest): HttpResponse {
            requests += request.url
            return answer()
        }
    }

    private val http = FakeTmdb()

    private fun metadata(key: String = "key", enabled: Boolean = true, region: String = "GB"): OnlineMetadata {
        val dir: File = Files.createTempDirectory("tm-watch").toFile()
        val store = MetadataStore(File(dir, "metadata.properties"))
        store.update { it.copy(enabled = enabled) }
        var clock = 1_800_000_000_000L
        return OnlineMetadata(key, store, MetadataCache(File(dir, "cache"), now = { clock }), "test", http, { clock }, { clock += it }, { "en" }, { region })
    }

    private val film = PosterChoice(7, show = false, title = "Night Train", year = 2019, originalLanguage = "en", overview = "", posterUrl = null)

    private val body = """{"id":7,"results":{
        "GB":{"link":"https://www.themoviedb.org/movie/7/watch","flatrate":[{"provider_name":"Stream Two","display_priority":5},{"provider_name":"Stream One","display_priority":1}],
              "rent":[{"provider_name":"Rent Shop"}],"buy":[{"provider_name":"Rent Shop"},{"provider_name":"  "}],"ads":[{"provider_name":"Free Tv"}]},
        "US":{"flatrate":[{"provider_name":"Elsewhere"}]}}}"""

    @Test
    fun theRegionsServicesAreListedByKindInTmdbsOrderNamesOnly() = runBlocking {
        http.answer = { HttpResponse(200, body = body.toByteArray()) }
        val found = metadata().whereToWatch(film) as WhereToWatch.Found
        assertEquals("GB", found.offers.region)
        assertEquals(listOf("Stream One", "Stream Two"), found.offers.stream)
        assertEquals(listOf("Rent Shop"), found.offers.rent)
        assertEquals(listOf("Rent Shop"), found.offers.buy)
        assertEquals(listOf("Free Tv"), found.offers.free)
        assertTrue(http.requests.single().contains("movie/7/watch/providers"))
    }

    @Test
    fun aShowAsksTheTvListAndASecondAskIsFromMemory() = runBlocking {
        http.answer = { HttpResponse(200, body = body.toByteArray()) }
        val meta = metadata()
        val show = film.copy(show = true)
        meta.whereToWatch(show)
        meta.whereToWatch(show)
        assertEquals(1, http.requests.size)
        assertTrue(http.requests.single().contains("tv/7/watch/providers"))
    }

    @Test
    fun aRegionWithNothingListedSaysSo() = runBlocking {
        http.answer = { HttpResponse(200, body = body.toByteArray()) }
        assertEquals(WhereToWatch.NoneHere("IN"), metadata(region = "IN").whereToWatch(film))
        http.answer = { HttpResponse(404, body = "{}".toByteArray()) }
        assertEquals(WhereToWatch.NoneHere("GB"), metadata().whereToWatch(film))
    }

    @Test
    fun noKeyOffOfflineAndThrottledAreTheirOwnAnswers() = runBlocking {
        assertEquals(WhereToWatch.NoKey, metadata(key = "").whereToWatch(film))
        assertEquals(WhereToWatch.Off, metadata(enabled = false).whereToWatch(film))
        assertTrue(http.requests.isEmpty())
        http.answer = { throw com.tmplayer.platform.IOException("offline") }
        assertEquals(WhereToWatch.Unavailable, metadata().whereToWatch(film))
        http.answer = { HttpResponse(429, headers = mapOf("Retry-After" to "5"), body = ByteArray(0)) }
        assertEquals(WhereToWatch.Unavailable, metadata().whereToWatch(film))
        http.answer = { HttpResponse(401, body = ByteArray(0)) }
        assertEquals(WhereToWatch.NoKey, metadata().whereToWatch(film))
    }

    @Test
    fun aHugeOrWrongTypedListIsBounded() {
        val many = (1..500).joinToString(",") { """{"provider_name":"Service $it ${"x".repeat(200)}","display_priority":$it}""" }
        val offers = WatchOfferRules.read(JSONObject("""{"results":{"GB":{"flatrate":[$many],"rent":"nope","buy":[1,2,null]}}}"""), "gb")
        assertEquals(WatchOfferRules.PER_KIND, offers.stream.size)
        assertTrue(offers.stream.all { it.length <= WatchOfferRules.NAME_MAX })
        assertTrue(offers.rent.isEmpty() && offers.buy.isEmpty())
        // A region that is no region code falls back to the US list.
        assertEquals("US", WatchOfferRules.read(JSONObject("{}"), "not a region").region)
    }
}
