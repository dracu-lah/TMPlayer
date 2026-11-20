package com.tmplayer.online

import com.tmplayer.data.CrashReportScrubber
import com.tmplayer.data.LogScrubber
import com.tmplayer.platform.LogSink
import com.tmplayer.platform.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong

/**
 * SubMaker against a fake addon that follows the protocol, as seen live: the list answer with its
 * toolbox entry, ready subtitles as SubRip, and "Make <Language>" entries whose address answers at
 * once with a placeholder and, fetched again, gives the translation so far, growing, with nothing
 * to say when it is whole. Also 429, garbage, huge bodies and wrong types; and that the viewer's
 * address never reaches a log line, a report or the disk but the store.
 */
class SubMakerTest {

    private val secret = "k9Xq2LmZ7vTn4RwB8s"
    private val base = "https://addon.test/$secret"

    /** A scripted addon: the routes, how many times each was asked, and a translation that grows. */
    private class FakeAddon(private val base: String, private val secret: String) : HttpTransport {
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        val headers = java.util.Collections.synchronizedList(mutableListOf<Map<String, String>>())

        /** The translate address's answers, in order; the last one repeats. Cue counts, 0 = the placeholder. */
        var growth: List<Int> = listOf(0, 20, 60, 60, 120, 150)
        var translateStatus: List<HttpResponse> = emptyList()
        var listBody: String? = null
        var listStatus = 200
        var listFails = false
        var manifestBody = """{"id":"x","resources":["subtitles"],"types":["movie","series"]}"""
        private var polls = 0

        fun count(part: String) = requests.count { part in it }

        fun listJson(): String = """
            {"subtitles":[
              {"id":"sub_toolbox","lang":"Sub Toolbox","url":"$base/sub-toolbox/abc"},
              {"id":"os_1","lang":"eng","label":"Night.Sky.2022.1080p • OpenSubtitles","url":"$base/sub/eng1.srt"},
              {"id":"os_2","lang":"mal","label":"Night.Sky.2022.WEB • SubDL","url":"$base/sub/mal1.srt"},
              {"id":"translate_mal","lang":"Make Malayalam","url":"$base/translate/mal"},
              {"id":"translate_fra","lang":"Make French","url":"$base/translate/fra"},
              {"id":"","lang":"eng","url":"$base/sub/empty.srt"},
              {"id":"x","lang":"eng","url":"javascript:alert(1)"},
              {"id":"y","lang":5,"url":7},
              3, null, "text"
            ]}
        """.trimIndent()

        override suspend fun send(request: HttpRequest): HttpResponse {
            requests += request.url
            headers += request.headers
            val path = request.url.removePrefix(base)
            return when {
                path == "/manifest.json" -> ok(manifestBody)
                path.startsWith("/subtitles/") -> {
                    if (listFails) throw java.io.IOException("offline $secret")
                    HttpResponse(listStatus, body = (listBody ?: listJson()).toByteArray())
                }
                path.startsWith("/sub/") -> ok(srt(3))
                path.startsWith("/translate/") -> translate()
                else -> HttpResponse(404)
            }
        }

        private fun translate(): HttpResponse {
            val index = polls++
            translateStatus.getOrNull(index)?.let { return it }
            val shift = translateStatus.size
            val n = growth[minOf(index - shift, growth.lastIndex).coerceAtLeast(0)]
            return ok(if (n == 0) PLACEHOLDER else srt(n))
        }

        private fun ok(text: String) = HttpResponse(200, mapOf("content-type" to "text/plain"), text.toByteArray())

        companion object {
            val PLACEHOLDER = "1\n00:00:00,000 --> 04:00:00,000\nTRANSLATION IN PROGRESS\n\n2\n04:00:00,000 --> 04:00:05,000\nThis can take a few minutes.\n"

            fun srt(cues: Int, fromSecond: Int = 0): String = buildString {
                for (i in 1..cues) {
                    val a = fromSecond + i * 5
                    fun t(s: Int) = "%02d:%02d:%02d,000".format(s / 3600, s / 60 % 60, s % 60)
                    append("$i\n${t(a)} --> ${t(a + 3)}\nLine $i\n\n")
                }
            }
        }
    }

