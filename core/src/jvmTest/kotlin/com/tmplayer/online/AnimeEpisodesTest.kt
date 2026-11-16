package com.tmplayer.online

import com.tmplayer.online.AnimeEpisodes.Piece
import com.tmplayer.online.AnimeEpisodes.Source
import com.tmplayer.online.AnimeEpisodes.SourceList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The anime episode guide: the pure resolver, then each source's order, over a fake network. */
class AnimeEpisodesTest {

    // ---- the pure resolver ------------------------------------------------------------------

    private val layout = AnimeEpisodes.layout(AnimeFixtures.seasonSizes.mapIndexed { i, n -> MetaSeason(i + 1, n) })

    private fun guideOf(vararg lists: SourceList, next: MetaAiring? = null) = AnimeEpisodes.merge(lists.toList(), layout, next)

    private fun tmdbSeasonsList(): SourceList {
        val bySeason = AnimeFixtures.seasonSizes.mapIndexed { i, size ->
            val offset = AnimeFixtures.seasonSizes.take(i).sum()
            (i + 1) to (1..size).map { e -> MetaEpisode(i + 1, e, AnimeFixtures.title(offset + e), stillUrl = "https://image.tmdb.org/t/p/w300/s${offset + e}.jpg", airDate = AnimeFixtures.date(offset + e)) }
        }.toMap()
        return AnimeEpisodes.fromSeasons(bySeason, layout)
    }

    @Test
    fun absoluteAndSeasonNumbersMapBothWays() {
        val guide = guideOf(tmdbSeasonsList())
        assertEquals(500, guide.total)
        assertEquals(1 to 1, guide.locate(1))
        assertEquals(1 to 32, guide.locate(32))
        assertEquals(2 to 1, guide.locate(33))
        assertEquals(21 to AnimeFixtures.seasonSizes.last(), guide.locate(500))
        assertNull("past the end", guide.locate(501))
        for (n in 1..500) {
            val (s, e) = guide.locate(n)!!
            assertEquals("S${s}E$e", n, guide.absoluteOf(s, e))
        }
    }

    @Test
    fun aNumberPastItsSeasonIsTheAbsoluteOne() {
        val guide = guideOf(tmdbSeasonsList())
        assertEquals("S01E123 of a 32 episode season", 123, guide.absoluteOf(1, 123))
        assertEquals("S02E57 of a 21 episode season", 57, guide.absoluteOf(2, 57))
        assertEquals("a per season file", 33 + 4, guide.absoluteOf(2, 5))
        assertNull("specials never map", guide.absoluteOf(0, 5))
        assertNull("past the whole run", guide.absoluteOf(1, 501))
        assertNull(guide.absoluteOf(1, 0))
        assertNull(guide.absoluteOf(1, -3))
    }

    @Test
    fun withNoSeasonListOnlySeasonOneIsTakenStraight() {
        val kitsu = AnimeEpisodes.fromNumbered(Source.Kitsu, (1..24).map { MetaEpisode(1, it, "T$it x") })
        val guide = AnimeEpisodes.merge(listOf(kitsu), emptyList(), null)
        assertEquals(7, guide.absoluteOf(1, 7))
        assertNull("S2 of a show no list breaks into seasons: nothing says where it falls", guide.absoluteOf(2, 5))
        assertNull(guide.locate(7))
    }

