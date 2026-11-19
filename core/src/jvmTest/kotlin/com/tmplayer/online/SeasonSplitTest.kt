package com.tmplayer.online

import com.tmplayer.data.EpisodeGuide
import com.tmplayer.data.MediaItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

/**
 * A show TMDB lists as one season that its own episode group splits in several ([SeasonSplit]),
 * the shape of Blue Box (tv 207347, read live 2026-11-19): one season of 37 on its page, and a
 * "Seasons" group of 25 + 12, a "Cours (Path)" group of three and a "Blu-ray (ja-JP)" group of 25.
 */
class SeasonSplitTest {

    private val show = MetaInfo(MetaProvider.Tmdb, "207347", MetaKind.Show, "Blue Box", 2024)

    private fun air(n: Int): String =
        if (n <= 25) LocalDate.of(2024, 10, 3).plusWeeks(n - 1L).toString() else LocalDate.of(2026, 10, 2).plusWeeks(n - 26L).toString()

    /** TMDB names episodes up to 31; 32 on are announced without a title yet. */
    private fun name(n: Int): String = if (n <= 31) "The ${n}th Tide" else ""

    private fun page(seasons: String = """[{"season_number":0,"episode_count":2},{"season_number":1,"episode_count":37,"air_date":"2024-10-03"}]""", next: Int? = 33) =
        """{"id":207347,"genres":[{"name":"Animation"}],"number_of_seasons":1,"status":"Returning Series","seasons":$seasons,
        ${next?.let { """"next_episode_to_air":{"season_number":1,"episode_number":$it,"air_date":"${air(it)}","name":""},""" } ?: ""}
        "vote_average":8.1,"vote_count":300,
        "episode_groups":{"results":[
          {"id":"bluray","name":"Blu-ray (ja-JP)","type":3,"group_count":3,"episode_count":25},
          {"id":"cours","name":"Cours (Path)","type":7,"group_count":3,"episode_count":37},
          {"id":"seasons","name":"Seasons","type":6,"group_count":2,"episode_count":37}]}}"""

    private fun episode(n: Int, order: Int) =
        """{"season_number":1,"episode_number":$n,"order":$order,"name":"${name(n)}","overview":"About $n","air_date":"${air(n)}","runtime":24,"still_path":"/s$n.jpg"}"""

    private val seasonsGroup = """{"id":"seasons","name":"Seasons","groups":[
        {"name":"Season 2","order":2,"episodes":[${(26..37).joinToString(",") { episode(it, it - 26) }}]},
        {"name":"Season 1","order":1,"episodes":[${(1..25).joinToString(",") { episode(it, it - 1) }}]}]}"""

    private val ownSeason = """{"episodes":[${(1..37).joinToString(",") { """{"episode_number":$it,"name":"${name(it)}","air_date":"${air(it)}"}""" }}]}"""

    private inner class World : HttpTransport {
        val requests = mutableListOf<String>()
        var page = page()
        var group: () -> HttpResponse = { ok(seasonsGroup) }

        fun ok(text: String) = HttpResponse(200, body = text.toByteArray())

        fun count(part: String) = requests.count { part in it }

        override suspend fun send(request: HttpRequest): HttpResponse {
            requests += request.url
            val url = request.url
            return when {
                "tv/episode_group/" in url -> group()
                "tv/207347/season/1" in url -> ok(ownSeason)
                "tv/207347" in url || "tv/1396" in url -> ok(page)
                else -> HttpResponse(404, body = "{}".toByteArray())
            }
        }
    }

    private var clock = 1_800_000_000_000L
    private val dir: File = Files.createTempDirectory("tm-split").toFile()
    private val world = World()

    private fun metadata() = OnlineMetadata(
        buildKey = "build-key",
        store = MetadataStore(File(dir, "metadata.properties")).also { s -> s.update { it.copy(enabled = true) } },
        cache = MetadataCache(File(dir, "cache"), now = { clock }),
        appVersion = "test",
        http = world,
        now = { clock },
        sleep = { clock += it },
        language = { "en" },
        region = { "GB" },
    )

    private fun video(id: Long, name: String) = MediaItem(
        chatId = 1, messageId = id, fileId = id.toInt(), title = name, sizeBytes = 100, durationSec = 1_400,
        mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null, date = id.toInt(), fileName = name,
    )

    private fun chat(season: Int, numbers: IntRange) = numbers.map { video(it.toLong(), "Blue.Box.S%02dE%02d.1080p.mkv".format(season, it)) }

    private val today = "2026-11-19"

    // ---- choosing the group ---------------------------------------------------------------------

