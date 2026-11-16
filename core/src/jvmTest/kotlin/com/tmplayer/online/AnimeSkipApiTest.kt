package com.tmplayer.online

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimeSkipApiTest {

    private class FakeHttp(private val code: Int, private val body: String, private val headers: Map<String, String> = emptyMap()) : HttpTransport {
        val asked = mutableListOf<HttpRequest>()
        override suspend fun send(request: HttpRequest): HttpResponse {
            asked += request
            return HttpResponse(code, headers, body.toByteArray())
        }
    }

    private val limiter = RateLimiter(0, { 0L }, {})

    /** A fake client id: the real one is the user's to register (see [SkipSources]). */
    private fun api(http: HttpTransport, id: String = "test-client", now: () -> Long = { 0L }) = AnimeSkipApi(http, limiter, "test", id, now)

    private val show = """
        {"data":{"findShowsByExternalId":[{"episodes":[
          {"number":null,"absoluteNumber":null,"baseDuration":1469.4,"timestamps":[]},
          {"number":"1","absoluteNumber":null,"baseDuration":1447,"timestamps":[]},
          {"number":"2","absoluteNumber":null,"baseDuration":1450.9,"timestamps":[
             {"at":141,"type":{"name":"Canon"}},{"at":43,"type":{"name":"Intro"}},{"at":1380,"type":{"name":"Credits"}},{"at":10,"type":{"name":"Recap"}}]},
          {"number":"3","absoluteNumber":"27","baseDuration":1435.1,"timestamps":[
             {"at":0,"type":{"name":"New Intro"}},{"at":97,"type":{"name":"Title Card"}},{"at":1300,"type":{"name":"Mixed Credits"}},{"at":1390,"type":{"name":"Preview"}}]},
          {"number":"9","absoluteNumber":null,"baseDuration":1447,"timestamps":[{"at":820,"type":{"name":"Intro"}}]}
        ]}]}}
    """.trimIndent()

    @Test
    fun `a blank client id asks nobody anything`() = runBlocking {
        val http = FakeHttp(200, show)
        val reply = api(http, id = "").segments("16498", 2, 0) as MetaReply.Ok
        assertEquals(emptyList<SkipSegment>(), reply.value)
        assertTrue(http.asked.isEmpty())
        assertEquals(false, api(http, id = "  ").configured)
    }

    @Test
    fun `the query sends the client id, the AniList id and nothing else`() = runBlocking {
        val http = FakeHttp(200, show)
        api(http).segments("16498", 2, 1450)
        val request = http.asked.single()
        assertEquals("POST", request.method)
        assertEquals("test-client", request.headers["X-Client-ID"])
        assertTrue(request.body!!.contains("\"id\":\"16498\""))
        assertTrue(request.body!!.contains("findShowsByExternalId"))
        // No title, no file name: the body is the query and the one variable.
        com.tmplayer.json.JSONObject(request.body!!).let { assertTrue(it.has("query") && it.has("variables") && it.length() == 2) }
    }

    @Test
    fun `timestamps are points that run to the next one and map to skip kinds`() = runBlocking {
        val two = api(FakeHttp(200, show)).segments("16498", 2, 1450) as MetaReply.Ok
        assertEquals(
            listOf(
                SkipSegment(SkipKind.Recap, 10_000, 43_000),
                SkipSegment(SkipKind.Opening, 43_000, 141_000),
                SkipSegment(SkipKind.Ending, 1_380_000, null),
            ),
            two.value,
        )
        val three = api(FakeHttp(200, show)).segments("16498", 3, 0) as MetaReply.Ok
        assertEquals(
            listOf(
                SkipSegment(SkipKind.Opening, 0, 97_000),
                SkipSegment(SkipKind.Ending, 1_300_000, 1_390_000),
            ),
            // The preview closes the credits and has no end of its own: a half filled entry, dropped.
            three.value,
        )
    }

    @Test
    fun `an episode is found by absolute number when the number does not match, and an intro with no end is dropped`() = runBlocking {
        val byAbsolute = api(FakeHttp(200, show)).segments("16498", 27, 0) as MetaReply.Ok
        assertEquals(2, byAbsolute.value.size)
        assertEquals(emptyList<SkipSegment>(), (api(FakeHttp(200, show)).segments("16498", 9, 0) as MetaReply.Ok).value)
        assertEquals(emptyList<SkipSegment>(), (api(FakeHttp(200, show)).segments("16498", 99, 0) as MetaReply.Ok).value)
    }

    @Test
    fun `a cut of another length does not get the times`() = runBlocking {
        val shorter = api(FakeHttp(200, show)).segments("16498", 2, 1300) as MetaReply.Ok
        assertEquals(emptyList<SkipSegment>(), shorter.value)
        val close = api(FakeHttp(200, show)).segments("16498", 2, 1420) as MetaReply.Ok
        assertEquals(3, close.value.size)
    }

    @Test
    fun `a whole show is one question for ten minutes`() = runBlocking {
        var clock = 0L
        val http = FakeHttp(200, show)
        val api = api(http) { clock }
        api.segments("16498", 2, 0)
        api.segments("16498", 3, 0)
        clock += 9 * 60_000
        api.segments("16498", 2, 0)
        assertEquals(1, http.asked.size)
        clock += 2 * 60_000
        api.segments("16498", 2, 0)
        assertEquals(2, http.asked.size)
    }

    @Test
    fun `404, an empty show and no data are nothing timed, not a failure`() = runBlocking {
        for ((code, body) in listOf(404 to "{}", 200 to """{"data":{"findShowsByExternalId":[]}}""", 200 to """{"data":{"findShowsByExternalId":[{"episodes":[]}]}}""", 200 to """{"data":{}}""")) {
            val reply = api(FakeHttp(code, body)).segments("1", 1, 0)
            assertEquals("$code $body", MetaReply.Ok(emptyList<SkipSegment>()), reply)
        }
    }

    @Test
    fun `the 403 a client over its limit gets is a wait, a refused id is not`() = runBlocking {
        val limited = api(FakeHttp(403, """{"errors":[{"message":"Rate limit exceeded"}]}""")).segments("1", 1, 0)
        assertEquals(MetaReply.Throttled(AnimeSkipApi.RATE_WAIT_MS), limited)
        assertEquals(MetaReply.Throttled(5_000), api(FakeHttp(429, "", mapOf("Retry-After" to "5"))).segments("1", 1, 0))
        assertEquals(MetaReply.Refused, api(FakeHttp(403, """{"errors":[{"message":"Invalid client"}]}""")).segments("1", 1, 0))
        assertEquals(MetaReply.Failed(500), api(FakeHttp(500, "{}")).segments("1", 1, 0))
        assertEquals(MetaReply.Failed(200), api(FakeHttp(200, """{"errors":[{"message":"boom"}]}""")).segments("1", 1, 0))
        assertEquals(MetaReply.Failed(0), api(HttpTransport { throw com.tmplayer.platform.IOException("offline") }).segments("1", 1, 0))
    }

    @Test
    fun `an id that is not digits is never sent`() = runBlocking {
        val http = FakeHttp(200, show)
        for (bad in listOf("", "abc", "1\"} x", "1".repeat(40), "-1")) {
            assertEquals(MetaReply.Ok(emptyList<SkipSegment>()), api(http).segments(bad, 1, 0))
        }
        assertTrue(http.asked.isEmpty())
    }

    @Test
    fun `a huge answer is read without choking and a huge one past the cap is a failure`() = runBlocking {
        val episodes = (1..8_000).joinToString(",") {
            """{"number":"$it","baseDuration":1400,"timestamps":[{"at":10,"type":{"name":"Intro"}},{"at":100,"type":{"name":"Canon"}}]}"""
        }
        val reply = api(FakeHttp(200, """{"data":{"findShowsByExternalId":[{"episodes":[$episodes]}]}}""")).segments("1", 7_000, 0)
        // Past the read cap of 6,000 episodes: nothing for that one, no crash.
        assertEquals(MetaReply.Ok(emptyList<SkipSegment>()), reply)
        assertEquals(1, (api(FakeHttp(200, """{"data":{"findShowsByExternalId":[{"episodes":[$episodes]}]}}""")).segments("1", 5_000, 0) as MetaReply.Ok).value.count { it.kind == SkipKind.Opening })
    }

    @Test
    fun `their types map to our kinds and the rest only end a stretch`() {
        assertEquals(SkipKind.Opening, AnimeSkipApi.kindOf("Mixed Intro"))
        assertEquals(SkipKind.Opening, AnimeSkipApi.kindOf(" new intro "))
        assertEquals(SkipKind.Ending, AnimeSkipApi.kindOf("New Credits"))
        assertEquals(SkipKind.Recap, AnimeSkipApi.kindOf("RECAP"))
        assertEquals(SkipKind.Preview, AnimeSkipApi.kindOf("Preview"))
        for (other in listOf("Canon", "Filler", "Title Card", "Branding", "Transition", "Must Watch", "Unknown", "", null, "Intro2")) {
            assertEquals(null, AnimeSkipApi.kindOf(other))
        }
    }

    // ---- the id fallback ------------------------------------------------------------------

    @Test
    fun `the id mapping gives the other ids from an AniList id and sends only that id`() = runBlocking {
        val http = FakeHttp(200, """{"anidb":23,"anilist":1,"myanimelist":1,"imdb":"tt0213338","themoviedb":30991,"thetvdb":76885,"media":"TV"}""")
        val reply = IdMapApi(http, limiter, "test").forAniList("1") as MetaReply.Ok
        assertEquals(AnimeIds(mal = "1", imdb = "tt0213338", tvdb = "76885", tmdb = "30991"), reply.value)
        assertEquals("https://arm.haglund.dev/api/v2/ids?source=anilist&id=1", http.asked.single().url)
        assertEquals(MetaReply.Ok(AnimeIds()), IdMapApi(FakeHttp(404, "{}"), limiter, "t").forAniList("5"))
        assertEquals(MetaReply.Ok(AnimeIds(mal = null, imdb = null)), IdMapApi(FakeHttp(200, """{"myanimelist":"x","imdb":"nope","thetvdb":-4}"""), limiter, "t").forAniList("5"))
        assertEquals(MetaReply.Throttled(30_000), IdMapApi(FakeHttp(429, ""), limiter, "t").forAniList("5"))
    }
}
