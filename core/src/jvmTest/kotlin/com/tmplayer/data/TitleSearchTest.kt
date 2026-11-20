package com.tmplayer.data

import com.tmplayer.online.HttpRequest
import com.tmplayer.online.HttpResponse
import com.tmplayer.online.HttpTransport
import com.tmplayer.online.MetadataCache
import com.tmplayer.online.MetadataStore
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.online.PosterChoice
import com.tmplayer.online.PosterChoices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * HX41: any film or show by its name, the copies the viewer has of it, and "Not in your chats"
 * when there are none. TMDB is a fake here: the build carries no key in tests, and with none the
 * search must still work from the chats alone.
 */
class TitleSearchTest {

    private fun video(
        chatId: Long,
        messageId: Long,
        fileName: String,
        width: Int = 0,
        height: Int = 0,
        duration: Int = 6_000,
    ) = MediaItem(
        chatId = chatId, messageId = messageId, fileId = messageId.toInt(), title = fileName,
        sizeBytes = 900 * SizeFilter.MB, durationSec = duration, mimeType = "video/x-matroska",
        thumbnailFileId = 0, miniThumbnail = null, date = messageId.toInt(), fileName = fileName,
        width = width, height = height,
    )

    private fun q(text: String) = GlobalSearch.parse(text, maxYear = 2026)

    private fun groups(vararg items: MediaItem, query: String) =
        GlobalSearch.merge(emptyList(), items.toList(), q(query), SearchSort.BestMatch, SearchIdentity.Default)

    private fun film(id: Int, title: String, year: Int?, language: String = "en", original: String = "") =
        PosterChoice(id, show = false, title = title, year = year, originalLanguage = language, overview = "", posterUrl = null, originalTitle = original)

    private fun show(id: Int, title: String, year: Int?, language: String = "en") =
        PosterChoice(id, show = true, title = title, year = year, originalLanguage = language, overview = "", posterUrl = null)

    private val none: (MediaItem) -> String? = { null }
    private val noHints: (Long) -> List<String> = { emptyList() }

    private fun titles(choices: List<PosterChoice>, groups: List<ResultGroup>, query: String, online: (MediaItem) -> String? = none, hints: (Long) -> List<String> = noHints) =
        GlobalSearch.titles(choices, groups, q(query), online, hints)

    // ---- what is asked --------------------------------------------------------------------------

    @Test
    fun theTitleQueryTakesATrailingYearButNotAYearThatIsTheTitle() {
        assertEquals("night train" to 2019, GlobalSearch.titleQuery(q("night train 2019"), 2026)?.let { it.title to it.year })
        assertEquals("1917" to null, GlobalSearch.titleQuery(q("1917"), 2026)?.let { it.title to it.year })
        // A year no film has yet is part of the title.
        assertEquals("Blade Runner 2049" to null, GlobalSearch.titleQuery(q("Blade Runner 2049"), 2026)?.let { it.title to it.year })
        // A quality is a filter, never part of the title.
        assertEquals("night train", GlobalSearch.titleQuery(q("night train 1080p"), 2026)?.title)
        assertNull(GlobalSearch.titleQuery(q("1080p"), 2026))
        assertNull(GlobalSearch.titleQuery(q(""), 2026))
        assertNull(GlobalSearch.titleQuery(q("a"), 2026))
    }

    // ---- copies under the title -----------------------------------------------------------------

    @Test
    fun copiesAreListedUnderTheirTitleRecommendedCopyFirst() {
        val sd = video(1, 10, "Night.Train.2019.480p.mkv")
        val hd = video(2, 20, "Night.Train.2019.1080p.mkv", 1920, 1080)
        val cam = video(3, 30, "Night.Train.2019.HDCAM.mkv", 1920, 1080)
        val found = groups(sd, hd, cam, query = "night train")
        val hits = titles(listOf(film(7, "Night Train", 2019)), found, "night train")
        assertEquals(1, hits.size)
        val hit = hits.single()
        assertTrue(hit.inChats)
        assertFalse(GlobalSearch.notInChats(hits))
        // HX38: the recommended copy leads, never the cam.
        assertEquals(hd.id, hit.best?.lead?.id)
        assertEquals(3, hit.fileCount)
        assertEquals(3, hit.chatCount)
    }

    @Test
    fun aTitleWithNoCopySaysNotInYourChats() {
        val other = groups(video(1, 10, "Harbour.Notes.2021.mkv"), query = "night")
        val hits = titles(listOf(film(7, "Night Train", 2019)), other, "night train")
        assertEquals(1, hits.size)
        assertTrue(hits.single().notInChats)
        assertTrue(GlobalSearch.notInChats(hits))
        assertNull(hits.single().best)
    }