    private val oneSeason = listOf(MetaSeason(0, 2), MetaSeason(1, 37))
    private val groups = listOf(
        TmdbEpisodeGroup("bluray", 3, 25, "Blu-ray (ja-JP)", 3),
        TmdbEpisodeGroup("cours", 7, 37, "Cours (Path)", 3),
        TmdbEpisodeGroup("seasons", 6, 37, "Seasons", 2),
    )

    @Test
    fun theSeasonsGroupIsChosenOverCoursAndAShortBluRay() {
        assertEquals("seasons", SeasonSplit.choose(oneSeason, groups)?.id)
        // Without it the cours split stands; the Blu-ray group holds 25 of 37, too few.
        assertEquals("cours", SeasonSplit.choose(oneSeason, groups.filter { it.id != "seasons" })?.id)
        assertNull(SeasonSplit.choose(oneSeason, groups.filter { it.id == "bluray" }))
    }

    @Test
    fun aNameStartingWithSeasonBreaksATie() {
        val tied = listOf(TmdbEpisodeGroup("a", 7, 37, "Cours", 2), TmdbEpisodeGroup("b", 6, 36, "Season order", 2))
        assertEquals("b", SeasonSplit.choose(oneSeason, tied)?.id)
    }

    @Test
    fun aShowListingSeveralSeasonsOrAbsoluteBlocksIsNeverSplit() {
        assertNull(SeasonSplit.choose(listOf(MetaSeason(1, 20), MetaSeason(2, 17)), groups + TmdbEpisodeGroup("x", 6, 37, "Seasons", 3)))
        assertNull(SeasonSplit.choose(oneSeason, listOf(TmdbEpisodeGroup("abs", 2, 37, "Absolute", 2))))
        assertNull(SeasonSplit.choose(oneSeason, listOf(TmdbEpisodeGroup("one", 6, 37, "Seasons", 1))))
        assertNull(SeasonSplit.choose(emptyList(), groups))
    }

    @Test
    fun garbageGroupsNeverThrowAndSplitNothing() {
        val junk = listOf(
            TmdbEpisodeGroup("", 6, 37, "Seasons", 2),
            TmdbEpisodeGroup("huge", 6, Int.MAX_VALUE, "Seasons", Int.MAX_VALUE),
            TmdbEpisodeGroup("neg", 6, -5, "", -1),
            TmdbEpisodeGroup("zero", 0, 0, "", 0),
        )
        assertNull(SeasonSplit.choose(oneSeason, junk))
        assertNull(SeasonSplit.choose(listOf(MetaSeason(1, Int.MAX_VALUE)), junk))
        assertNull(SeasonSplit.choose(listOf(MetaSeason(1, -3)), groups))
        val piece = AnimeEpisodes.Piece(0)
        // One group, nonsense numbers, every episode in the same place: no split.
        assertNull(SeasonSplit.build("g", listOf(AnimeEpisodes.GroupEpisode(0, 0, 1, 1, piece)), oneSeason))
        assertNull(SeasonSplit.build("g", (1..37).map { AnimeEpisodes.GroupEpisode(it % 2, it, -1, Int.MAX_VALUE, piece) }, oneSeason))
        assertNull(SeasonSplit.build("g", (1..37).map { AnimeEpisodes.GroupEpisode(it % 2, it, 1, 1, piece) }, oneSeason))
        assertNull(SeasonSplit.read(null))
        assertEquals(emptyList<AnimeEpisodes.GroupEpisode>(), SeasonSplit.read(SeasonSplit.store(emptyList())))
    }

    @Test
    fun anUntitledEpisodeKeepsNoPlaceholderCountedInTheShowsOwnNumbers() {
        // TMDB titles an announced episode "Episode 32" (its place in the one-season count); in season 2 it is episode 7.
        val episodes = (1..37).map {
            AnimeEpisodes.GroupEpisode(if (it <= 25) 1 else 2, it, 1, it, AnimeEpisodes.Piece(0, if (it == 32) "Episode 32" else name(it), airDate = air(it)))
        }
        val split = SeasonSplit.build("seasons", episodes, oneSeason) ?: error("no split")
        val seventh = split.episodes[2]?.firstOrNull { it.number == 7 } ?: error("no E7")
        assertEquals("", seventh.name)
        assertEquals("The 26th Tide", split.episodes[2]?.first()?.name)
    }

