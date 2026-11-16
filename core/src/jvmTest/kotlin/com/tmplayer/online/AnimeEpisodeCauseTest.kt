package com.tmplayer.online

import com.tmplayer.data.EpisodeTag
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * T11, step 1: why a long anime (Naruto Shippuden, 500 episodes named by absolute number, "Naruto
 * Shippuden - 123.mkv") shows no episode titles, stills or details. Each test states what a viewer
 * should get and failed before the fix for the cause it names:
 *
 *  1. a show matched on AniList (one entry for the whole run) had no episode list at all,
 *  2. [EpisodeNames.named] only takes a name whose season and number are the file's, and the
 *     lookup answered for an AniList show with no episode, so the player's label stayed a bare code,
 *  3. TMDB's episode groups (its absolute order) were never read, so "123" had no place in TMDB's
 *     21 seasons, and
 *  4. AniList's `streamingEpisodes` were never asked for.
 */
class AnimeEpisodeCauseTest {

    private var clock = 1_800_000_000_000L
    private val world = AnimeWorld()
    private val dir: File = Files.createTempDirectory("tm-anime").toFile()

    private fun metadata(): OnlineMetadata {
        val store = MetadataStore(File(dir, "metadata.properties"))
        store.update { it.copy(enabled = true) }
        return OnlineMetadata(
            buildKey = "build-key",
            store = store,
            cache = MetadataCache(File(dir, "cache"), now = { clock }),
            appVersion = "test",
            http = world,
            now = { clock },
            sleep = { clock += it },
            language = { "en" },
        )
    }

    private val file = "[SubsPlease] Naruto Shippuden - 123 (1080p) [ABCD1234].mkv"

    private fun show(online: OnlineMetadata): MetaInfo {
        val query = MetaQuery.of(file) ?: error("no query")
        assertTrue("the fansub shape reads as anime", query.anime)
        assertEquals("absolute number as episode 123 of season 1", 123, query.episode)
        return (runBlocking { online.lookup(query.showOnly()) } as MetaResult.Found).info
    }

    @Test
    fun aShowMatchedOnAniListHasAnEpisodeList() = runBlocking {
        world.tmdbAbsoluteGroup = true
        val online = metadata()
        val info = show(online)
        assertEquals(MetaProvider.AniList, info.provider)
        val listed = online.season(info, 1)
        assertNotNull("AniList lists no episodes, so the series page had nothing to name or draw", listed)
        assertEquals("one season counted straight through", AnimeFixtures.TOTAL, listed!!.size)
        assertEquals(AnimeFixtures.title(123), EpisodeNames.listName(1, 123, listed))
        assertTrue("with its still", EpisodeNames.listed(1, 123, listed)?.stillUrl != null)
    }

    @Test
    fun theLabelOfAnAbsolutelyNumberedFileGetsTheEpisodesName() = runBlocking {
        world.tmdbAbsoluteGroup = true
        val online = metadata()
        val tag = EpisodeTag(code = "S01E123", show = "Naruto Shippuden", season = 1, episode = 123)
        val named = EpisodeNames.named(tag, null, online)
        assertEquals(AnimeFixtures.title(123), named.name)
    }

    @Test
    fun tmdbsAbsoluteOrderGroupIsRead() = runBlocking {
        world.tmdbAbsoluteGroup = true
        val online = metadata()
        online.season(show(online), 1)
        assertTrue("episode_groups asked", world.count("episode_groups") > 0)
        assertTrue("the absolute group read", world.count("tv/episode_group/") > 0)
    }

    @Test
    fun aniListsStreamingEpisodesAreAsked() = runBlocking {
        world.tmdb = false
        world.kitsu = false
        world.jikan = false
        world.streaming = 1..30
        val online = metadata()
        val listed = online.season(show(online), 1)
        assertTrue("the streaming list is asked for", world.count("streamingEpisodes") > 0)
        assertEquals(AnimeFixtures.streamTitle(7), listed?.let { EpisodeNames.listName(1, 7, it) })
    }
}