    @Test
    fun theFirstSourceWithATitleGivesIt() {
        val group = SourceList(Source.TmdbGroup, listOf(Piece(1, name = "Real Title", season = 1, episode = 1), Piece(2, name = "Episode 2", season = 1, episode = 2)))
        val kitsu = SourceList(Source.Kitsu, listOf(Piece(1, name = "Kitsu one", overview = "Kitsu words", stillUrl = "https://media.kitsu.app/1.jpg"), Piece(2, name = "Kitsu two")))
        val jikan = SourceList(Source.Jikan, listOf(Piece(1, name = "Jikan one", airDate = "2007-02-15"), Piece(2, name = "Jikan two", airDate = "2007-02-22")))
        val guide = AnimeEpisodes.merge(listOf(group, kitsu, jikan), emptyList(), null)
        val one = guide.entry(1)!!
        assertEquals("Real Title", one.name)
        assertEquals(Source.TmdbGroup, one.source)
        assertEquals("the overview is the first that has one", "Kitsu words", one.overview)
        assertEquals("the picture too, from the source that has one", "https://media.kitsu.app/1.jpg", one.stillUrl)
        assertEquals(Source.Kitsu, one.stillSource)
        assertEquals("the date from the first source dating it", "2007-02-15", one.airDate)
        val two = guide.entry(2)!!
        assertEquals("a placeholder is no title: the next source's stands", "Kitsu two", two.name)
        assertEquals(Source.Kitsu, two.source)
        assertEquals("TMDB's own place for it is kept", 1 to 1, one.season to one.episode)
    }

    @Test
    fun anEpisodeNoSourceNamesStaysUnnamedNotNumbered() {
        val list = SourceList(Source.Jikan, listOf(Piece(3, name = "Episode 3"), Piece(4, name = "  ")))
        val guide = AnimeEpisodes.merge(listOf(list), emptyList(), null)
        assertEquals("", guide.entry(3)!!.name)
        assertEquals("", guide.entry(4)!!.name)
        assertNull(guide.entry(3)!!.source)
    }

    @Test
    fun aPictureFromAnotherHostIsNeverTaken() {
        val list = SourceList(Source.Kitsu, listOf(Piece(1, name = "A b", stillUrl = "https://evil.example/x.jpg"), Piece(2, name = "C d", stillUrl = "http://media.kitsu.app/2.jpg")))
        val guide = AnimeEpisodes.merge(listOf(list), emptyList(), null)
        assertNull(guide.entry(1)!!.stillUrl)
        assertNull("not https", guide.entry(2)!!.stillUrl)
    }

    @Test
    fun aListThatIsAnotherShowIsNotPlausible() {
        fun list(total: Int) = SourceList(Source.TmdbGroup, listOf(Piece(total, name = "x y")))
        assertTrue("the same run", AnimeEpisodes.plausible(500, list(500)))
        assertTrue("one episode short, TMDB not yet updated", AnimeEpisodes.plausible(500, list(496)))
        assertFalse("Naruto (220) for Naruto Shippuden (500)", AnimeEpisodes.plausible(500, list(220)))
        assertTrue("a cour of 12 in a show TMDB lists as 24", AnimeEpisodes.plausible(12, list(24)))
        assertFalse("a 12 episode entry against a thousand", AnimeEpisodes.plausible(12, list(1000)))
        assertTrue("nothing to compare", AnimeEpisodes.plausible(null, list(7)))
        assertFalse("an empty list is no list", AnimeEpisodes.plausible(12, SourceList(Source.Kitsu, emptyList())))
    }

    @Test
    fun theSeasonViewIsOneSeasonCountedStraightThrough() {
        val guide = guideOf(tmdbSeasonsList(), next = MetaAiring(1, 501, "2099-01-01"))
        val flat = AnimeEpisodes.seasonEpisodes(guide, 1)
        assertEquals(500, flat.size)
        assertTrue(flat.all { it.season == 1 })
        assertEquals(AnimeFixtures.title(123), flat[122].name)
        assertEquals("a season's own list is its own numbers", AnimeFixtures.seasonSizes[1], AnimeEpisodes.seasonEpisodes(guide, 2).size)
        assertEquals(AnimeFixtures.title(33), AnimeEpisodes.seasonEpisodes(guide, 2).first().name)
        assertEquals(listOf(MetaSeason(1, 500, AnimeFixtures.date(1))), AnimeEpisodes.seasonList(guide))
    }