    @Test
    fun theSplitNumbersEachSeasonFromOneAndPlacesTheNextEpisode() {
        val episodes = (1..37).map { AnimeEpisodes.GroupEpisode(if (it <= 25) 1 else 2, it, 1, it, AnimeEpisodes.Piece(0, name(it), airDate = air(it))) }
        val split = SeasonSplit.build("seasons", episodes, oneSeason) ?: error("no split")
        assertEquals(listOf(MetaSeason(1, 25, "2024-10-03"), MetaSeason(2, 12, "2026-10-02")), split.seasons)
        assertEquals((1..12).toList(), split.episodes[2]?.map { it.number })
        assertEquals("The 26th Tide", split.episodes[2]?.first()?.name)
        assertEquals(MetaAiring(2, 8, air(33)), split.next(MetaAiring(1, 33, air(33))))
        // An episode TMDB announced past the group's last counts on in the last season.
        assertEquals(MetaAiring(2, 13, "2027-01-01"), split.next(MetaAiring(1, 38, "2027-01-01")))
        assertNull(split.next(MetaAiring(3, 1, "2027-01-01")))
        // Kept and read back, the same split.
        val again = SeasonSplit.build("seasons", SeasonSplit.read(SeasonSplit.store(episodes)) ?: error("not read"), oneSeason)
        assertEquals(split.seasons, again?.seasons)
        assertEquals(split.episodes, again?.episodes)
    }

    // ---- end to end: Blue Box ---------------------------------------------------------------

    @Test
    fun blueBoxSeasonTwoHasItsTitlesDatesAndGaps() = runBlocking {
        val online = metadata()
        val extras = online.extras(show) ?: error("no extras")
        assertEquals(listOf(MetaSeason(1, 25, "2024-10-03"), MetaSeason(2, 12, "2026-10-02")), extras.seasonList)
        assertEquals(2, extras.seasons)
        assertEquals(MetaAiring(2, 8, air(33)), extras.nextEpisode)
        assertEquals("seasons", extras.seasonGroup)
        assertEquals(listOf(MetaSeason(1, 37, "2024-10-03")), extras.listedSeasons)
        assertEquals(1, world.count("tv/episode_group/seasons"))

        val items = chat(2, 1..6)
        val series = EpisodeGuide.seriesOf(items[0], items)
        assertTrue(EpisodeGuide.agrees(series, extras.seasonList))
        val numbering = EpisodeGuide.numbering(series, extras)
        assertTrue(numbering.split)
        val seasons = EpisodeGuide.seasons(series, numbering.seasons, numbering.next, today)
        assertEquals(listOf(1 to EpisodeGuide.State.Missing, 2 to EpisodeGuide.State.InChat), seasons.map { it.number to it.state })
        assertEquals(12, seasons[1].total)

        val listed = online.season(show, 2) ?: error("no season 2")
        assertEquals((1..12).toList(), listed.map { it.number })
        val rows = EpisodeGuide.rows(2, series, listed, today, seasons[1].let { MetaSeason(2, 12, it.airDate) }, numbering.next)
        assertEquals(6, rows.count { it is EpisodeGuide.Row.Have })
        val gaps = rows.filterIsInstance<EpisodeGuide.Row.Gap>().map { it.episode }
        assertEquals((7..12).toList(), gaps.map { it.number })
        assertEquals((32..37).map { air(it) }, gaps.map { it.airDate })
        assertEquals(EpisodeGuide.State.Missing, gaps.first().state)
        assertEquals(EpisodeGuide.State.Upcoming, gaps[1].state)
        // The ones in the chat carry their titles; the untitled gaps fall back to their number on the page.
        assertEquals("The 26th Tide", (rows.first() as EpisodeGuide.Row.Have).meta?.name)
        assertEquals("", gaps.first().meta?.name)
        assertEquals(24, listed.first().runtimeMin)
        assertEquals("https://image.tmdb.org/t/p/w300/s26.jpg", listed.first().stillUrl)

        // Season 1 is the group's first 25, from the same answer: no more calls, and a restart reads the disk.
        assertEquals(25, online.season(show, 1)?.size)
        assertEquals(25, online.peekSeason(show, 1)?.size)
        val calls = world.requests.size
        val fresh = metadata()
        assertEquals(extras.seasonList, fresh.extras(show)?.seasonList)
        assertEquals(listed, fresh.season(show, 2))
        assertEquals(calls, world.requests.size)
    }

    @Test
    fun aChatCountingStraightThroughKeepsTmdbsOwnNumbering() = runBlocking {
        val online = metadata()
        val extras = online.extras(show) ?: error("no extras")
        val items = chat(1, 26..31)
        val series = EpisodeGuide.seriesOf(items[0], items)
        val numbering = EpisodeGuide.numbering(series, extras)
        assertFalse(numbering.split)
        assertEquals(listOf(MetaSeason(1, 37, "2024-10-03")), numbering.seasons)
        assertEquals(MetaAiring(1, 33, air(33), ""), numbering.next)
        val own = online.season(show, 1, split = false) ?: error("no season")
        assertEquals(37, own.size)
        assertEquals("The 31th Tide", own[30].name)
    }