    private val clock = AtomicLong(1_800_000_000_000L)
    private val dir: File = Files.createTempDirectory("tm-submaker").toFile()
    private val addon = FakeAddon(base, secret)
    private val store = OnlineSubtitlesStore(File(dir, "online.properties"))
    private val cache = SubtitleCache(File(dir, "cache"), now = { clock.get() })
    private val logged = java.util.Collections.synchronizedList(mutableListOf<String>())
    private lateinit var online: OnlineSubtitles

    private val movie = SubtitleTarget(fileName = "Night.Sky.2022.1080p.WEB.mkv", sizeBytes = 1_000_000, imdb = "tt1234567")
    private val episode = SubtitleTarget(fileName = "Night.Sky.S02E05.1080p.WEB.mkv", sizeBytes = 1_000_000, imdb = "tt1234567")

    private val oldSink = Logger.sink

    @Before
    fun setUp() {
        Logger.sink = object : LogSink {
            override fun i(tag: String, message: String) { logged += "$tag $message" }
            override fun w(tag: String, message: String, error: Throwable?) { logged += "$tag $message ${error?.message.orEmpty()}" }
        }
        online = OnlineSubtitles("k", store, cache, "1.0.0", addon, now = { clock.get() }, sleep = { clock.addAndGet(it) })
        assertEquals(OnlineSubtitles.SubMakerSet.Saved, online.setSubMakerUrl("stremio://addon.test/$secret/manifest.json"))
    }

    @After
    fun tearDown() {
        Logger.sink = oldSink
        SubMakerSecret.register("")
        online.translations.cancelAll()
        dir.deleteRecursively()
    }

    private fun awaitEnd(key: String, seconds: Long = 15): TranslationState {
        val until = System.nanoTime() + seconds * 1_000_000_000
        while (System.nanoTime() < until) {
            val s = online.translations.stateOf(key)
            if (s != null && !s.active) return s
            Thread.sleep(2)
        }
        error("translation did not end: ${online.translations.stateOf(key)}")
    }

    // ---- the address ------------------------------------------------------------------------

    @Test
    fun `an install address is read in every form it is pasted in`() {
        assertEquals(base, SubMakerUrl.normalize("https://addon.test/$secret/manifest.json"))
        assertEquals(base, SubMakerUrl.normalize("stremio://addon.test/$secret/manifest.json"))
        assertEquals(base, SubMakerUrl.normalize("  \"https://ADDON.test/$secret/\"  "))
        assertEquals(base, SubMakerUrl.normalize("https://addon.test/$secret/manifest.json?x=1#y"))
        assertEquals("https://addon.test", SubMakerUrl.normalize("https://addon.test/manifest.json"))
        assertEquals("http://127.0.0.1:8080/tok", SubMakerUrl.normalize("http://127.0.0.1:8080/tok/manifest.json"))
    }

    @Test
    fun `what is not an install address is refused`() {
        for (bad in listOf(null, "", "   ", "addon.test/tok", "http://addon.test/tok", "ftp://addon.test/tok", "https://user:pw@addon.test/tok", "https://", "https://addon.test/a b", "javascript:alert(1)", "https://addon.test/\u0007x", "https://addon.test/" + "a".repeat(2_100))) {
            assertNull("$bad", SubMakerUrl.normalize(bad))
        }
        assertEquals(OnlineSubtitles.SubMakerSet.Invalid, online.setSubMakerUrl("not a link"))
        assertEquals(base, store.now.submakerBase)
    }

