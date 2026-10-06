package com.tmplayer.online

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

/**
 * One real search against api.opensubtitles.com, to prove the key and the request shape work.
 * Skipped unless OPENSUBTITLES_LIVE=1 and OPENSUBTITLES_API_KEY are in the environment, so CI and
 * an ordinary test run never touch the network. A search by name only: no sign in, no download,
 * so nobody's quota is spent.
 *
 *     OPENSUBTITLES_LIVE=1 OPENSUBTITLES_API_KEY=... ./gradlew :core:jvmTest --tests '*OpenSubtitlesLiveTest'
 */
class OpenSubtitlesLiveTest {

    @Test
    fun `a search by name for a well known film finds English subtitles`() = runBlocking {
        val key = System.getenv("OPENSUBTITLES_API_KEY").orEmpty()
        assumeTrue(System.getenv("OPENSUBTITLES_LIVE") == "1" && key.isNotBlank())
        val dir = Files.createTempDirectory("tm-online-live").toFile()
        val online = OnlineSubtitles(
            apiKey = key,
            store = OnlineSubtitlesStore(dir.resolve("online.properties")),
            cache = SubtitleCache(dir.resolve("cache")),
            appVersion = "1.22.1",
        )
        val result = online.search(SubtitleTarget("The.Matrix.1999.1080p.BluRay.mkv", 0, languages = listOf("en")))
        println("live: ${result.hits.size} hits, notice ${result.notice}, first: ${result.hits.firstOrNull()?.release}")
        assertTrue("expected hits, got ${result.notice}", result.hits.isNotEmpty())
        assertTrue(result.hits.all { it.language == "en" && it.provider == SubtitleProvider.OpenSubtitles })
    }
}