    @Test
    fun anAiredEpisodeWithNoDateIsNeverToAir() {
        val list = SourceList(Source.Jikan, listOf(Piece(1, name = "A b", airDate = "2020-01-01"), Piece(2, name = "C d"), Piece(3, name = "E f")))
        val guide = AnimeEpisodes.merge(listOf(list), emptyList(), MetaAiring(1, 3, "2099-05-05"))
        val flat = AnimeEpisodes.flat(guide)
        assertEquals("takes the date before it", "2020-01-01", flat[1].airDate)
        assertNull("the one that has not aired stays undated", flat[2].airDate)
        assertEquals("the next episode is one past the last listed", 3, guide.total)
    }

    @Test
    fun theOverlayAddsTheRunToAShowAniListListsNoSeasonsFor() {
        val guide = guideOf(tmdbSeasonsList(), next = MetaAiring(1, 501, "2099-01-01"))
        val extras = AnimeEpisodes.overlay(MetaExtras(genres = listOf("Action")), guide)
        assertEquals(listOf("Action"), extras.genres)
        assertEquals(1, extras.seasonList.size)
        assertEquals(500, extras.seasonList.single().episodeCount)
        assertEquals(501, extras.nextEpisode?.episode)
        val tmdb = MetaExtras(seasonList = listOf(MetaSeason(1, 3)))
        assertEquals("a show with seasons of its own is left alone", tmdb, AnimeEpisodes.overlay(tmdb, guide))
    }

    @Test
    fun streamingTitlesCarryTheirNumber() {
        assertEquals(Piece(12, name = "The Title", stillUrl = "https://x.test/a.jpg"), AnimeEpisodes.streamingPiece("Episode 12 - The Title", "https://x.test/a.jpg"))
        assertEquals("E7 - Name x", AnimeEpisodes.streamingPiece("E7 - Name x", null)?.let { "E${it.absolute} - ${it.name}" })
        assertNull("a title with no number is not trusted with a place", AnimeEpisodes.streamingPiece("Naruto Shippuden Opening 2", null))
        assertNull(AnimeEpisodes.streamingPiece("Episode 99999 - Huge", null))
        assertNull(AnimeEpisodes.streamingPiece("", null))
    }

    @Test
    fun datesAreCivil() {
        assertEquals("1970-01-01", AnimeEpisodes.dateOf(0))
        assertEquals("2000-02-29", AnimeEpisodes.dateOf(951_782_400))
        assertEquals("2026-11-06", AnimeEpisodes.dateOf(1_794_000_000))
        assertNull(AnimeEpisodes.dateOf(-1))
        assertNull(AnimeEpisodes.dateOf(Long.MAX_VALUE))
    }

    @Test
    fun garbageNeverThrowsAndIsBounded() {
        val rng = kotlin.random.Random(11)
        val pieces = (0 until 20_000).map {
            Piece(
                absolute = rng.nextInt(20_000) - 5_000,
                name = if (rng.nextBoolean()) "x".repeat(rng.nextInt(2_000)) else "Episode ${rng.nextInt(50)}",
                overview = "<b>".repeat(rng.nextInt(50)),
                stillUrl = listOf(null, "ftp://x", "https://image.tmdb.org/t/p/w300/${rng.nextInt()}.jpg").random(rng),
                airDate = listOf(null, "yesterday", "2020-01-01").random(rng),
                runtimeMin = rng.nextInt(5_000) - 100,
                season = rng.nextInt(400) - 100,
                episode = rng.nextInt(400) - 100,
            )
        }
        val guide = AnimeEpisodes.merge(
            listOf(SourceList(Source.Kitsu, pieces)),
            listOf(MetaSeason(-4, 10), MetaSeason(1, -5), MetaSeason(1, 99_999), MetaSeason(3, 12), MetaSeason(3, 14), MetaSeason(9_999, 5)),
            MetaAiring(-1, -1, "x"),
        )
        assertTrue(guide.entries.size <= AnimeEpisodes.MAX_EPISODES)
        assertTrue(guide.entries.all { it.absolute in 1..AnimeEpisodes.MAX_EPISODES && it.name.length <= 300 && it.overview.length <= 4_000 })
        assertEquals("only a numbered season of a sane count is laid out, once", listOf(3), guide.layout.map { it.number })
        assertEquals(guide.total.coerceAtMost(AnimeEpisodes.MAX_EPISODES), AnimeEpisodes.flat(guide).size)
        runCatching { guide.absoluteOf(Int.MAX_VALUE, Int.MAX_VALUE) }.getOrNull()
        assertNull(AnimeEpisodes.fromSeasons(emptyMap(), emptyList()).pieces.firstOrNull())
        assertTrue(AnimeEpisodes.fromGroup(emptyList()).pieces.isEmpty())
        assertTrue(AnimeEpisodes.layout(listOf(MetaSeason(1, 5), MetaSeason(1, 6))).size == 1)
    }