    @Test
    fun withNoTitlesTheSearchStillListsTheChatsAndNeverSaysNotInYourChats() {
        // Lookups off, no key, offline: no titles, and the cards speak for themselves.
        val found = groups(video(1, 10, "Night.Train.2019.mkv"), query = "night train")
        val hits = titles(emptyList(), found, "night train")
        assertTrue(hits.isEmpty())
        assertFalse(GlobalSearch.notInChats(hits))
        assertEquals(1, found.size)
    }

    @Test
    fun aFilmWithACommonNameIsToldApartByTheYear() {
        val old = video(1, 10, "Idiot.1951.mkv")
        val new = video(2, 20, "Idiot.2022.1080p.mkv")
        val found = groups(old, new, query = "idiot")
        val hits = titles(listOf(film(1, "Idiot", 2022, "cs"), film(3, "The Idiot", 1951, "ja")), found, "idiot")
        val byId = hits.associateBy { it.title.tmdbId }
        assertEquals(listOf(new.id), byId.getValue(1).groups.map { it.lead.id })
        assertEquals(listOf(old.id), byId.getValue(3).groups.map { it.lead.id })
    }

    @Test
    fun twoTitlesOfOneNameAndYearAreToldApartByTheLanguageOrSaidToBeMaybe() {
        val tamil = video(1, 10, "Idiot.2022.Tamil.1080p.mkv")
        val plain = video(2, 20, "Idiot.2022.mkv")
        val choices = listOf(film(1, "Idiot", 2022, "cs"), film(2, "Idiot", 2022, "ta"))
        val hits = titles(choices, groups(tamil, plain, query = "idiot"), "idiot")
        val byId = hits.associateBy { it.title.tmdbId }
        // The Tamil name says which; the plain one could be either, so it is a copy of neither.
        assertTrue(byId.getValue(2).groups.any { g -> g.copies.any { it.id == tamil.id } })
        assertTrue(byId.getValue(1).groups.none { g -> g.copies.any { it.id == tamil.id } })
        val plainGroup = (byId.getValue(1).maybe + byId.getValue(2).maybe).flatMap { it.copies }.map { it.id }
        assertTrue(plain.id in plainGroup || byId.values.any { h -> h.groups.any { g -> g.copies.any { it.id == plain.id } } })
    }

    @Test
    fun theChatsLanguageHintDecidesWhereTheNameDoesNot() {
        // HX24: a chat named for its language. The file says nothing of it.
        val copy = video(40, 10, "Bigg.Boss.S08E01.mkv")
        val found = groups(copy, query = "bigg boss")
        val choices = listOf(show(1, "Bigg Boss", 2017, "ta"), show(2, "Bigg Boss", 2018, "ml"))
        val hits = titles(choices, found, "bigg boss", hints = { if (it == 40L) listOf("ml") else emptyList() })
        val byId = hits.associateBy { it.title.tmdbId }
        assertTrue(byId.getValue(2).inChats)
        assertTrue(byId.getValue(1).notInChats || byId.getValue(1).groups.isEmpty())
        // Without the hint, neither is sure.
        val blind = titles(choices, found, "bigg boss").associateBy { it.title.tmdbId }
        assertFalse(blind.getValue(1).inChats)
        assertFalse(blind.getValue(2).inChats)
        assertEquals(1, blind.getValue(1).maybe.size)
    }

    @Test
    fun anOnlineMatchWinsOverTheName() {
        // A file named oddly but matched to TMDB's title 7 online is its copy.
        val odd = video(1, 10, "NT.2019.x265.mkv")
        val found = groups(odd, query = "night train")
        val hits = titles(listOf(film(7, "Night Train", 2019)), found, "night train", online = { "tmdb:film:7" })
        assertTrue(hits.single().inChats)
    }

    @Test
    fun episodesAreAShowsCopiesNeverAFilmsAndTrailersAreNeverCopies() {
        val episode = video(1, 10, "Harbour.Notes.S01E01.mkv")
        val trailer = video(2, 20, "Harbour.Notes.2021.Trailer.mkv", duration = 120)
        val found = groups(episode, trailer, query = "harbour notes")
        val hits = titles(listOf(film(5, "Harbour Notes", 2021), show(6, "Harbour Notes", 2020)), found, "harbour notes")
        val byId = hits.associateBy { it.title.tmdbId }
        assertTrue(byId.getValue(6).inChats)
        assertTrue(byId.getValue(5).notInChats)
    }