    @Test
    fun aFailedGroupFallsBackUnsplitAndIsAskedAgain() = runBlocking {
        world.group = { HttpResponse(500, body = "{}".toByteArray()) }
        val online = metadata()
        val first = online.extras(show) ?: error("no extras")
        assertEquals(listOf(MetaSeason(1, 37, "2024-10-03")), first.seasonList)
        assertEquals(MetaAiring(1, 33, air(33), ""), first.nextEpisode)
        assertNull(first.seasonGroup)
        assertNull(online.peekExtras(show))
        // TMDB's own season answers meanwhile, as before.
        assertEquals(37, online.season(show, 1)?.size)

        world.group = { world.ok(seasonsGroup) }
        val second = online.extras(show) ?: error("no extras")
        assertEquals(2, second.seasonList.size)
        assertEquals(12, online.season(show, 2)?.size)
    }

    @Test
    fun aThrottledGroupIsNotKeptAndPausesTmdb() = runBlocking {
        world.group = { HttpResponse(429, headers = mapOf("Retry-After" to "30"), body = ByteArray(0)) }
        val online = metadata()
        assertEquals(1, online.extras(show)?.seasonList?.size)
        val calls = world.requests.size
        // Paused: nothing asked, nothing kept.
        assertNull(online.peekExtras(show))
        online.extras(show)
        assertEquals(calls, world.requests.size)
        clock += 31_000
        world.group = { world.ok(seasonsGroup) }
        assertEquals(2, online.extras(show)?.seasonList?.size)
    }

    @Test
    fun aGoneGroupLeavesTmdbsSeasonsAndIsKept() = runBlocking {
        world.group = { HttpResponse(404, body = "{}".toByteArray()) }
        val online = metadata()
        assertEquals(1, online.extras(show)?.seasonList?.size)
        assertEquals(1, online.peekExtras(show)?.seasonList?.size)
        assertEquals(1, world.count("tv/episode_group/"))
    }

    @Test
    fun aShowAlreadyListingTwoSeasonsIsUntouched() = runBlocking {
        world.page = page(seasons = """[{"season_number":1,"episode_count":25,"air_date":"2024-10-03"},{"season_number":2,"episode_count":12,"air_date":"2026-10-02"}]""", next = null)
        val other = MetaInfo(MetaProvider.Tmdb, "1396", MetaKind.Show, "Two Seasons")
        val extras = metadata().extras(other) ?: error("no extras")
        assertEquals(listOf(MetaSeason(1, 25, "2024-10-03"), MetaSeason(2, 12, "2026-10-02")), extras.seasonList)
        assertNull(extras.seasonGroup)
        assertTrue(extras.listedSeasons.isEmpty())
        assertEquals(0, world.count("tv/episode_group/"))
    }

    @Test
    fun extrasKeptFromBeforeTheGroupsAreAskedOnceAndKeepTheirScores() = runBlocking {
        val key = listOf("extras2", "Tmdb", "Show", "207347", OnlineMetadata.ENGLISH, "GB").joinToString("|")
        val old = MetaExtras(
            seasons = 1,
            seasonList = listOf(MetaSeason(1, 37, "2024-10-03")),
            scores = listOf(MetaScore(ScoreSource.Imdb, 8.4)),
            scoresAsked = true,
            scoresAt = clock / 1000,
            scoresFromOmdb = true,
        )
        MetadataCache(File(dir, "cache"), now = { clock }).putExtras(key, old)
        val online = metadata()
        val extras = online.extras(show) ?: error("no extras")
        assertEquals(2, extras.seasonList.size)
        assertTrue(extras.scores.any { it.source == ScoreSource.Imdb })
        assertTrue(extras.groupsRead)
        val calls = world.requests.size
        metadata().extras(show)
        assertEquals(calls, world.requests.size)
    }

    @Test
    fun theSplitSurvivesTheCachesJson() {
        val extras = MetaExtras(
            seasonList = listOf(MetaSeason(1, 25, "2024-10-03"), MetaSeason(2, 12)),
            nextEpisode = MetaAiring(2, 8, "2026-11-20"),
            seasonGroup = "seasons",
            listedSeasons = listOf(MetaSeason(1, 37)),
            listedNext = MetaAiring(1, 33, "2026-11-20"),
            groupsRead = true,
        )
        assertEquals(extras, MetaExtras.fromJson(extras.toJson()))
    }
}