    @Test
    fun `the address is kept for the next start and removing it ends SubMaker`() {
        assertEquals(base, OnlineSubtitlesStore(File(dir, "online.properties")).now.submakerBase)
        assertTrue(online.submakerOn)
        assertEquals(OnlineSubtitles.SubMakerSet.Cleared, online.setSubMakerUrl(""))
        assertFalse(online.submakerOn)
        assertEquals("", OnlineSubtitlesStore(File(dir, "online.properties")).now.submakerBase)
        assertEquals(SubtitleNotice.SubMakerOff, runBlocking { online.searchSubMaker(movie) }.notice)
    }

    @Test
    fun `the manifest check says whether the address serves subtitles`() = runBlocking {
        assertNull(online.checkSubMaker())
        addon.manifestBody = """{"resources":["catalog"]}"""
        assertEquals(SubtitleNotice.SubMakerRejected, online.checkSubMaker())
        addon.manifestBody = "garbage"
        assertEquals(SubtitleNotice.SubMakerRejected, online.checkSubMaker())
    }

    // ---- the list ---------------------------------------------------------------------------

    @Test
    fun `a movie is asked by its id, and the toolbox and unusable entries are left out`() = runBlocking {
        val result = online.searchSubMaker(movie)
        assertNull(result.notice)
        assertTrue(addon.requests.any { it == "$base/subtitles/movie/tt1234567.json" })
        assertEquals(listOf("eng", "mal", "", ""), result.hits.map { it.language })
        assertEquals(listOf("", "", "Malayalam", "French").sorted(), result.hits.map { it.translateTo }.sorted())
        assertTrue(result.hits.none { it.id.startsWith("sub_toolbox") })
        assertTrue(result.hits.all { it.provider == SubtitleProvider.SubMaker })
        // Ready subtitles first, then the translations.
        assertEquals(listOf(false, false, true, true), result.hits.map { it.translateTo.isNotEmpty() })
    }

    @Test
    fun `an episode is asked as the show's id with its season and episode`() = runBlocking {
        online.searchSubMaker(episode)
        assertTrue(addon.requests.any { it == "$base/subtitles/series/tt1234567:2:5.json" })
    }

    @Test
    fun `the language wanted comes first among the translations too`() = runBlocking {
        val result = online.searchSubMaker(movie.copy(languages = listOf("fr", "en")))
        assertEquals(listOf("French", "Malayalam"), result.hits.filter { it.translateTo.isNotEmpty() }.map { it.translateTo })
    }

    @Test
    fun `a list is kept for a few minutes and asked again after`() = runBlocking {
        online.searchSubMaker(movie)
        online.searchSubMaker(movie)
        assertEquals(1, addon.count("/subtitles/"))
        clock.addAndGet(6 * 60_000)
        online.searchSubMaker(movie)
        assertEquals(2, addon.count("/subtitles/"))
    }

    @Test
    fun `no IMDb id is a sentence not a guess`() = runBlocking {
        assertEquals(SubtitleNotice.SubMakerNoId, online.searchSubMaker(movie.copy(imdb = null)).notice)
        assertEquals(SubtitleNotice.SubMakerNoId, online.searchSubMaker(movie.copy(imdb = "tt12")).notice)
        assertEquals(0, addon.count("/subtitles/"))
    }

    @Test
    fun `the list's failures are told apart`() = runBlocking {
        addon.listStatus = 429
        assertEquals(SubtitleNotice.SubMakerBusy, online.searchSubMaker(movie).notice)
        addon.listStatus = 404
        assertEquals(SubtitleNotice.SubMakerRejected, online.searchSubMaker(movie).notice)
        addon.listStatus = 500
        assertEquals(SubtitleNotice.Offline, online.searchSubMaker(movie).notice)
        addon.listStatus = 200
        addon.listBody = "<html>not json</html>"
        assertEquals(SubtitleNotice.Unreadable, online.searchSubMaker(movie).notice)
        addon.listBody = """{"subtitles":[]}"""
        assertEquals(SubtitleNotice.SubMakerNoResults, online.searchSubMaker(movie).notice)
        addon.listBody = null
        addon.listFails = true
        assertEquals(SubtitleNotice.Offline, online.searchSubMaker(movie).notice)
    }