    // ---- over a fake network ----------------------------------------------------------------

    private var clock = 1_800_000_000_000L
    private val world = AnimeWorld()
    private var dir: File = Files.createTempDirectory("tm-anime").toFile()

    private fun metadata(enabled: Boolean = true, tmdbKey: String = "build-key"): OnlineMetadata {
        val store = MetadataStore(File(dir, "metadata.properties"))
        store.update { it.copy(enabled = enabled) }
        return OnlineMetadata(
            buildKey = tmdbKey,
            store = store,
            cache = MetadataCache(File(dir, "cache"), now = { clock }),
            appVersion = "test",
            http = world,
            now = { clock },
            sleep = { clock += it },
            language = { "en" },
        )
    }

    private fun show(online: OnlineMetadata, file: String = "[SubsPlease] Naruto Shippuden - 123 (1080p) [ABCD1234].mkv"): MetaInfo {
        val query = MetaQuery.of(file) ?: error("no query")
        return (runBlocking { online.lookup(query.showOnly()) } as MetaResult.Found).info
    }

    @Test
    fun theAbsoluteGroupIsTakenFirstAndNamesEveryEpisode() = runBlocking {
        world.tmdbAbsoluteGroup = true
        val online = metadata()
        val guide = online.animeGuide(show(online))!!
        assertEquals(500, guide.entries.size)
        assertTrue(guide.entries.all { it.name == AnimeFixtures.title(it.absolute) && it.source == Source.TmdbGroup })
        assertEquals(AnimeFixtures.place(123), guide.locate(123))
        assertEquals("a still for each, from TMDB", "https://image.tmdb.org/t/p/w300/still123.jpg", guide.entry(123)!!.stillUrl)
        assertEquals("Kitsu and Jikan are not asked when TMDB named everything", 0, world.count("kitsu.io") + world.count("jikan.moe"))
    }

    @Test
    fun withoutAGroupTmdbsSeasonsAreCountedInOrderInTwoCalls() = runBlocking {
        val online = metadata()
        val guide = online.animeGuide(show(online))!!
        assertEquals(500, guide.entries.size)
        assertTrue(guide.entries.all { it.source == Source.TmdbSeasons })
        assertEquals("21 seasons are two appended calls, not 21", 2, world.count("append_to_response=season"))
        assertEquals(0, world.count("/season/1?"))
        assertEquals(AnimeFixtures.title(500), guide.entry(500)!!.name)
    }

