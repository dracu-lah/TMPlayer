package com.tmplayer.online

import com.tmplayer.platform.LogSink
import com.tmplayer.platform.Logger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Online subtitles against a fake HTTP layer: what is asked of OpenSubtitles and SubDL, and what
 * the app does with each kind of answer. No test here touches the network.
 */
class OnlineSubtitlesTest {

    /** Answers by path, in turn; remembers every request. */
    private class FakeHttp : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        private val routes = mutableMapOf<String, ArrayDeque<HttpResponse>>()

        fun on(pathPart: String, vararg responses: HttpResponse) {
            routes.getOrPut(pathPart) { ArrayDeque() }.addAll(responses)
        }

        fun count(pathPart: String) = requests.count { pathPart in it.url }

        override suspend fun send(request: HttpRequest): HttpResponse {
            requests += request
            val route = routes.entries.firstOrNull { request.url.contains(it.key) }
                ?: error("unexpected request ${request.method} ${request.url}")
            return route.value.removeFirstOrNull() ?: error("no answer left for ${route.key}")
        }
    }

    private var clock = 1_800_000_000_000L
    private val sleeps = mutableListOf<Long>()
    private val dir: File = Files.createTempDirectory("tm-online").toFile()
    private val http = FakeHttp()
    private val store = OnlineSubtitlesStore(File(dir, "online.properties"))
    private val cache = SubtitleCache(File(dir, "cache"), now = { clock })

    private fun online(key: String = "test-key") = OnlineSubtitles(
        apiKey = key,
        store = store,
        cache = cache,
        appVersion = "1.0.0",
        http = http,
        now = { clock },
        sleep = { sleeps += it; clock += it },
    )

    private fun json(code: Int, body: String, headers: Map<String, String> = emptyMap()) =
        HttpResponse(code, headers, body.toByteArray())

    private fun hit(fileId: Int, lang: String = "en", release: String = "Release.$fileId", hash: Boolean = false, downloads: Int = 10, machine: Boolean = false) = """
        {"id":"$fileId","type":"subtitle","attributes":{"language":"$lang","release":"$release","download_count":$downloads,
         "hearing_impaired":false,"moviehash_match":$hash,"ai_translated":$machine,"machine_translated":false,
         "files":[{"file_id":$fileId,"file_name":"$release.srt"}]}}
    """.trimIndent()

    private fun page(vararg hits: String) = json(200, """{"total_count":${hits.size},"data":[${hits.joinToString(",")}]}""")

    private val episode = SubtitleTarget(
        fileName = "Night.Sky.S01E02.1080p.WEB.mkv",
        sizeBytes = 1_000_000,
        hash = "8e245d9679d31e12",
        languages = listOf("es", "en"),
    )

    private fun signIn(o: OnlineSubtitles, allowed: Int = 20, remaining: Int = 20) = runBlocking {
        http.on("/login", json(200, """{"user":{"allowed_downloads":$allowed},"base_url":"vip-api.opensubtitles.com","token":"tok-1","status":200}"""))
        http.on("/infos/user", json(200, """{"data":{"allowed_downloads":$allowed,"remaining_downloads":$remaining}}"""))
        assertEquals(OnlineSubtitles.SignIn.Ok, o.signIn("viewer", "secret"))
    }

    private val srt = "1\n00:00:01,000 --> 00:00:02,000\nHola\n"

    // ---- search ----------------------------------------------------------------------------

    @Test
    fun `search by hash sends the hash, the key, and sorted lower case parameters`() = runBlocking {
        val o = online()
        http.on("/subtitles", page(hit(1, "en", hash = false, downloads = 900), hit(2, "es", hash = true, downloads = 3), hit(3, "es", downloads = 50)))
        val result = o.search(episode)

        val request = http.requests.single()
        assertEquals("GET", request.method)
        assertEquals(
            "https://api.opensubtitles.com/api/v1/subtitles?ai_translated=exclude&languages=en,es&moviehash=8e245d9679d31e12",
            request.url.replace("%2C", ","),
        )
        assertEquals("test-key", request.headers["Api-Key"])
        assertEquals("TMPlayer v1.0.0", request.headers["User-Agent"])
        assertNull("a search carries no account token", request.headers["Authorization"])
        // Hash match first, then the wanted languages in order, then downloads.
        assertEquals(listOf("2", "3", "1"), result.hits.map { it.id })
        assertTrue(result.hits.first().hashMatch)
        assertEquals(SubtitleNotice.SignInToDownload, result.notice)
    }

    @Test
    fun `no hash match falls back to the name with season and episode`() = runBlocking {
        val o = online()
        http.on("moviehash=", page())
        http.on("query=", page(hit(7, "en")))
        val result = o.search(episode)

        assertEquals(2, http.requests.size)
        val byName = http.requests[1].url
        assertTrue(byName, "query=night+sky" in byName)
        assertTrue(byName, "season_number=1" in byName)
        assertTrue(byName, "episode_number=2" in byName)
        assertFalse(byName, "moviehash" in byName)
        assertEquals(listOf("7"), result.hits.map { it.id })
    }

    @Test
    fun `a film without a hash is searched by title and year`() = runBlocking {
        val o = online()
        http.on("/subtitles", page(hit(9)))
        o.search(SubtitleTarget("The.Matrix.1999.1080p.BluRay.mkv", 0, languages = listOf("en")))
        assertTrue(http.requests.single().url, "query=the+matrix+1999" in http.requests.single().url)
    }

    @Test
    fun `machine translations are left out unless switched on`() = runBlocking {
        val o = online()
        http.on("/subtitles", page(hit(1, machine = true), hit(2)))
        assertEquals(listOf("2"), o.search(episode).hits.map { it.id })

        o.setIncludeMachine(true)
        http.on("/subtitles", page(hit(1, machine = true), hit(2)))
        val withMachine = o.search(episode)
        assertTrue("ai_translated=include" in http.requests.last().url)
        assertEquals(setOf("1", "2"), withMachine.hits.map { it.id }.toSet())
    }

    @Test
    fun `a repeated search comes from the cache until it goes stale`() = runBlocking {
        val o = online()
        http.on("/subtitles", page(hit(1)))
        o.search(episode)
        val again = o.search(episode)
        assertEquals(1, http.requests.size)
        assertTrue(again.fromCache)
        assertEquals(listOf("1"), again.hits.map { it.id })

        clock += SubtitleCache.SEARCH_TTL_MS + 1
        http.on("/subtitles", page(hit(2)))
        val fresh = o.search(episode)
        assertEquals(2, http.requests.size)
        assertFalse(fresh.fromCache)
        assertEquals(listOf("2"), fresh.hits.map { it.id })
    }

    @Test
    fun `a 429 on search backs off for Retry-After and tries once more`() = runBlocking {
        val o = online()
        http.on("/subtitles", json(429, """{"message":"Throttle limit reached"}""", mapOf("Retry-After" to "2")), page(hit(1)))
        val result = o.search(episode)
        assertEquals(listOf("1"), result.hits.map { it.id })
        assertTrue("waited the 2 s asked for: $sleeps", 2_000L in sleeps)
    }

    @Test
    fun `two 429s in a row say busy, and nothing is cached`() = runBlocking {
        val o = online()
        http.on("/subtitles", json(429, "{}"), json(429, "{}"))
        val result = o.search(episode)
        assertEquals(SubtitleNotice.Busy, result.notice)
        assertTrue(result.hits.isEmpty())
        http.on("/subtitles", page(hit(1)))
        assertEquals(listOf("1"), o.search(episode).hits.map { it.id })
    }

    @Test
    fun `requests are spaced to five a second`() = runBlocking {
        val o = online()
        repeat(5) { i ->
            http.on("/subtitles", page(hit(i + 1)))
            o.search(episode.copy(hash = "%016x".format(i + 1L), fileName = "Film$i.mkv"))
        }
        assertEquals(5, http.requests.size)
        // The clock only moves when the limiter sleeps, so four gaps of 200 ms are four sleeps.
        assertEquals(listOf(200L, 200L, 200L, 200L), sleeps)
    }

    @Test
    fun `no connection says offline`() = runBlocking {
        val o = online()
        val failing = OnlineSubtitles("k", store, cache, "1.0.0", http = { throw java.io.IOException("down") }, now = { clock }, sleep = { clock += it })
        assertEquals(SubtitleNotice.Offline, failing.search(episode).notice)
        assertTrue(o.inBuild)
    }

    // ---- error mapping and the log line -------------------------------------------------------

    /** Every line the shared code logs while [block] runs. */
    private fun logged(block: () -> Unit): List<String> {
        val lines = mutableListOf<String>()
        val before = Logger.sink
        Logger.sink = object : LogSink {
            override fun i(tag: String, message: String) { lines += "I/$tag: $message" }
            override fun w(tag: String, message: String, error: Throwable?) { lines += "W/$tag: $message" }
        }
        try {
            block()
        } finally {
            Logger.sink = before
        }
        return lines
    }

    private fun failingWith(error: Exception) =
        OnlineSubtitles("secret-app-key", store, cache, "1.0.0", http = { throw error }, now = { clock }, sleep = { clock += it })

    @Test
    fun `a failed connection is logged under TMPlayer, with where and why but never the key`() {
        val lines = logged {
            val result = runBlocking { failingWith(java.net.UnknownHostException("api.opensubtitles.com")).search(episode) }
            assertEquals(SubtitleNotice.Offline, result.notice)
        }
        val line = lines.single()
        assertTrue(line, line.startsWith("W/TMPlayer: OpenSubtitles GET https://api.opensubtitles.com/api/v1/subtitles failed: UnknownHostException"))
        assertFalse("the key never reaches the log", "secret-app-key" in line)
        assertFalse("nor the query string", "moviehash" in line)
    }

    @Test
    fun `an exception that is not an IOException is still offline, logged, and does not escape`() {
        val lines = logged {
            val result = runBlocking { failingWith(SecurityException("Permission denied (missing INTERNET permission?)")).search(episode) }
            assertEquals(SubtitleNotice.Offline, result.notice)
        }
        assertTrue(lines.single().contains("SecurityException: Permission denied"))
    }

    @Test
    fun `a server error is offline and the log line carries its status and words`() = runBlocking {
        val o = online()
        http.on("/subtitles", json(503, """{"message":"Service unavailable"}"""))
        val lines = logged { assertEquals(SubtitleNotice.Offline, runBlocking { o.search(episode) }.notice) }
        assertEquals(
            listOf("""W/TMPlayer: OpenSubtitles GET https://api.opensubtitles.com/api/v1/subtitles failed: HTTP 503: {"message":"Service unavailable"}"""),
            lines,
        )
    }

    @Test
    fun `an answer that is not JSON is offline and logged`() = runBlocking {
        val o = online()
        http.on("/subtitles", json(200, "<html>captive portal</html>"))
        val lines = logged { assertEquals(SubtitleNotice.Offline, runBlocking { o.search(episode) }.notice) }
        assertTrue(lines.single(), lines.single().endsWith("failed: HTTP 200: <html>captive portal</html>"))
    }

    @Test
    fun `a query the service turns down is not the connection failing, and the name still gets its turn`() = runBlocking {
        val o = online()
        http.on("moviehash=", json(400, """{"errors":["Not enough parameters"],"status":400}"""))
        http.on("query=", page(hit(7)))
        val lines = logged {
            val result = runBlocking { o.search(episode) }
            assertEquals(listOf("7"), result.hits.map { it.id })
            assertEquals(SubtitleNotice.SignInToDownload, result.notice)
        }
        assertTrue(lines.single().contains("HTTP 400"))
        assertTrue(Reply.Failed(400).refusedRequest)
        assertFalse(Reply.Failed(0).refusedRequest)
        assertFalse(Reply.Failed(502).refusedRequest)
    }

    @Test
    fun `a refused query with nothing else to try is no results, not unreachable`() = runBlocking {
        val o = online()
        http.on("/subtitles", json(400, """{"errors":["Query is too short"],"status":400}"""))
        val result = o.search(SubtitleTarget(fileName = "Movie title.mkv", sizeBytes = 0, languages = listOf("en")))
        assertTrue(result.hits.isEmpty())
        assertNull(result.notice)
    }

    @Test
    fun `a title under three letters is never sent, since OpenSubtitles answers it with a 400`() = runBlocking {
        val o = online()
        val result = o.search(SubtitleTarget(fileName = "Up.mkv", sizeBytes = 0, languages = listOf("en")))
        assertTrue(http.requests.isEmpty())
        assertNull(result.notice)
    }

    @Test
    fun `searches no longer send machine_translated, which the API now answers with a redirect`() = runBlocking {
        val o = online()
        http.on("/subtitles", page(hit(1)))
        o.search(episode)
        assertFalse(http.requests.single().url.contains("machine_translated"))
    }

    // ---- account ---------------------------------------------------------------------------

    @Test
    fun `sign in keeps the token and the quota, never the password`() = runBlocking {
        val o = online()
        signIn(o, allowed = 20, remaining = 17)
        val login = http.requests.first { "/login" in it.url }
        assertEquals("https://api.opensubtitles.com/api/v1/login", login.url)
        assertTrue(login.body!!.contains("\"password\":\"secret\""))
        val info = http.requests.first { "/infos/user" in it.url }
        assertEquals("https://vip-api.opensubtitles.com/api/v1/infos/user", info.url)
        assertEquals("Bearer tok-1", info.headers["Authorization"])

        assertEquals(OnlineStatus.SignedIn("viewer", 17, 20), o.status())
        val saved = File(dir, "online.properties").readText()
        assertFalse("the password is not on the disk", saved.contains("secret"))
        // A second instance reads the same account back.
        assertEquals("tok-1", OnlineSubtitlesStore(File(dir, "online.properties")).now.token)
    }

    @Test
    fun `a wrong password says so and stores nothing`() = runBlocking {
        val o = online()
        http.on("/login", json(401, """{"message":"Error, invalid username/password","status":401}"""))
        assertEquals(OnlineSubtitles.SignIn.WrongPassword, o.signIn("viewer", "nope"))
        assertEquals(OnlineStatus.SignedOut, o.status())
    }

    @Test
    fun `sign ins are spaced a second apart`() = runBlocking {
        val o = online()
        http.on("/login", json(401, "{}"), json(401, "{}"))
        o.signIn("a", "b")
        o.signIn("a", "c")
        assertTrue("waited about a second: $sleeps", sleeps.any { it >= 800 })
    }

    @Test
    fun `sign out tells OpenSubtitles and forgets the account`() = runBlocking {
        val o = online()
        signIn(o)
        http.on("/logout", json(200, """{"message":"token successfully destroyed","status":200}"""))
        o.signOut()
        assertEquals("DELETE", http.requests.last().method)
        assertEquals(OnlineStatus.SignedOut, o.status())
        assertFalse(store.now.signedIn)
    }

    // ---- download --------------------------------------------------------------------------

    private val osHit = SubtitleHit(SubtitleProvider.OpenSubtitles, "4242", "es", "Night.Sky.S01E02", "Night.Sky.S01E02.srt")

    @Test
    fun `download spends a download, writes UTF-8, and a second one comes from the cache`() = runBlocking {
        val o = online()
        signIn(o, remaining = 10)
        http.on(
            "/download",
            json(200, """{"link":"https://dl.opensubtitles.org/x/4242.srt","file_name":"Night.Sky.S01E02.srt","requests":11,"remaining":9,"reset_time_utc":"2027-01-15T10:00:00.000Z"}"""),
        )
        // Saved by its uploader in the Windows code page: "ñ" is one byte, 0xF1.
        http.on("dl.opensubtitles.org", HttpResponse(200, body = "1\n00:00:01,000 --> 00:00:02,000\nMañana\n".toByteArray(Charsets.ISO_8859_1)))
        val done = o.download(osHit) as DownloadResult.Done

        val ask = http.requests.first { "/download" in it.url }
        assertEquals("POST", ask.method)
        assertEquals("https://vip-api.opensubtitles.com/api/v1/download", ask.url)
        assertEquals("Bearer tok-1", ask.headers["Authorization"])
        assertTrue(ask.body!!.contains("\"file_id\":4242"))
        assertTrue(done.file.name.endsWith(".srt"))
        assertTrue(done.file.readText(Charsets.UTF_8).contains("Mañana"))
        assertFalse(done.fromCache)
        assertEquals(9, store.now.remaining)
        assertEquals(OnlineStatus.SignedIn("viewer", 9, 20), o.status())

        val before = http.requests.size
        val again = o.download(osHit) as DownloadResult.Done
        assertTrue(again.fromCache)
        assertEquals(before, http.requests.size)
        assertEquals(done.file, again.file)
    }

    @Test
    fun `a downloaded file expires after six months at most`() = runBlocking {
        val o = online()
        signIn(o)
        http.on("/download", json(200, """{"link":"https://dl.opensubtitles.org/a","file_name":"a.srt","remaining":19}"""))
        http.on("dl.opensubtitles.org", HttpResponse(200, body = srt.toByteArray()))
        o.download(osHit)
        clock += SubtitleCache.MAX_TTL_MS + 1
        http.on("/download", json(200, """{"link":"https://dl.opensubtitles.org/a","file_name":"a.srt","remaining":18}"""))
        http.on("dl.opensubtitles.org", HttpResponse(200, body = srt.toByteArray()))
        assertFalse((o.download(osHit) as DownloadResult.Done).fromCache)
        assertEquals(2, http.count("/download"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a cache may not keep anything longer than six months`() {
        SubtitleCache(dir, searchTtlMs = SubtitleCache.MAX_TTL_MS + 1)
    }

    @Test
    fun `not signed in, a download asks for a sign in and sends nothing`() = runBlocking {
        val o = online()
        assertEquals(DownloadResult.Failed(SubtitleNotice.SignInToDownload), o.download(osHit))
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `a 429 carrying the quota means no downloads until the reset time`() = runBlocking {
        val o = online()
        signIn(o)
        http.on(
            "/download",
            json(429, """{"requests":21,"remaining":0,"message":"You have downloaded your allowed 20 subtitles for 24h","reset_time":"7 hours","reset_time_utc":"2027-01-15T10:00:00.000Z"}"""),
        )
        val reset = java.time.Instant.parse("2027-01-15T10:00:00Z").toEpochMilli()
        assertEquals(DownloadResult.Failed(SubtitleNotice.QuotaUsed(reset)), o.download(osHit))
        assertEquals(0, store.now.remaining)
        assertEquals(OnlineStatus.QuotaUsed("viewer", reset), o.status())

        // Asked again before the reset: refused here, without spending a request.
        val before = http.requests.size
        assertEquals(DownloadResult.Failed(SubtitleNotice.QuotaUsed(reset)), o.download(osHit))
        assertEquals(before, http.requests.size)
        // Searching still works, and says why downloading does not.
        http.on("/subtitles", page(hit(1)))
        assertEquals(SubtitleNotice.QuotaUsed(reset), o.search(episode).notice)
    }

    @Test
    fun `406 is the quota too`() = runBlocking {
        val o = online()
        signIn(o)
        http.on("/download", json(406, """{"message":"You have downloaded your allowed 20 subtitles for 24h","remaining":0}"""))
        val failed = o.download(osHit) as DownloadResult.Failed
        assertTrue(failed.notice is SubtitleNotice.QuotaUsed)
    }

    @Test
    fun `remaining zero after a download blocks the next one until the reset passes`() = runBlocking {
        val o = online()
        signIn(o, remaining = 1)
        http.on("/download", json(200, """{"link":"https://dl.opensubtitles.org/b","file_name":"b.srt","remaining":0,"reset_time_utc":"2027-01-15T10:00:00.000Z"}"""))
        http.on("dl.opensubtitles.org", HttpResponse(200, body = srt.toByteArray()))
        assertTrue(o.download(osHit) is DownloadResult.Done)
        clock = java.time.Instant.parse("2027-01-15T09:00:00Z").toEpochMilli()
        val other = osHit.copy(id = "5555")
        assertTrue((o.download(other) as DownloadResult.Failed).notice is SubtitleNotice.QuotaUsed)
        assertEquals(1, http.count("/download"))

        clock = java.time.Instant.parse("2027-01-15T10:00:01Z").toEpochMilli()
        assertEquals(OnlineStatus.SignedIn("viewer", 20, 20), o.status())
        http.on("/download", json(200, """{"link":"https://dl.opensubtitles.org/c","file_name":"c.srt","remaining":19}"""))
        http.on("dl.opensubtitles.org", HttpResponse(200, body = srt.toByteArray()))
        assertTrue(o.download(other) is DownloadResult.Done)
    }

    @Test
    fun `an expired token signs the viewer out and says so`() = runBlocking {
        val o = online()
        signIn(o)
        http.on("/download", json(401, """{"message":"invalid token"}"""))
        assertEquals(DownloadResult.Failed(SubtitleNotice.SignInExpired), o.download(osHit))
        assertFalse(store.now.signedIn)
        assertEquals(OnlineStatus.Expired("viewer"), o.status())
        // The next download does not try the dead token again.
        assertEquals(DownloadResult.Failed(SubtitleNotice.SignInExpired), o.download(osHit))
        assertEquals(1, http.count("/download"))
        http.on("/subtitles", page(hit(1)))
        assertEquals(SubtitleNotice.SignInExpired, o.search(episode).notice)
    }

    // ---- a revoked key ---------------------------------------------------------------------

    @Test
    fun `a refused key turns the feature off, keeps the cache, and comes back with a new key`() = runBlocking {
        val o = online()
        // Cached before the key went bad.
        http.on("/subtitles", page(hit(1)))
        o.search(episode)

        http.on("/subtitles", json(403, """{"message":"You cannot consume this service"}"""))
        val refused = o.search(episode.copy(hash = "0000000000000001", fileName = "Other.Film.2001.mkv"))
        assertEquals(SubtitleNotice.Unavailable, refused.notice)
        assertEquals(OnlineStatus.Unavailable, o.status())
        assertFalse("the key itself is never stored", File(dir, "online.properties").readText().contains("test-key"))

        // Nothing more is sent with the refused key, for searches, downloads or sign ins.
        val before = http.requests.size
        assertEquals(SubtitleNotice.Unavailable, o.search(episode.copy(hash = "0000000000000002", fileName = "Third.mkv")).notice)
        assertEquals(DownloadResult.Failed(SubtitleNotice.Unavailable), o.download(osHit))
        assertEquals(OnlineSubtitles.SignIn.Failed(SubtitleNotice.Unavailable), o.signIn("a", "b"))
        assertEquals(before, http.requests.size)

        // An update with a new key works at once.
        val updated = online(key = "new-key")
        assertEquals(OnlineStatus.SignedOut, updated.status())
        http.on("/subtitles", page(hit(3)))
        assertEquals(listOf("3"), updated.search(episode.copy(hash = "0000000000000004", fileName = "Fourth.mkv")).hits.map { it.id })
        assertEquals("new-key", http.requests.last().headers["Api-Key"])
    }

    @Test
    fun `a refused key is tried again a day later`() = runBlocking {
        val o = online()
        http.on("/subtitles", json(403, "{}"))
        o.search(episode)
        clock += OnlineSubtitles.REFUSED_RETRY_MS + 1
        assertEquals(OnlineStatus.SignedOut, o.status())
    }

    @Test
    fun `a build without a key is not in the build`() {
        val o = online(key = "")
        assertFalse(o.inBuild)
        assertEquals(OnlineStatus.NotInBuild, o.status())
    }

    // ---- SubDL -----------------------------------------------------------------------------

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            entries.forEach { (name, text) ->
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `SubDL is searched when OpenSubtitles has nothing, and its zip gives the right episode`() = runBlocking {
        val o = online()
        o.setSubdlKey("  subdl-key ")
        http.on("api.opensubtitles.com", page(), page())
        http.on(
            "api.subdl.com",
            json(200, """{"status":true,"results":[],"subtitles":[{"release_name":"Night.Sky.S01","name":"Night.Sky.S01.zip","lang":"spanish","language":"ES","url":"/subtitle/123-456.zip","hi":false}]}"""),
        )
        val result = o.search(episode)
        val search = http.requests.first { "api.subdl.com" in it.url }.url
        assertTrue(search, "api_key=subdl-key" in search)
        assertTrue(search, "film_name=Night+Sky" in search)
        assertTrue(search, "languages=ES%2CEN" in search)
        assertTrue(search, "type=tv" in search)
        assertEquals(SubtitleProvider.Subdl, result.hits.single().provider)
        assertEquals("es", result.hits.single().language)
        assertNull(result.notice)

        http.on("dl.subdl.com", HttpResponse(200, body = zip("Night.Sky.S01E01.srt" to "one", "Night.Sky.S01E02.srt" to srt)))
        val done = o.download(result.hits.single(), episode) as DownloadResult.Done
        assertEquals("https://dl.subdl.com/subtitle/123-456.zip", http.requests.last().url)
        assertEquals(srt, done.file.readText())
    }

    @Test
    fun `a refused SubDL key says so and is not tried again`() = runBlocking {
        val o = online()
        o.setSubdlKey("bad")
        http.on("api.opensubtitles.com", page(), page())
        http.on("api.subdl.com", json(200, """{"status":false,"error":"Invalid API key"}"""))
        assertEquals(SubtitleNotice.SubdlKeyRefused, o.search(episode).notice)
        assertTrue(store.now.subdlRefused)
    }

    @Test
    fun `SubDL stands in when the OpenSubtitles key is refused`() = runBlocking {
        val o = online()
        o.setSubdlKey("k")
        http.on("api.opensubtitles.com", json(403, "{}"))
        http.on("api.subdl.com", json(200, """{"status":true,"subtitles":[{"release_name":"R","name":"R.zip","language":"EN","url":"/subtitle/1.zip"}]}"""))
        val result = o.search(episode)
        assertEquals(1, result.hits.size)
        assertEquals(SubtitleNotice.Unavailable, result.notice)
    }

    // ---- cache housekeeping ----------------------------------------------------------------

    @Test
    fun `purge empties the cache and the next search asks again`() = runBlocking {
        val o = online()
        signIn(o)
        http.on("/subtitles", page(hit(1)))
        o.search(episode)
        http.on("/download", json(200, """{"link":"https://dl.opensubtitles.org/a","file_name":"a.srt","remaining":19}"""))
        http.on("dl.opensubtitles.org", HttpResponse(200, body = srt.toByteArray()))
        o.download(osHit)
        assertTrue(o.cacheBytes() > 0)

        o.purge()
        assertEquals(0, o.cacheBytes())
        assertTrue("the account survives a purge", store.now.signedIn)
        http.on("/subtitles", page(hit(1)))
        assertFalse(o.search(episode).fromCache)
    }

    @Test
    fun `prune drops only what has gone stale`() {
        cache.putSearch("old", emptyList())
        clock += SubtitleCache.SEARCH_TTL_MS + 1
        cache.putSearch("new", emptyList())
        cache.prune()
        assertNull(cache.search("old"))
        assertNotNull(cache.search("new"))
    }

    // ---- hash ------------------------------------------------------------------------------

    @Test
    fun `the hash is the size plus the words at both ends`() {
        val zeros = File(dir, "zeros.bin").apply { writeBytes(ByteArray(131_072)) }
        assertEquals("0000000000020000", MovieHash.of(zeros))

        val pattern = File(dir, "pattern.bin").apply { writeBytes(ByteArray(200_000) { ((it * 31 + 7) and 0xff).toByte() }) }
        // Worked out separately, in Python, from the published algorithm.
        assertEquals("5f9fe02060a3cd40", MovieHash.of(pattern))

        assertNull("too short to hash", MovieHash.of(File(dir, "short.bin").apply { writeBytes(ByteArray(1000)) }))
    }

    @Test
    fun `languages come as two letter codes, Brazilian Portuguese kept apart`() {
        assertEquals("en", OnlineSubtitles.iso1("eng"))
        assertEquals("de", OnlineSubtitles.iso1("ger"))
        assertEquals("pt-br", OnlineSubtitles.iso1("pt-BR"))
        assertEquals("ml", OnlineSubtitles.iso1("ml"))
        assertNull(OnlineSubtitles.iso1("und"))
        assertEquals(listOf("ml", "en"), OnlineSubtitles.languagesFor("mal"))
    }
}
