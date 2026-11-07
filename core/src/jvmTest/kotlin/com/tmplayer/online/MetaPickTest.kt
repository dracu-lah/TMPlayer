package com.tmplayer.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