    @Test
    fun withoutTmdbKitsuAnswersByTheMalId() = runBlocking {
        world.tmdb = false
        val online = metadata()
        val guide = online.animeGuide(show(online))!!
        assertEquals(500, guide.entries.size)
        assertTrue(guide.entries.all { it.source == Source.Kitsu })
        assertEquals(AnimeFixtures.kitsuTitle(123), guide.entry(123)!!.name)
        assertEquals("https://media.kitsu.app/episodes/123.jpg", guide.entry(123)!!.stillUrl)
        assertEquals("one mapping call, 25 pages of twenty, no call per row", 1, world.count("mappings"))
        assertEquals(25, world.count("offset%5D="))
        assertEquals("Jikan is not asked once Kitsu named everything", 0, world.count("jikan.moe"))
        assertNull("Kitsu knows no TMDB place", guide.locate(123))
    }

    @Test
    fun withoutTmdbOrKitsuJikanGivesTitlesAndDates() = runBlocking {
        world.tmdb = false
        world.kitsu = false
        val online = metadata()
        val guide = online.animeGuide(show(online))!!
        assertTrue(guide.entries.all { it.source == Source.Jikan })
        assertEquals(AnimeFixtures.jikanTitle(300), guide.entry(300)!!.name)
        assertEquals(AnimeFixtures.date(300), guide.entry(300)!!.airDate)
        assertEquals("five pages of a hundred", 5, world.count("api.jikan.moe"))
    }

    @Test
    fun aniListsStreamingListIsTheLastResort() = runBlocking {
        world.tmdb = false
        world.kitsu = false
        world.jikan = false
        world.streaming = 1..40
        val online = metadata()
        val guide = online.animeGuide(show(online))!!
        assertEquals(AnimeFixtures.streamTitle(12), guide.entry(12)!!.name)
        assertEquals(Source.AniList, guide.entry(12)!!.source)
        assertEquals("https://img1.ak.crunchyroll.com/i/12.jpg", guide.entry(12)!!.stillUrl)
        assertEquals(40, guide.entries.size)
    }

    @Test
    fun laterSourcesFillOnlyTheTitlesTheEarlierOnesLack() = runBlocking {
        // TMDB lists episodes 1 to 450 only (its list is a little short); Kitsu has all 500.
        world.tmdbSizes = AnimeFixtures.seasonSizes.dropLast(1) + (AnimeFixtures.seasonSizes.last() - 49)
        val online = metadata()
        val guide = online.animeGuide(show(online))!!
        assertEquals("TMDB's title where it has one", Source.TmdbSeasons, guide.entry(100)!!.source)
        assertEquals("Kitsu's where TMDB has none", AnimeFixtures.kitsuTitle(480), guide.entry(480)!!.name)
        assertEquals(Source.Kitsu, guide.entry(480)!!.source)
    }

    @Test
    fun aWrongMatchIsLeftOut() = runBlocking {
        // TMDB's "Naruto" has 220 episodes in 9 seasons: not the 500 episode run AniList counts.
        world.tmdbSizes = listOf(26, 26, 26, 26, 26, 26, 26, 26, 12)
        world.kitsu = false
        world.jikan = false
        val online = metadata()
        val guide = online.animeGuide(show(online))
        assertTrue("no title from the other show", guide == null || guide.entries.none { it.name.isNotBlank() })
        assertTrue("and none of its seasons are laid over the run", guide == null || guide.layout.isEmpty())
    }

    @Test
    fun oneAnswerPerShowNotPerRow() = runBlocking {
        world.tmdbAbsoluteGroup = true
        val online = metadata()
        val info = show(online)
        val before = world.requests.size
        for (n in 1..500) {
            val q = MetaQuery(title = "Naruto Shippuden", season = 1, episode = n, anime = true)
            val found = (online.lookup(q) as MetaResult.Found).info.episode
            assertEquals("episode $n", AnimeFixtures.title(n), found?.name)
        }
        val asked = world.requests.size - before
        assertTrue("500 rows asked $asked times", asked <= 8)
        assertEquals(1, world.count("tv/episode_group/"))
        assertNotNull(info)
    }