    @Test
    fun `garbage lists give nothing and never throw`() {
        for (text in listOf("", "null", "[]", "{}", """{"subtitles":"x"}""", """{"subtitles":[1,2,"3",null,[],{}]}""", """{"subtitles":{"a":1}}""", "{\"subtitles\":[", "\u0000\u0001")) {
            val parsed = SubMakerApi.parseList(text, base)
            assertTrue("$text", parsed == null || parsed.isEmpty())
        }
        val huge = buildString {
            append("""{"subtitles":[""")
            for (i in 0 until 5_000) append("""{"id":"i$i","lang":"eng","url":"$base/sub/$i.srt"},""")
            append("""{"id":"last","lang":"eng","url":"$base/sub/x.srt"}]}""")
        }
        assertEquals(SubMakerApi.MAX_ENTRIES, SubMakerApi.parseList(huge, base)?.size)
        val long = SubMakerApi.parseList("""{"subtitles":[{"id":"${"a".repeat(10_000)}","lang":"eng","url":"$base/s.srt"}]}""", base)
        assertTrue(long!!.single().id.length <= 300)
        assertNull(SubMakerApi.parseList("""{"subtitles":[]}""" + " ".repeat(3 * 1024 * 1024), base))
    }

    // ---- ready subtitles --------------------------------------------------------------------

    @Test
    fun `a ready subtitle is fetched, read as text and kept`() = runBlocking {
        val hit = online.searchSubMaker(movie).hits.first()
        val done = online.download(hit, movie) as DownloadResult.Done
        assertTrue(done.file.readText().contains("Line 3"))
        assertTrue(done.label.contains("SubMaker"))
        val again = online.download(hit, movie) as DownloadResult.Done
        assertTrue(again.fromCache)
        assertEquals(1, addon.count("/sub/eng1"))
    }

    @Test
    fun `a hit remembered for a video never keeps the address`() = runBlocking {
        val hit = online.searchSubMaker(movie).hits.first()
        assertTrue(hit.link.startsWith(base))
        val json = hit.toJson().toString()
        assertFalse(json, json.contains(secret))
        assertEquals("", SubtitleHit.fromJson(hit.toJson())?.link)
    }

    // ---- translation ------------------------------------------------------------------------

    private fun makeHit(name: String = "Malayalam") = runBlocking { online.searchSubMaker(movie).hits.first { it.translateTo == name } }