    @Test
    fun theExactTitleLeadsAndTitlesWithCopiesComeBeforeTitlesWithNone() {
        val found = groups(video(1, 10, "Night.Train.Home.2015.mkv"), query = "night train")
        val choices = listOf(
            film(1, "Night Trains of Europe", 2001),
            film(2, "Night Train Home", 2015),
            film(3, "Night Train", 1998),
            film(4, "Unrelated Words", 2010),
        )
        val hits = titles(choices, found, "night train")
        // The exact name first; then, of the two starting with the words, the one in the chats.
        assertEquals(listOf(3, 2, 1), hits.map { it.title.tmdbId })
    }

    @Test
    fun theYearTheWordsGiveRanksFirstAmongOneName() {
        val choices = listOf(film(1, "Night Train", 1998), film(2, "Night Train", 2019))
        val hits = titles(choices, emptyList(), "night train 2019")
        assertEquals(2, hits.first().title.tmdbId)
    }

    @Test
    fun aHugeAnswerIsBoundedAndFast() {
        // 3,000 films of six copies, the search's own cap of cards, and garbage titles.
        val items = (1..3_000).flatMap { f -> (1..6).map { c -> video(f.toLong() % 400, f * 10L + c, "Film $f ${2000 + f % 25} ${listOf("480p", "720p", "1080p")[c % 3]}.mkv") } }
        val found = groups(*items.toTypedArray(), query = "film")
        val choices = (1..40).map { film(it, "Film $it", 2000 + it % 25) } +
            listOf(film(0, "bad id", 2000), film(99, "", 2000), film(100, "x".repeat(100_000), null))
        val started = System.nanoTime()
        val hits = titles(choices, found, "film")
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(hits.size <= GlobalSearch.TITLES_SHOWN)
        assertTrue("took $ms ms", ms < 1_000)
    }

    // ---- with a fake TMDB behind OnlineMetadata ---------------------------------------------------

    private class FakeTmdb(private val films: String, private val shows: String) : HttpTransport {
        val requests = mutableListOf<String>()
        override suspend fun send(request: HttpRequest): HttpResponse {
            requests += request.url
            val body = when {
                "search/movie" in request.url -> films
                "search/tv" in request.url -> shows
                else -> "{}"
            }
            return HttpResponse(200, body = body.toByteArray())
        }
    }

    private fun metadata(http: HttpTransport, key: String, enabled: Boolean = true): OnlineMetadata {
        val dir: File = Files.createTempDirectory("tm-titles").toFile()
        val store = MetadataStore(File(dir, "metadata.properties"))
        store.update { it.copy(enabled = enabled) }
        var clock = 1_800_000_000_000L
        return OnlineMetadata(key, store, MetadataCache(File(dir, "cache"), now = { clock }), "test", http, { clock }, { clock += it }, { "en" })
    }

    @Test
    fun aFakeTmdbAnswerBecomesTitlesWithTheChatsCopies() = runBlocking {
        val tmdb = FakeTmdb(
            films = """{"results":[{"id":7,"title":"Night Train","original_title":"Nachtzug","release_date":"2019-03-01","original_language":"de"},
                {"id":"oops"},{"id":8,"title":null},{"id":9,"title":"Night Train to Lisbon","release_date":"2013-01-01"}]}""",
            shows = """{"results":"not a list"}""",
        )
        val choices = (metadata(tmdb, "key").candidates(GlobalSearch.titleQuery(q("night train"), 2026)!!) as PosterChoices.Found).choices
        assertTrue(tmdb.requests.all { "api.themoviedb.org" in it })
        assertEquals("Nachtzug", choices.first { it.tmdbId == 7 }.originalTitle)
        val found = groups(video(1, 10, "Nachtzug.2019.German.mkv"), query = "night train")
        val hits = GlobalSearch.titles(choices, found, q("night train"), none, noHints)
        // The original title matches the German copy; the other film is not in the chats.
        assertEquals(7, hits.first().title.tmdbId)
        assertTrue(hits.first().inChats)
        assertTrue(hits.first { it.title.tmdbId == 9 }.notInChats)
    }

    @Test
    fun noKeyOrLookupsOffAskNothingAndTheChatsStillAnswer() = runBlocking {
        val tmdb = FakeTmdb("{}", "{}")
        assertEquals(PosterChoices.NoKey, metadata(tmdb, key = "").candidates(GlobalSearch.titleQuery(q("night train"), 2026)!!))
        assertEquals(PosterChoices.Off, metadata(tmdb, key = "k", enabled = false).candidates(GlobalSearch.titleQuery(q("night train"), 2026)!!))
        assertTrue(tmdb.requests.isEmpty())
        val found = groups(video(1, 10, "Night.Train.2019.mkv"), query = "night train")
        assertEquals(1, found.size)
        assertTrue(GlobalSearch.titles(emptyList(), found, q("night train"), none, noHints).isEmpty())
    }
}
