package com.tmplayer.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real release names as the chats post them, read into the question TMDB is asked, and the pick among its answers. */
class MetaPickTest {

    private fun of(name: String) = MetaQuery.of(name) ?: error("no query for $name")

    @Test
    fun aDubbedFilmKeepsItsTitleYearAndLanguages() {
        val q = of("Clap.2022.1080p.10bit.AMZN.WEBRip.Tamil-Hindi.DDP2.0.x265.HEVC.ESub-iP0OL.mkv")
        assertEquals("Clap", q.title)
        assertEquals(2022, q.year)
        assertEquals(MetaKind.Film, q.kind)
        assertEquals(listOf("ta", "hi"), q.languages)
    }

    @Test
    fun aTwoLetterTitleInBracketsWithItsYear() {
        val q = of("DC (2026) 720p 10bit DS4K SNXT WEBRip x265 HEVC Hindi HE-AAC 2.0 ESubs ~ MDSrK.mkv")
        assertEquals("DC", q.title)
        assertEquals(2026, q.year)
        assertEquals(MetaKind.Film, q.kind)
        assertEquals(listOf("hi"), q.languages)
    }

    @Test
    fun aSeasonSplitInTwoFilesIsAShowFromItsFirstEpisode() {
        val q = of("Chainsmoker.Cat.S01E01-06.1080p.HEVC.WEB-DL.Hindi-Japan.mkv")
        assertEquals("Chainsmoker Cat", q.title)
        assertEquals(MetaKind.Show, q.kind)
        assertEquals(1, q.season)
        assertEquals(1, q.episode)
        assertEquals(listOf("hi", "ja"), q.languages)
        val parsed = com.tmplayer.data.MediaName.parse("Chainsmoker.Cat.S01E07-12.1080p.HEVC.WEB-DL.Hindi-Japan.mkv")
        assertEquals(7, parsed.episode)
        assertEquals(12, parsed.lastEpisode)
        // A picture height after the dash is no last episode.
        assertNull(com.tmplayer.data.MediaName.parse("Harbour.Notes.S01E05-720p.mkv").lastEpisode)
    }

    @Test
    fun malayalamFilmsAndHindiSeasonPacks() {
        val boys = of("Manjummel.Boys.2024.Malayalam.1080p.WEB-DL.mkv")
        assertEquals("Manjummel Boys", boys.title)
        assertEquals(2024, boys.year)
        assertEquals(listOf("ml"), boys.languages)
        val pack = of("Panchayat.S03.Complete.Hindi.720p.mkv")
        assertEquals("Panchayat", pack.title)
        assertEquals(MetaKind.Show, pack.kind)
        assertEquals(3, pack.season)
    }

    private fun candidate(id: String, title: String, year: Int?, language: String = "") =
        MetaCandidate(id, listOf(title, title), year, "", null, null, originalLanguage = language)

    @Test
    fun theSameWordsComeBeforeTheSameYearAndTheYearBeforeTheLanguage() {
        val q = of("Clap.2022.Tamil.mkv")
        val list = listOf(
            candidate("near", "Clap", 2021, "ta"),
            candidate("exact", "Clap", 2022, "en"),
        )
        assertEquals("exact", MetaPick.best(q, list, show = false)?.id)
        val tie = listOf(candidate("first", "Clap", 2022, "en"), candidate("tamil", "Clap", 2022, "ta"))
        assertEquals("tamil", MetaPick.best(q, tie, show = false)?.id)
        val plain = listOf(candidate("first", "Clap", 2022), candidate("second", "Clap", 2022))
        assertEquals("the provider's order breaks a tie", "first", MetaPick.best(q, plain, show = false)?.id)
    }

    @Test
    fun nothingThatFailsTheTitleCheckIsPicked() {
        val q = of("Coast walk day 2 2023.mp4")
        assertNull(MetaPick.best(q, listOf(candidate("x", "Coastal Walks", 2023)), show = false))
    }