    @Test
    fun theGuideIsKeptAndSurvivesARestartOffline() = runBlocking {
        world.tmdbAbsoluteGroup = true
        val first = metadata()
        first.animeGuide(show(first))!!
        world.offline = true
        val second = metadata()
        val info = (second.lookup(MetaQuery.of("[SubsPlease] Naruto Shippuden - 123 (1080p).mkv")!!.showOnly()) as MetaResult.Found).info
        val guide = second.animeGuide(info)
        assertNotNull("kept for a day", guide)
        assertEquals(AnimeFixtures.title(77), guide!!.entry(77)!!.name)
    }

    @Test
    fun offlineWithNothingKeptAnswersNothingAndBreaksNothing() = runBlocking {
        world.offline = true
        val online = metadata()
        val info = MetaInfo(MetaProvider.AniList, "1735", MetaKind.Show, "Naruto Shippuden")
        assertNull(online.animeGuide(info))
        assertNull(online.season(info, 1))
        assertEquals(MetaExtras(), online.animeExtras(info, MetaExtras()))
        assertNull(online.peekAnimeGuide(info))
    }

    @Test
    fun aGuideCutShortByTheNetworkIsNotKeptAndResumesLater() = runBlocking {
        world.tmdb = false
        world.jikan = false
        var served = 0
        val flaky = HttpTransport { request ->
            if ("kitsu.io/api/edge/anime/" in request.url && served++ >= 3) throw com.tmplayer.platform.IOException("dropped")
            world.send(request)
        }
        val store = MetadataStore(File(dir, "metadata.properties"))
        store.update { it.copy(enabled = true) }
        val cache = MetadataCache(File(dir, "cache"), now = { clock })
        fun online(http: HttpTransport) = OnlineMetadata("build-key", store, cache, "test", http, { clock }, { clock += it }, { "en" })
        val cut = online(flaky)
        val info = show(cut)
        val partial = cut.animeGuide(info)
        assertTrue("a few pages' titles are used for now", partial != null && partial.entries.size in 20..80)
        assertNull("but the guide is not kept in memory", cut.peekAnimeGuide(info))
        val whole = online(world).animeGuide(info)!!
        assertEquals(500, whole.entries.size)
    }

    @Test
    fun theFilesOwnNumbersFindTheirNameThroughAnyNumbering() = runBlocking {
        world.tmdbAbsoluteGroup = true
        val online = metadata()
        // A file numbered per TMDB season: S06E12 is absolute 12 + the first five seasons.
        val (season, episode) = AnimeFixtures.place(150)
        val perSeason = (online.lookup(MetaQuery(title = "Naruto Shippuden", season = season, episode = episode, anime = true)) as MetaResult.Found).info.episode
        assertEquals(AnimeFixtures.title(150), perSeason?.name)
        assertEquals("carrying the file's own numbers so the label takes it", season to episode, perSeason?.season to perSeason?.number)
        // A file counted straight through: S01E150.
        val straight = (online.lookup(MetaQuery(title = "Naruto Shippuden", season = 1, episode = 150, anime = true)) as MetaResult.Found).info.episode
        assertEquals(AnimeFixtures.title(150), straight?.name)
    }

    @Test
    fun aCourSplitShowIsNamedByTheRunItBelongsTo() = runBlocking {
        // AniList lists cour one alone (12 episodes); TMDB lists the whole show as 2 seasons of 12.
        world.entryEpisodes = 12
        world.tmdbSizes = listOf(12, 12)
        world.tmdbAbsoluteGroup = false
        world.kitsu = false
        world.jikan = false
        val online = metadata()
        val info = show(online)
        val guide = online.animeGuide(info)!!
        assertEquals(24, guide.total)
        assertEquals("a file of cour two numbered on from cour one", AnimeFixtures.title(14), guide.entry(guide.absoluteOf(1, 14)!!)!!.name)
        assertEquals("a file of cour two numbered from one again", AnimeFixtures.title(14), guide.entry(guide.absoluteOf(2, 2)!!)!!.name)
    }
}
