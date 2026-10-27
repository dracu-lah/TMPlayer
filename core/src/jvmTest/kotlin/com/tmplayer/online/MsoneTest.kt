package com.tmplayer.online

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
 * MSone through TMPlayer's gateway, against a fake HTTP layer: the state check and what each
 * state does to the picker, the draft search shape, the gateway's errors, and downloads.
 */
class MsoneTest {

    private class FakeHttp : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        private val routes = mutableMapOf<String, ArrayDeque<() -> HttpResponse>>()

        fun on(pathPart: String, vararg responses: HttpResponse) {
            routes.getOrPut(pathPart) { ArrayDeque() }.addAll(responses.map { r -> { r } })
        }

        fun fail(pathPart: String) {
            routes.getOrPut(pathPart) { ArrayDeque() }.add { throw java.io.IOException("offline") }
        }

        fun count(pathPart: String) = requests.count { pathPart in it.url }

        override suspend fun send(request: HttpRequest): HttpResponse {
            requests += request
            val route = routes.entries.firstOrNull { request.url.contains(it.key) }
                ?: error("unexpected request ${request.method} ${request.url}")
            return (route.value.removeFirstOrNull() ?: error("no answer left for ${route.key}"))()
        }
    }

    private var clock = 1_800_000_000_000L
    private val dir: File = Files.createTempDirectory("tm-msone").toFile()
    private val http = FakeHttp()
    private val store = OnlineSubtitlesStore(File(dir, "online.properties"))
    private val cache = SubtitleCache(File(dir, "cache"), now = { clock })
    private val online = OnlineSubtitles("k", store, cache, "1.0.0", http, now = { clock }, sleep = { clock += it })

    private fun json(code: Int, body: String, headers: Map<String, String> = emptyMap()) =
        HttpResponse(code, headers, body.toByteArray())

    private fun health(state: String, message: String? = null) =
        json(200, """{"ok":true,"providers":{"msone":{"state":"$state","message":${message?.let { "\"$it\"" } ?: "null"}}}}""")

    private val searchAnswer = """
        {"hits":[
          {"id":"100","title":"Night Sky","year":2022,"kind":"pack","season":1,"episode":null,
           "translator":{"name":"Anu","url":"https://example.org/anu"},
           "downloadUrl":"https://malayalamsubtitles.org/download/100","pageUrl":"https://malayalamsubtitles.org/night-sky","credit":"Subtitles by MSone"},
          {"id":"101","title":"Night Sky","year":2022,"kind":"single","season":1,"episode":2,
           "translator":{"name":"Biju","url":"javascript:alert(1)"},
           "downloadUrl":"https://elsewhere.example/101.srt","pageUrl":"http://malayalamsubtitles.org/x","credit":"Subtitles by MSone"},
          {"id":"","title":"No id"},
          {"title":"Missing id"},
          7
        ]}
    """.trimIndent()

    private val episode = SubtitleTarget(fileName = "Night.Sky.S01E02.1080p.WEB.mkv", sizeBytes = 1_000_000)

    private fun turnOn() = runBlocking {
        http.on("/health", health("on"))
        assertEquals(MsoneState.On, online.msoneStatus().state)
    }

    private fun srt(text: String) = "1\n00:00:01,000 --> 00:00:02,000\n$text\n".toByteArray()

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (name, data) -> z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() } }
        return out.toByteArray()
    }

    // ---- state -----------------------------------------------------------------------------

    @Test
    fun `off at the gateway says so where the results would be and never searches`() = runBlocking {
        http.on("/health", health("off"))
        val result = online.searchMsone(episode)
        assertEquals(SubtitleNotice.MsoneOff(MsoneState.Off, null), result.notice)
        assertTrue(result.hits.isEmpty())
        assertEquals(0, http.count("/msone/search"))
    }

    @Test
    fun `the state is asked at most every six hours, and a forced check once a minute`() = runBlocking {
        http.on("/health", health("off"), health("paused", "Back next week"), health("on"))
        online.msoneStatus()
        online.msoneStatus()
        assertEquals(1, http.count("/health"))
        online.msoneStatus(force = true)
        assertEquals("a forced check within a minute asks nothing", 1, http.count("/health"))
        clock += OnlineSubtitles.MSONE_FORCE_MS
        val paused = online.msoneStatus(force = true)
        assertEquals(MsoneState.Paused, paused.state)
        assertEquals("Back next week", paused.message)
        clock += OnlineSubtitles.MSONE_CHECK_MS
        assertEquals(MsoneState.On, online.msoneStatus().state)
        assertEquals(3, http.count("/health"))
    }

    @Test
    fun `a failed check keeps stopped, and a first check that fails is unknown`() = runBlocking {
        http.fail("/health")
        assertEquals(MsoneState.Unknown, online.msoneStatus().state)

        clock += OnlineSubtitles.MSONE_FORCE_MS
        http.on("/health", health("stopped", "MSone asked us to stop"))
        assertEquals(MsoneState.Stopped, online.msoneStatus(force = true).state)

        clock += OnlineSubtitles.MSONE_CHECK_MS
        http.fail("/health")
        val after = online.msoneStatus()
        assertEquals(MsoneState.Stopped, after.state)
        assertEquals("MSone asked us to stop", after.message)
        // Kept on the disk too, for the next launch.
        assertEquals(MsoneState.Stopped, OnlineSubtitlesStore(File(dir, "online.properties")).now.msoneState)
    }

    @Test
    fun `an unknown state word reads as unknown`() {
        assertEquals(MsoneState.Unknown, MsoneApi.parseHealth("""{"providers":{"msone":{"state":"maybe"}}}""", 0)?.state)
        assertNull(MsoneApi.parseHealth("""{"providers":{}}""", 0))
        assertNull(MsoneApi.parseHealth("<html>", 0))
    }

    // ---- search ----------------------------------------------------------------------------

    @Test
    fun `hits are read from the draft shape, unsafe links dropped, the episode first`() = runBlocking {
        turnOn()
        http.on("/msone/search", json(200, searchAnswer))
        val result = online.searchMsone(episode)
        assertNull(result.notice)
        assertEquals(listOf("101", "100"), result.hits.map { it.id })
        val single = result.hits[0]
        assertEquals(SubtitleProvider.Msone, single.provider)
        assertEquals("ml", single.language)
        assertEquals("Night Sky S01E02", single.release)
        assertEquals("Night Sky S01", result.hits[1].release)
        assertEquals("", single.translatorUrl)
        assertEquals("", single.pageUrl)
        assertEquals("https://elsewhere.example/101.srt", single.link)
        val pack = result.hits[1]
        assertTrue(pack.pack)
        assertEquals("Anu", pack.translator)
        assertEquals("https://example.org/anu", pack.translatorUrl)
        assertEquals(1, pack.featureSeason)
        assertNull(pack.featureEpisode)
        val asked = http.requests.last { "/msone/search" in it.url }.url
        assertTrue(asked, "q=Night+Sky" in asked && "season=1" in asked && "episode=2" in asked)

        // Kept: the same video searched again asks nothing.
        online.searchMsone(episode)
        assertEquals(1, http.count("/msone/search"))
    }

    @Test
    fun `an IMDb id is searched instead of the title`() = runBlocking {
        turnOn()
        http.on("/msone/search", json(200, """{"hits":[]}"""))
        online.searchMsone(episode.copy(imdb = "tt6723592"))
        val asked = http.requests.last().url
        assertTrue(asked, "imdb=tt6723592" in asked && "q=" !in asked)
    }

    @Test
    fun `the gateway's errors become the picker's lines`() = runBlocking {
        turnOn()
        http.on(
            "/msone/search",
            json(429, """{"error":"rate_limited","message":"Slow down"}""", mapOf("Retry-After" to "30")),
            json(400, """{"error":"bad_query","message":"q"}"""),
            json(502, "<html>bad gateway</html>"),
            json(503, """{"error":"unavailable","message":"Something went wrong."}"""),
            json(200, "{not json"),
        )
        assertEquals(SubtitleNotice.MsoneBusy, online.searchMsone(episode).notice)
        assertEquals(SubtitleNotice.MsoneTooShort, online.searchMsone(episode).notice)
        assertEquals(SubtitleNotice.Offline, online.searchMsone(episode).notice)
        assertEquals("the gateway's own failure is not MSone being off", SubtitleNotice.Offline, online.searchMsone(episode).notice)
        assertEquals(SubtitleNotice.Offline, online.searchMsone(episode).notice)
        // None of these changes the state.
        assertEquals(MsoneState.On, store.now.msoneState)
    }

    @Test
    fun `a 404 is no results, not an error`() = runBlocking {
        turnOn()
        http.on("/msone/search", json(404, """{"error":"not_found","message":"none"}"""))
        val result = online.searchMsone(episode)
        assertNull(result.notice)
        assertTrue(result.hits.isEmpty())
    }

    @Test
    fun `a title too short to search says so without asking`() = runBlocking {
        turnOn()
        val result = online.searchMsone(SubtitleTarget(fileName = "a.mkv", sizeBytes = 1))
        assertEquals(SubtitleNotice.MsoneTooShort, result.notice)
        assertEquals(0, http.count("/msone/search"))
    }

    @Test
    fun `stopped mid search turns the state and deletes kept MSone files`() = runBlocking {
        turnOn()
        http.on("/msone/search", json(200, searchAnswer))
        val hit = online.searchMsone(episode).hits.first { !it.pack }
        http.on("/msone/download/101", HttpResponse(200, body = srt("Hello")))
        assertTrue(online.download(hit, episode) is DownloadResult.Done)
        cache.putFile(SubtitleProvider.OpenSubtitles, "os-1", SubtitleText.Text("srt", "1\n00:00:01,000 --> 00:00:02,000\nx\n"))

        clock += 1
        http.on("/msone/search", json(410, """{"error":"stopped","message":"At MSone's request"}"""))
        val result = online.searchMsone(episode.copy(fileName = "Other.Show.S01E01.mkv"))
        assertEquals(SubtitleNotice.MsoneOff(MsoneState.Stopped, "At MSone's request"), result.notice)
        assertEquals(MsoneState.Stopped, store.now.msoneState)
        assertNull(cache.file(SubtitleProvider.Msone, "101"))
        assertNotNull("other providers' files stay", cache.file(SubtitleProvider.OpenSubtitles, "os-1"))
    }

    @Test
    fun `paused keeps downloaded subtitles and still lists them`() = runBlocking {
        turnOn()
        http.on("/msone/search", json(200, searchAnswer))
        val hits = online.searchMsone(episode).hits
        http.on("/msone/download/101", HttpResponse(200, body = srt("Hello")))
        assertTrue(online.download(hits.first { !it.pack }, episode) is DownloadResult.Done)

        clock += OnlineSubtitles.MSONE_CHECK_MS
        http.on("/health", health("paused", "Taking a break"))
        val result = online.searchMsone(episode)
        assertEquals(SubtitleNotice.MsoneOff(MsoneState.Paused, "Taking a break"), result.notice)
        assertEquals(listOf("101"), result.hits.map { it.id })
        assertNull("a kept file is not blocked", online.blockedFor(result.hits.single()))
        assertNotNull("a file not kept is", online.blockedFor(hits.first { it.pack }))
        val again = online.download(result.hits.single(), episode)
        assertTrue(again is DownloadResult.Done && again.fromCache)
    }

    // ---- download --------------------------------------------------------------------------

    @Test
    fun `MSone's own address is fetched directly, any other through the gateway`() = runBlocking {
        turnOn()
        http.on("/msone/search", json(200, searchAnswer))
        val hits = online.searchMsone(episode).hits
        http.on("malayalamsubtitles.org/download/100", HttpResponse(200, body = zip("Night.Sky.S01E01.srt" to srt("one"), "Night.Sky.S01E02.srt" to srt("two"))))
        val pack = online.download(hits.first { it.pack }, episode)
        assertTrue(pack is DownloadResult.Done)
        assertTrue("the video's episode out of the pack", (pack as DownloadResult.Done).file.readText().contains("two"))
        assertEquals("Malayalam · MSone", pack.label)

        http.on("/msone/download/101", HttpResponse(200, body = srt("single")))
        assertTrue(online.download(hits.first { !it.pack }, episode) is DownloadResult.Done)
        assertEquals("the untrusted host is never asked", 0, http.count("elsewhere.example"))
    }

    @Test
    fun `each episode of one pack is kept apart`() = runBlocking {
        turnOn()
        http.on("/msone/search", json(200, searchAnswer))
        val pack = online.searchMsone(episode).hits.first { it.pack }
        val body = zip("Night.Sky.S01E01.srt" to srt("one"), "Night.Sky.S01E02.srt" to srt("two"))
        http.on("malayalamsubtitles.org/download/100", HttpResponse(200, body = body), HttpResponse(200, body = body))
        val two = online.download(pack, episode) as DownloadResult.Done
        val one = online.download(pack, episode.copy(fileName = "Night.Sky.S01E01.mkv")) as DownloadResult.Done
        assertTrue(two.file.readText().contains("two"))
        assertTrue(one.file.readText().contains("one"))
        assertTrue((online.download(pack, episode) as DownloadResult.Done).fromCache)
    }

    @Test
    fun `a pack without this episode gives nothing rather than another episode`() = runBlocking {
        turnOn()
        http.on("/msone/search", json(200, searchAnswer))
        val pack = online.searchMsone(episode).hits.first { it.pack }
        http.on("malayalamsubtitles.org/download/100", HttpResponse(200, body = zip("Night.Sky.S01E01.srt" to srt("one"), "Night.Sky.S01E03.srt" to srt("three"))))
        assertEquals(DownloadResult.Failed(SubtitleNotice.Unreadable), online.download(pack, episode))
    }

    @Test
    fun `an oversized or unreadable file fails with a line, not an exception`() = runBlocking {
        turnOn()
        http.on("/msone/search", json(200, searchAnswer))
        val single = online.searchMsone(episode).hits.first { !it.pack }
        http.on(
            "/msone/download/101",
            HttpResponse(200, body = ByteArray(SubtitleText.MAX_BYTES + 1)),
            HttpResponse(200, body = ByteArray(0)),
            HttpResponse(503, body = """{"error":"paused","message":"Later"}""".toByteArray()),
        )
        assertEquals(DownloadResult.Failed(SubtitleNotice.Offline), online.download(single, episode))
        assertEquals(DownloadResult.Failed(SubtitleNotice.Unreadable), online.download(single, episode))
        assertEquals(DownloadResult.Failed(SubtitleNotice.MsoneOff(MsoneState.Paused, "Later")), online.download(single, episode))
        assertEquals(MsoneState.Paused, store.now.msoneState)
    }

    // ---- the hidden switch -----------------------------------------------------------------

    @Test
    fun `msone typed as the SubDL key shows and hides MSone and keeps the key`() {
        online.setSubdlKey("real-key")
        assertFalse(store.now.msoneShown)
        assertEquals(true, online.setSubdlKey(" MSone "))
        assertTrue(store.now.msoneShown)
        assertEquals("real-key", store.now.subdlKey)
        assertEquals(false, online.setSubdlKey("msone"))
        assertFalse(store.now.msoneShown)
        assertNull(online.setSubdlKey("other"))
        assertEquals("other", store.now.subdlKey)
    }

    @Test
    fun `an MSone hit survives the search cache`() {
        val hit = MsoneApi.parseHits(searchAnswer)!!.first()
        val back = SubtitleHit.listFromJson(SubtitleHit.listToJson(listOf(hit))).single()
        assertEquals(hit, back)
    }
}