    @Test
    fun `a translation runs as a job, polls the same address and replaces the track as it grows`() {
        val hit = makeHit()
        val tracks = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, Boolean>>())
        val started = online.translate("video1", hit, null) { file, final -> tracks += SubMakerApi.cueCount(file.readText()) to final }
        assertTrue(started)
        val key = online.translationKey("video1", hit)
        val end = awaitEnd(key)
        assertEquals(TranslationState.Done(150), end)
        // Every poll was the one address, never a rebuilt one.
        assertEquals(setOf("$base/translate/mal"), addon.requests.filter { "/translate/" in it }.toSet())
        // Growing, the last one final and whole; none of the placeholder.
        assertEquals(150 to true, tracks.last())
        assertTrue(tracks.all { it.first in 20..150 })
        assertEquals(tracks.map { it.first }.sorted(), tracks.map { it.first })
        // Kept for the video under the finished entry, with no half left.
        assertEquals(150, SubMakerApi.cueCount(cache.file(hit.provider, hit.id)!!.readText()))
        assertNull(cache.file(hit.provider, hit.id + "~part"))
        // The same entry again is at once the finished one, with no new asking.
        val before = addon.count("/translate/")
        var again: Pair<Int, Boolean>? = null
        assertTrue(online.translate("video1", hit, null) { file, final -> again = SubMakerApi.cueCount(file.readText()) to final })
        assertEquals(150 to true, again)
        assertEquals(before, addon.count("/translate/"))
    }

    @Test
    fun `only the first answer's placeholder is pending and never shown as a track`() {
        addon.growth = listOf(0, 0, 0, 40)
        val hit = makeHit()
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        online.translate("v", hit, null) { file, _ -> seen += file.readText() }
        awaitEnd(online.translationKey("v", hit))
        assertTrue(seen.none { it.contains("TRANSLATION IN PROGRESS") })
        assertTrue(seen.isNotEmpty())
    }

    @Test
    fun `a job is one per language and video`() {
        addon.growth = listOf(0, 10)
        val hit = makeHit()
        online.translations.setForeground(false)
        assertTrue(online.translate("v", hit, null) { _, _ -> })
        assertFalse(online.translate("v", hit, null) { _, _ -> })
        // Another video, or another language, is another job.
        assertTrue(online.translate("w", hit, null) { _, _ -> })
        assertTrue(online.translate("v", makeHit("French"), null) { _, _ -> })
        online.translations.setForeground(true)
        awaitEnd(online.translationKey("v", hit))
    }

    @Test
    fun `nothing is asked while the app is in the background`() {
        val hit = makeHit()
        online.translations.setForeground(false)
        online.translate("v", hit, null) { _, _ -> }
        Thread.sleep(150)
        assertEquals(0, addon.count("/translate/"))
        assertTrue(online.translations.stateOf(online.translationKey("v", hit))!!.active)
        online.translations.setForeground(true)
        assertTrue(awaitEnd(online.translationKey("v", hit)) is TranslationState.Done)
    }

    @Test
    fun `cancel ends the job and forgets it`() {
        val hit = makeHit()
        online.translations.setForeground(false)
        online.translate("v", hit, null) { _, _ -> }
        val key = online.translationKey("v", hit)
        online.translations.cancel(key)
        online.translations.setForeground(true)
        Thread.sleep(100)
        assertNull(online.translations.stateOf(key))
        assertEquals(0, addon.count("/translate/"))
        // And it can be started again.
        assertTrue(online.translate("v", hit, null) { _, _ -> })
        awaitEnd(key)
    }

    @Test
    fun `a video's jobs end when it does`() {
        val hit = makeHit()
        online.translations.setForeground(false)
        online.translate("v", hit, null) { _, _ -> }
        online.translate("v", makeHit("French"), null) { _, _ -> }
        online.translate("other", hit, null) { _, _ -> }
        online.translations.cancelVideo(online.translationPrefix("v"))
        assertNull(online.translations.stateOf(online.translationKey("v", hit)))
        assertNotNull(online.translations.stateOf(online.translationKey("other", hit)))
    }

    @Test
    fun `an answer that never has a line ends in a sentence after the cap`() {
        addon.growth = listOf(0)
        val hit = makeHit()
        online.translate("v", hit, null) { _, _ -> }
        val end = awaitEnd(online.translationKey("v", hit))
        assertEquals(TranslationState.Failed(SubtitleNotice.SubMakerEmpty, 0), end)
        assertTrue(addon.count("/translate/") in 30..40)
        assertNull(cache.file(hit.provider, hit.id))
    }

    @Test
    fun `a 429 waits out what it asked for and goes on`() {
        addon.translateStatus = listOf(HttpResponse(429, mapOf("retry-after" to "30")), HttpResponse(429))
        addon.growth = listOf(0, 30)
        val slept = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val timed = OnlineSubtitles("k", store, cache, "1.0.0", addon, now = { clock.get() }, sleep = { slept += it; clock.addAndGet(it) })
        val hit = makeHit()
        timed.translate("v", hit, null) { _, _ -> }
        val key = timed.translationKey("v", hit)
        val until = System.nanoTime() + 15_000_000_000
        while (timed.translations.stateOf(key)?.active != false && System.nanoTime() < until) Thread.sleep(2)
        assertEquals(TranslationState.Done(30), timed.translations.stateOf(key))
        assertTrue("$slept", slept.any { it >= 30_000 })
        assertTrue(slept.all { it <= SubMakerTranslator.MAX_BACKOFF_MS })
        timed.translations.cancelAll()
    }

    @Test
    fun `a rejected address stops it, keeping what came`() {
        addon.translateStatus = listOf(HttpResponse(200, body = FakeAddon.srt(25).toByteArray()), HttpResponse(403))
        val hit = makeHit()
        online.translate("v", hit, null) { _, _ -> }
        assertEquals(TranslationState.Failed(SubtitleNotice.SubMakerRejected, 25), awaitEnd(online.translationKey("v", hit)))
    }

    @Test
    fun `garbage, an offline address and a huge body each say so plainly`() {
        fun endOf(first: HttpResponse): TranslationState {
            addon.translateStatus = List(10) { first }
            val hit = makeHit()
            online.translations.cancelAll()
            online.translate("v", hit, null) { _, _ -> }
            return awaitEnd(online.translationKey("v", hit))
        }
        assertEquals(TranslationState.Failed(SubtitleNotice.Unreadable, 0), endOf(HttpResponse(200, body = ByteArray(0))))
        assertEquals(TranslationState.Failed(SubtitleNotice.Unreadable, 0), endOf(HttpResponse(200, body = ByteArray(5 * 1024 * 1024) { 'a'.code.toByte() })))
        assertEquals(TranslationState.Failed(SubtitleNotice.Offline, 0), endOf(HttpResponse(503)))
    }

    @Test
    fun `a translation that reaches the video's end is finished at once`() {
        addon.growth = listOf(0, 100, 200, 200, 200)
        // 200 cues end at 1003 s; a video of 1,050 s is within the end's slack.
        val hit = makeHit()
        online.translate("v", hit, 1_050_000L) { _, _ -> }
        val first = awaitEnd(online.translationKey("v", hit))
        assertEquals(TranslationState.Done(200), first)
        assertEquals(3, addon.count("/translate/"))
    }

    // ---- the secret -------------------------------------------------------------------------

    @Test
    fun `the address reaches no log line, bundle or report`() = runBlocking {
        addon.listFails = true
        online.searchSubMaker(movie)
        addon.listFails = false
        addon.listStatus = 500
        online.searchSubMaker(movie.copy(imdb = "tt7654321"))
        addon.listStatus = 200
        addon.translateStatus = List(5) { HttpResponse(503) }
        val hit = makeHit("French")
        online.translations.cancelAll()
        online.translate("v", hit, null) { _, _ -> }
        awaitEnd(online.translationKey("v", hit))
        assertTrue("some failures were logged", logged.isNotEmpty())
        for (line in logged) assertFalse(line, line.contains(secret) || line.contains("addon.test"))
    }

    @Test
    fun `a copy of the address that got into a line is taken out of it`() {
        val scrub = LogScrubber()
        for (line in listOf(
            "W/TMPlayer failed to open $base/manifest.json now",
            "GET $base/subtitles/movie/tt1234567.json failed",
            "retry stremio://addon.test/$secret/manifest.json",
            "bare token $secret in a line",
            "url=https://other.example/anything/translate/abc?x=1",
            "base=$base",
        )) {
            val cleaned = scrub.scrub(line)
            assertFalse(cleaned, cleaned.contains(secret) || cleaned.contains("addon.test"))
            assertFalse(cleaned, CrashReportScrubber.redact(line).contains(secret))
            assertTrue(cleaned, cleaned.contains("[addon]"))
        }
        // An ordinary address, and a line without any, stay as they are.
        assertEquals("opened https://example.org/page ok", scrub.scrub("opened https://example.org/page ok"))
    }

    @Test
    fun `the address is the only thing the store keeps that is secret, and it is not in the cache`() = runBlocking {
        val hit = online.searchSubMaker(movie).hits.first()
        online.download(hit, movie)
        val everything = dir.walkTopDown().filter { it.isFile && it.name != "online.properties" }
        for (f in everything) assertFalse(f.path, f.readText().contains(secret))
    }

    // ---- the translator on its own ----------------------------------------------------------

    private fun translator(sleep: suspend (Long) -> Unit, transport: HttpTransport) =
        SubMakerTranslator(SubMakerApi(transport, RateLimiter(0, { 0 }, {}), "t"), sleep)

    @Test
    fun `finishes when the count has not grown for three minutes of polling`() = runBlocking {
        val slept = mutableListOf<Long>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val transport = HttpTransport { HttpResponse(200, body = FakeAddon.srt(if (calls.getAndIncrement() == 0) 5 else 9).toByteArray()) }
        val states = mutableListOf<TranslationState>()
        translator({ slept += it }, transport).run("https://a.test/translate/x", null, MutableStateFlow(true), { states += it }, { _, _, _ -> })
        assertEquals(TranslationState.Done(9), states.last())
        // 5 (grown), 9 (grown), then 18 polls of 10 s with no growth reach 180 s.
        assertEquals(20, calls.get())
        assertEquals(19, slept.size)
        assertTrue(slept.all { it == 10_000L })
    }

    // ---- review fixes (HX91) ----------------------------------------------------------------

    @Test
    fun `an entry on another host than the viewer's own is dropped`() {
        val text = """{"subtitles":[
            {"id":"a","lang":"eng","url":"$base/sub/a.srt"},
            {"id":"b","lang":"eng","url":"https://evil.example/sub/b.srt"},
            {"id":"c","lang":"eng","url":"https://ADDON.test/x/c.srt"},
            {"id":"d","lang":"eng","url":"https://addon.test.evil.example/d.srt"},
            {"id":"e","lang":"eng","url":"https://user:pw@addon.test/e.srt"}]}"""
        assertEquals(listOf("a", "c"), SubMakerApi.parseList(text, base)!!.map { it.id })
        assertTrue(SubMakerUrl.fetchable("http://localhost:9/x", "http://localhost:9"))
        assertFalse(SubMakerUrl.fetchable("http://127.0.0.1:9/x", "http://localhost:9"))
        assertFalse(SubMakerUrl.fetchable("https://addon.test/x", "not a base"))
    }

    @Test
    fun `the singular subtitle path and the other addon paths are taken out of any address`() {
        val scrub = LogScrubber()
        for (line in listOf(
            "GET https://other.example/anytoken/subtitle/movie/tt1234567.json failed",
            "GET https://other.example/anytoken/subtitles/movie/tt1234567.json failed",
            "page https://other.example/anytoken/sub-toolbox/x",
            "job https://other.example/anytoken/translate/mal",
        )) {
            assertTrue(scrub.scrub(line), scrub.scrub(line).contains("[addon]"))
            assertFalse(CrashReportScrubber.redact(line), CrashReportScrubber.redact(line).contains("anytoken"))
        }
    }

    @Test
    fun `every form of the address is scrubbed from a log line and a crash report`() {
        val odd = "https://addon.test/tokenSegmentAA/conf%20ig-Bbb9/t%C3%A9st-segment"
        SubMakerSecret.register(odd)
        val scrub = LogScrubber()
        for (form in listOf(odd, "tokenSegmentAA", "conf%20ig-Bbb9", "conf ig-Bbb9", "t%C3%A9st-segment", "tést-segment", "tokenSegmentAA/conf%20ig-Bbb9")) {
            val line = "something $form went wrong"
            assertFalse("$form in a line", scrub.scrub(line).contains(form))
            assertFalse("$form in a report", CrashReportScrubber.redact(line).contains(form))
        }
        SubMakerSecret.register("")
        assertEquals("tokenSegmentAA stays", SubMakerSecret.scrub("tokenSegmentAA stays"))
    }

    private fun srtOf(cues: Int) = FakeAddon.srt(cues).toByteArray()

    @Test
    fun `429 answers never count as a stall and never make a final result`() = runBlocking {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val transport = HttpTransport {
            when (calls.getAndIncrement()) {
                0 -> HttpResponse(200, body = srtOf(20))
                1, 2, 3 -> HttpResponse(429, mapOf("retry-after" to "60"))
                else -> HttpResponse(200, body = srtOf(20))
            }
        }
        val finals = mutableListOf<Boolean>()
        val states = mutableListOf<TranslationState>()
        translator({}, transport).run("https://a.test/translate/x", null, MutableStateFlow(true), { states += it }, { _, _, final -> finals += final })
        // Three minutes of waiting out 429s did not end it: 18 calm polls without growth did.
        assertEquals(22, calls.get())
        assertEquals(listOf(false, true), finals)
        assertEquals(TranslationState.Done(20), states.last())
    }

    @Test
    fun `an addon that stays busy ends the job partial, never final`() = runBlocking {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val transport = HttpTransport {
            if (calls.getAndIncrement() == 0) HttpResponse(200, body = srtOf(20)) else HttpResponse(429, mapOf("retry-after" to "60"))
        }
        val finals = mutableListOf<Boolean>()
        val states = mutableListOf<TranslationState>()
        translator({}, transport).run("https://a.test/translate/x", null, MutableStateFlow(true), { states += it }, { _, _, final -> finals += final })
        assertTrue(finals.isNotEmpty() && finals.none { it })
        assertEquals(TranslationState.Failed(SubtitleNotice.SubMakerBusy, 20), states.last())
        assertEquals(1 + 10, calls.get())
    }

    @Test
    fun `reaching the cap while still growing shows the text but is not final`() = runBlocking {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val transport = HttpTransport { HttpResponse(200, body = srtOf(calls.incrementAndGet() * 5)) }
        val finals = mutableListOf<Boolean>()
        val states = mutableListOf<TranslationState>()
        SubMakerTranslator(SubMakerApi(transport, RateLimiter(0, { 0 }, {}), "t"), {}, capMs = 60_000L)
            .run("https://a.test/translate/x", null, MutableStateFlow(true), { states += it }, { _, _, final -> finals += final })
        assertTrue(finals.none { it })
        assertTrue(states.last() is TranslationState.Done)
    }

    @Test
    fun `a throttled partial result is still laid when the job ends, and never as the final file`() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val transport = HttpTransport { HttpResponse(200, body = srtOf(calls.incrementAndGet() * 5)) }
        val translator = SubMakerTranslator(SubMakerApi(transport, RateLimiter(0, { 0 }, {}), "t"), {}, capMs = 60_000L)
        val jobs = SubMakerJobs(translator, CoroutineScope(Dispatchers.Default), now = { 1L }, applyEveryMs = Long.MAX_VALUE / 2)
        val written = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, Boolean>>())
        jobs.start("v|x", "https://a.test/translate/x", null, write = { srt, final ->
            written += SubMakerApi.cueCount(srt) to final
            File.createTempFile("tm-part", ".srt", dir)
        }, onTrack = { _, _ -> })
        val until = System.nanoTime() + 15_000_000_000
        while (jobs.stateOf("v|x")?.active != false && System.nanoTime() < until) Thread.sleep(2)
        assertTrue(jobs.stateOf("v|x") is TranslationState.Done)
        assertTrue(written.none { it.second })
        // The first update was applied; the newest one waited and was laid at the end.
        assertTrue("$written", written.size >= 2 && written.last().first > written.first().first)
        jobs.cancelAll()
    }

    @Test
    fun `a cancel while waiting ends the run with no more asking`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val asked = java.util.concurrent.atomic.AtomicInteger()
        val transport = HttpTransport { asked.incrementAndGet(); HttpResponse(200, body = FakeAddon.PLACEHOLDER.toByteArray()) }
        val job = CoroutineScope(Dispatchers.Default).async {
            translator({ gate.await() }, transport).run("https://a.test/t", null, MutableStateFlow(true), {}, { _, _, _ -> })
        }
        while (asked.get() == 0) Thread.sleep(1)
        job.cancel()
        gate.complete(Unit)
        try { job.await() } catch (_: CancellationException) {}
        assertTrue(job.isCancelled)
        assertEquals(1, asked.get())
    }
}