    /** Two films called "Idiot" in 2022 (Czech and Tamil, 2026-11-07): the channel's handle must not eat the language. */
    @Test
    fun aLanguageAfterTheChannelHandleBreaksATieBetweenTwoFilmsOfTheSameNameAndYear() {
        val q = of("Idiot_2022_@Cinemahub182_Tamil_HQ_HDRip_1080p_HEVC_DD5_1_192Kbp.mkv")
        assertEquals("Idiot", q.title)
        assertEquals(2022, q.year)
        assertEquals(listOf("ta"), q.languages)
        val czech = MetaCandidate("959848", listOf("Idiot"), 2022, "", null, null, originalLanguage = "cs")
        val tamil = MetaCandidate("880780", listOf("Idiot", "\u0b87\u0b9f\u0bbf\u0baf\u0b9f\u0bcd"), 2022, "", null, null, originalLanguage = "ta")
        assertEquals("880780", MetaPick.best(q, listOf(czech, tamil), show = false)?.id)
    }

    private fun biggBoss() = listOf(
        MetaCandidate("tamil", listOf("Bigg Boss"), 2017, "", null, null, originalLanguage = "ta"),
        MetaCandidate("malayalam", listOf("Bigg Boss"), 2018, "", null, null, originalLanguage = "ml"),
        MetaCandidate("telugu", listOf("Bigg Boss"), 2017, "", null, null, originalLanguage = "te"),
        MetaCandidate("kannada", listOf("Bigg Boss"), 2013, "", null, null, originalLanguage = "kn"),
    )

    /** HX24: the Malayalam "Bigg Boss" of a chat named for Malayalam showed TMDB's first, the Tamil one. */
    @Test
    fun theChatsLanguageBreaksATieBetweenShowsOfOneName() {
        val q = of("Bigg Boss S08E50.mkv")
        assertEquals("tamil", MetaPick.best(q, biggBoss(), show = true)?.id)
        val hinted = q.withHints(com.tmplayer.data.MediaName.languageHints("Deonnn Malayalam Shows"))
        assertEquals(listOf("ml"), hinted.hints)
        assertEquals("malayalam", MetaPick.best(hinted, biggBoss(), show = true)?.id)
    }

    @Test
    fun aHintNeverOutweighsTheFilesOwnLanguageOrTheYear() {
        val ownWords = of("Bigg Boss S08E50 Telugu.mkv").withHints(listOf("ml"))
        assertEquals("telugu", MetaPick.best(ownWords, biggBoss(), show = true)?.id)
        val byYear = of("Bigg Boss 2017 S08E50.mkv").withHints(listOf("ml"))
        assertEquals("tamil", MetaPick.best(byYear, biggBoss(), show = true)?.id)
    }

    @Test
    fun aHintIsNeverAReasonToAccept() {
        val q = of("Coast walk day 2 2023.mp4").withHints(listOf("ta"))
        assertNull(MetaPick.best(q, listOf(candidate("x", "Coastal Walks", 2023, "ta")), show = false))
        // A chat that names a language no candidate has changes nothing.
        val q2 = of("Bigg Boss S08E50.mkv").withHints(listOf("fr"))
        assertEquals("tamil", MetaPick.best(q2, biggBoss(), show = true)?.id)
    }

    @Test
    fun hintsAreInTheCacheKeyOnlyWhenThereAreSome() {
        val q = of("Bigg Boss S08E50.mkv")
        assertEquals(q.cacheKey("en"), q.withHints(emptyList()).cacheKey("en"))
        org.junit.Assert.assertNotEquals(q.cacheKey("en"), q.withHints(listOf("ml")).cacheKey("en"))
    }

