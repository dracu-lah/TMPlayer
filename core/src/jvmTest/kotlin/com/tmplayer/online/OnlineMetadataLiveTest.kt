package com.tmplayer.online

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Properties

/**
 * The same lookups against the real TMDB, TVmaze and AniList, to prove the wiring: skipped unless
 * TMPLAYER_LIVE=1. The TMDB key comes from TMDB_API_KEY in the environment or local.properties,
 * and is never printed.
 */
class OnlineMetadataLiveTest {

    private val live = System.getenv("TMPLAYER_LIVE") == "1"

    private fun tmdbKey(): String = System.getenv("TMDB_API_KEY")?.takeIf { it.isNotBlank() }
        ?: listOf(File("local.properties"), File("../local.properties")).firstOrNull { it.isFile }?.let { f ->
            Properties().apply { f.inputStream().use { load(it) } }.getProperty("TMDB_API_KEY")
        }.orEmpty()

    private fun metadata(key: String, lang: String = "en"): OnlineMetadata {
        val dir = Files.createTempDirectory("tm-meta-live").toFile()
        val store = MetadataStore(File(dir, "m.properties")).apply { update { it.copy(enabled = true) } }
        return OnlineMetadata(key, store, MetadataCache(File(dir, "cache")), "live-test", language = { lang })
    }

    @Test
    fun tmdbFindsAWellKnownFilm() = runBlocking {
        assumeTrue(live)
        val key = tmdbKey()
        assumeTrue("no TMDB key", key.isNotBlank())
        val online = metadata(key, lang = "ml")
        val info = (online.lookup(MetaQuery.of("Big.Buck.Bunny.2008.1080p.BluRay.x264.mkv")!!) as MetaResult.Found).info
        assertEquals(MetaProvider.Tmdb, info.provider)
        assertEquals("10378", info.id)
        assertTrue("an overview, in English when Malayalam has none", info.overview.isNotBlank())
        val poster = online.image(info.posterUrl!!)
        assertTrue("the poster downloads", (poster?.length() ?: 0) > 5_000)
        println("LIVE tmdb: ${info.title} (${info.year}) id ${info.id}, overview in ${info.language}, poster ${poster?.length()} bytes")
    }

    @Test
    fun tvmazeFindsAShowAndItsEpisode() = runBlocking {
        assumeTrue(live)
        val online = metadata(key = "")
        val info = (online.lookup(MetaQuery.of("Breaking.Bad.S01E02.720p.mkv")!!) as MetaResult.Found).info
        assertEquals(MetaProvider.TvMaze, info.provider)
        assertTrue(info.episode?.name.orEmpty().isNotBlank())
        println("LIVE tvmaze: ${info.title} id ${info.id}, S01E02 \"${info.episode?.name}\"")
    }

    @Test
    fun aniListFindsAnAnime() = runBlocking {
        assumeTrue(live)
        val online = metadata(key = "")
        val query = MetaQuery.of("[SubsPlease] Sousou no Frieren - 05 (1080p).mkv")!!
        val info = (online.lookup(query) as MetaResult.Found).info
        assertEquals(MetaProvider.AniList, info.provider)
        println("LIVE anilist: ${info.title} (${info.year}) id ${info.id}, poster ${info.posterUrl != null}")
    }

    @Test
    fun aBadTmdbKeyIsRefused() = runBlocking {
        assumeTrue(live)
        val online = metadata(key = "0".repeat(32))
        online.lookup(MetaQuery.of("Big.Buck.Bunny.2008.mkv")!!)
        assertEquals(TmdbState.AppKeyRefused, online.tmdbState())
        println("LIVE bad key: ${online.tmdbState()}")
    }
}
