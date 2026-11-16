package com.tmplayer.online

import com.tmplayer.data.MediaItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Where the skip lookup goes for an anime and for a show only TMDB knows, over a fake network:
 * the id mapping when AniList has no MyAnimeList id, anime-skip.com as the second anime source
 * (only with a client id, which the tests fake), the second try by IMDb or TheTVDB id, what is
 * kept, and that nothing is asked while the switch is off.
 */
class SkipLookupTest {

    private class FakeHttp : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        val rules = mutableListOf<Pair<(HttpRequest) -> Boolean, () -> HttpResponse>>()

        fun on(part: String, code: Int = 200, headers: Map<String, String> = emptyMap(), body: () -> String) {
            rules += { r: HttpRequest -> part in r.url || (r.body?.contains(part) == true) } to { HttpResponse(code, headers, body().toByteArray()) }
        }

        fun count(part: String) = requests.count { part in it.url || it.body?.contains(part) == true }

        override suspend fun send(request: HttpRequest): HttpResponse {
            requests += request
            return rules.firstOrNull { it.first(request) }?.second?.invoke() ?: HttpResponse(404, body = "{}".toByteArray())
        }
    }

    private var clock = 1_800_000_000_000L
    private val http = FakeHttp()
    private val dir: File = Files.createTempDirectory("tm-skip").toFile()

    private fun metadata(clientId: String = "fake-client", skipTimes: Boolean = true): OnlineMetadata {
        val store = MetadataStore(File(dir, "metadata.properties"))
        store.update { it.copy(enabled = true, skipTimes = skipTimes) }
        return OnlineMetadata(
            buildKey = "build-key",
            store = store,
            cache = MetadataCache(File(dir, "cache"), now = { clock }),
            appVersion = "test",
            http = http,
            now = { clock },
            sleep = { clock += it },
            language = { "en" },
            animeSkipClientId = clientId,
        )
    }

    private fun episode(name: String, seconds: Int) = MediaItem(
        chatId = 7, messageId = 1, fileId = 0, title = name, sizeBytes = 1, durationSec = seconds, mimeType = "video/x-matroska",
        thumbnailFileId = 0, miniThumbnail = null, date = 1, fileName = name, caption = "",
    )

    private val frieren = """{"data":{"Page":{"media":[{"id":154587,"format":"TV","seasonYear":2023,
        "title":{"romaji":"Sousou no Frieren","english":"Frieren: Beyond Journey's End","native":"x"},
        "synonyms":[],"description":"An elf mage.","coverImage":{"large":"https://s4.anilist.co/f.jpg"}}]}}}"""

    private val noMal = """{"data":{"Media":{"idMal":null,"genres":[],"averageScore":80}}}"""

    private val animeSkipShow = """{"data":{"findShowsByExternalId":[{"episodes":[
        {"number":"5","baseDuration":1440,"timestamps":[{"at":30,"type":{"name":"Intro"}},{"at":120,"type":{"name":"Canon"}},{"at":1350,"type":{"name":"Credits"}}]}]}]}}"""

    private val file = "[SubsPlease] Sousou no Frieren - 05 (1080p) [ABCD1234].mkv"

    private fun animeAnswers() {
        http.on("idMal genres") { noMal }
        http.on("graphql.anilist.co") { frieren }
    }

    @Test
    fun withoutAMalIdTheIdMappingFindsOneAndAniSkipIsAsked() = runBlocking {
        animeAnswers()
        http.on("arm.haglund.dev") { """{"anilist":154587,"myanimelist":52991,"thetvdb":424536}""" }
        http.on("aniskip.com") { """{"found":true,"results":[{"interval":{"startTime":20.0,"endTime":110.0},"skipType":"op","skipId":"x","episodeLength":1440.0}]}""" }
        val got = metadata().skipTimes(episode(file, 1440))
        assertEquals(listOf(SkipSegment(SkipKind.Opening, 20_000, 110_000)), got)
        assertEquals("only the AniList id goes to the mapping", "https://arm.haglund.dev/api/v2/ids?source=anilist&id=154587", http.requests.first { "arm.haglund" in it.url }.url)
        assertTrue(http.requests.first { "aniskip" in it.url }.url.contains("/skip-times/52991/5"))
        assertEquals("AniSkip answered, anime-skip.com is not asked", 0, http.count("api.anime-skip.com"))
    }

    @Test
    fun animeSkipIsTheSecondSourceAndItsAnswerIsKept() = runBlocking {
        animeAnswers()
        http.on("arm.haglund.dev") { """{"anilist":154587,"myanimelist":52991}""" }
        http.on("aniskip.com", code = 404) { "{}" }
        http.on("api.anime-skip.com") { animeSkipShow }
        val online = metadata()
        val item = episode(file, 1440)
        val got = online.skipTimes(item)
        assertEquals(
            listOf(SkipSegment(SkipKind.Opening, 30_000, 120_000), SkipSegment(SkipKind.Ending, 1_350_000, null)),
            got,
        )
        val asked = http.requests.first { "anime-skip.com" in it.url }
        assertEquals("fake-client", asked.headers["X-Client-ID"])
        assertTrue("the AniList id, no title", asked.body!!.contains("154587") && !asked.body!!.contains("Frieren"))
        val before = http.requests.size
        assertEquals(got, online.skipTimes(item))
        assertEquals("kept: the second ask goes nowhere", before, http.requests.size)
        assertEquals("a skip lookup does not wake the episode guide (TMDB, Kitsu, Jikan)", 0, http.count("themoviedb") + http.count("kitsu") + http.count("jikan"))
    }

    @Test
    fun withoutAClientIdAnimeSkipIsNeverAskedAndAMissIsKept() = runBlocking {
        animeAnswers()
        http.on("arm.haglund.dev") { """{"anilist":154587,"myanimelist":52991}""" }
        http.on("aniskip.com", code = 404) { "{}" }
        val online = metadata(clientId = "")
        val item = episode(file, 1440)
        assertEquals(emptyList<SkipSegment>(), online.skipTimes(item))
        assertEquals(0, http.count("anime-skip.com"))
        val before = http.requests.size
        assertEquals(emptyList<SkipSegment>(), online.skipTimes(item))
        assertEquals("an empty answer is kept too", before, http.requests.size)
    }

    @Test
    fun aRateLimitIsNotKeptAsNothingTimedAndPausesThatSource() = runBlocking {
        animeAnswers()
        http.on("arm.haglund.dev") { """{"anilist":154587,"myanimelist":52991}""" }
        http.on("aniskip.com", code = 404) { "{}" }
        http.on("api.anime-skip.com", code = 403) { """{"errors":[{"message":"Rate limit exceeded"}]}""" }
        val online = metadata()
        val item = episode(file, 1440)
        assertEquals(emptyList<SkipSegment>(), online.skipTimes(item))
        val asked = http.count("api.anime-skip.com")
        assertEquals(1, asked)
        // Paused: another episode is not sent to them until the wait is over, and nothing was kept as a miss.
        online.skipTimes(episode("[SubsPlease] Sousou no Frieren - 06 (1080p) [ABCD1234].mkv", 1440))
        assertEquals(asked, http.count("api.anime-skip.com"))
        clock += AnimeSkipApi.RATE_WAIT_MS + 1
        http.rules.add(0, { r: HttpRequest -> "api.anime-skip.com" in r.url } to { HttpResponse(200, body = animeSkipShow.toByteArray()) })
        assertEquals(2, online.skipTimes(item).size)
    }

    @Test
    fun switchedOffNothingLeavesTheDevice() = runBlocking {
        animeAnswers()
        assertEquals(emptyList<SkipSegment>(), metadata(skipTimes = false).skipTimes(episode(file, 1440)))
        assertEquals(0, http.requests.size)
        assertFalse(http.requests.any { "anime-skip" in it.url || "aniskip" in it.url || "haglund" in it.url })
    }

    @Test
    fun aShowOnlyTmdbKnowsIsAskedAgainByItsImdbAndTvdbIds() = runBlocking {
        http.on("search/tv") {
            """{"results":[{"id":555,"name":"Harbour Notes","original_name":"Harbour Notes","first_air_date":"2021-03-01","overview":"x","poster_path":"/hn.jpg"}]}"""
        }
        http.on("tv/555/season/1/episode/2") { """{"name":"The Storm","overview":"x"}""" }
        http.on("append_to_response") { """{"external_ids":{"imdb_id":"tt1234567","tvdb_id":98765},"vote_average":7.5}""" }
        http.on("tmdb_id=555") { """{"intro":[]}""" }
        http.on("imdb_id=tt1234567") { """{"intro":[]}""" }
        http.on("tvdb_id=98765") { """{"intro":[{"start_ms":10000,"end_ms":70000}]}""" }
        val got = metadata().skipTimes(episode("Harbour.Notes.S01E02.1080p.WEB-DL.mkv", 2700))
        assertEquals(http.requests.joinToString("\n") { it.url }, listOf(SkipSegment(SkipKind.Intro, 10_000, 70_000)), got)
        assertEquals(1, http.count("tmdb_id=555"))
        val second = http.requests.first { "tvdb_id=98765" in it.url }.url
        assertTrue(second, "imdb_id=tt1234567" in second || "tvdb_id=98765" in second)
    }
}