    @Test
    fun languageHintsReadAChatTitle() {
        val h = com.tmplayer.data.MediaName::languageHints
        assertEquals(listOf("ml"), h("Malayalam Movies HD"))
        assertEquals(listOf("ta", "te"), h("Tamil Telugu Latest"))
        assertEquals(emptyList<String>(), h("Cinema_paradasio"))
        // Short forms inside a chat title are not languages.
        assertEquals(emptyList<String>(), h("Mal Tam Hin Eng"))
        assertEquals(emptyList<String>(), h(""))
    }

    @Test
    fun theChatRegistryHandsHintsToAnItemsQuery() {
        com.tmplayer.online.ChatLanguageHints.clear()
        assertEquals(false, com.tmplayer.online.ChatLanguageHints.knows(7L))
        com.tmplayer.online.ChatLanguageHints.note(7L, "Malayalam Shows")
        com.tmplayer.online.ChatLanguageHints.note(8L, "Plain chat")
        assertEquals(listOf("ml"), com.tmplayer.online.ChatLanguageHints.of(7L))
        assertEquals(emptyList<String>(), com.tmplayer.online.ChatLanguageHints.of(8L))
        assertEquals(true, com.tmplayer.online.ChatLanguageHints.knows(8L))
        com.tmplayer.online.ChatLanguageHints.note(7L, "Renamed")
        assertEquals(emptyList<String>(), com.tmplayer.online.ChatLanguageHints.of(7L))
        com.tmplayer.online.ChatLanguageHints.clear()
    }

    @Test
    fun aHintIsBelowEveryOtherStepAndOnlyBreaksAnExactTie() {
        // A near-year candidate with the exact words must beat a hinted candidate with no year at all.
        val q = of("Bigg Boss 2018 S08E50.mkv").withHints(listOf("ml"))
        val nearYear = MetaCandidate("near", listOf("Bigg Boss"), 2017, "", null, null, originalLanguage = "ta")
        val hintedNoYear = MetaCandidate("hinted", listOf("Bigg Boss"), null, "", null, null, originalLanguage = "ml")
        assertEquals("near", MetaPick.best(q, listOf(hintedNoYear, nearYear), show = true)?.id)
        assertTrue(MetaPick.score(q, nearYear) > MetaPick.score(q, hintedNoYear))
        // Between equals it still decides.
        val a = MetaCandidate("a", listOf("Bigg Boss"), 2018, "", null, null, originalLanguage = "ta")
        val b = MetaCandidate("b", listOf("Bigg Boss"), 2018, "", null, null, originalLanguage = "ml")
        assertEquals("b", MetaPick.best(q, listOf(a, b), show = true)?.id)
    }

    @Test
    fun theRegistryIsSafeUnderConcurrentWritersAndScalesTo5000Chats() {
        com.tmplayer.online.ChatLanguageHints.clear()
        val started = System.nanoTime()
        val threads = (0 until 8).map { t ->
            Thread { for (n in 0 until 600) com.tmplayer.online.ChatLanguageHints.note((t * 600 + n).toLong(), if (n % 3 == 0) "Malayalam Chat $n" else "Chat $n") }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        val millis = (System.nanoTime() - started) / 1_000_000
        // 4,800 chats over 64 shards of 128: every one is known and none was lost to a race.
        var known = 0
        var withHint = 0
        for (id in 0L until 4_800L) {
            if (com.tmplayer.online.ChatLanguageHints.knows(id)) known++
            if (com.tmplayer.online.ChatLanguageHints.of(id) == listOf("ml")) withHint++
        }
        assertEquals(4_800, known)
        assertEquals(1_600, withHint)
        assertTrue("took $millis ms", millis < 5_000)
        // Past the bound a chat is just not noted and asks without hints; nothing throws.
        for (id in 10_000L until 20_000L) com.tmplayer.online.ChatLanguageHints.note(id, "Tamil $id")
        assertTrue(com.tmplayer.online.ChatLanguageHints.of(1L).isEmpty() || com.tmplayer.online.ChatLanguageHints.knows(1L))
        com.tmplayer.online.ChatLanguageHints.clear()
    }
}
