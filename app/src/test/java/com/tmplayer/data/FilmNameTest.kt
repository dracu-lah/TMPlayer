package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmNameTest {

    // Pinned rather than read off the clock, so a test that passes today still passes in 2030.
    private fun parse(name: String) = FilmName.parse(name, maxYear = 2027)

    @Test
    fun `reads the release this app was built against`() {
        val parsed = parse("Uyir (2026) Malayalam (DS4K 1080p HS WEB-Rip E-AC3 5.1 Atmos).mkv")
        assertEquals("Uyir", parsed.title)
        assertEquals(2026, parsed.year)
        assertFalse(parsed.isEpisode)
    }

    @Test
    fun `treats dots as spaces`() {
        val parsed = parse("The.Matrix.1999.1080p.BluRay.x264-GROUP.mkv")
        assertEquals("The Matrix", parsed.title)
        assertEquals(1999, parsed.year)
    }

    @Test
    fun `handles underscores and mixed separators`() {
        val parsed = parse("Spirited_Away_2001_720p_BRRip.mkv")
        assertEquals("Spirited Away", parsed.title)
        assertEquals(2001, parsed.year)
    }

    @Test
    fun `a year inside the title is not the release year`() {
        // 2049 is beyond next year, so it cannot be a release date and stays in the title.
        val parsed = parse("Blade Runner 2049 1080p BluRay x265.mkv")
        assertEquals("Blade Runner 2049", parsed.title)
        assertNull(parsed.year)
    }

    @Test
    fun `a real year after a title year wins`() {
        val parsed = parse("Blade Runner 2049 2017 2160p UHD BluRay REMUX HDR.mkv")
        assertEquals("Blade Runner 2049", parsed.title)
        assertEquals(2017, parsed.year)
    }

    @Test
    fun `a bracketed year beats a bare one`() {
        // The 1080 in the title would never be read as a year, but 1984 could be; the brackets
        // are what settle it.
        val parsed = parse("1984 (1956) 720p WEB-DL.mkv")
        assertEquals("1984", parsed.title)
        assertEquals(1956, parsed.year)
    }

    @Test
    fun `stops at the technical block when there is no year`() {
        val parsed = parse("Dune Part Two 2160p WEB-DL DDP5 1 Atmos HDR HEVC.mkv")
        assertEquals("Dune Part Two", parsed.title)
        assertNull(parsed.year)
    }

    @Test
    fun `strips brackets left dangling by the cut`() {
        val parsed = parse("Interstellar [2014] [1080p] [BluRay].mkv")
        assertEquals("Interstellar", parsed.title)
        assertEquals(2014, parsed.year)
    }

    @Test
    fun `recognises a television episode`() {
        val parsed = parse("Severance.S02E05.1080p.WEB-DL.mkv")
        assertEquals("Severance", parsed.title)
        assertEquals(2, parsed.season)
        assertEquals(5, parsed.episode)
        assertTrue(parsed.isEpisode)
    }

    @Test
    fun `keeps a number that is part of the title`() {
        val parsed = parse("Deadpool 2 (2018) 1080p.mkv")
        assertEquals("Deadpool 2", parsed.title)
        assertEquals(2018, parsed.year)
    }

    @Test
    fun `a bare title survives with nothing stripped`() {
        val parsed = parse("Ratatouille.mkv")
        assertEquals("Ratatouille", parsed.title)
        assertNull(parsed.year)
    }

    @Test
    fun `does not mistake a short title for a file extension`() {
        // "2" is not an extension, so "Deadpool 2" must not become "Deadpool".
        assertEquals("Deadpool 2", parse("Deadpool 2").title)
    }

    @Test
    fun `survives a name that is nothing but markers`() {
        val parsed = parse("1080p.x264.mkv")
        assertEquals(null, parsed.year)
        // No title can be recovered, but it must not crash or return junk with brackets in it.
        assertFalse(parsed.title.contains("("))
    }

    @Test
    fun `empty input is handled`() {
        val parsed = parse("")
        assertEquals("", parsed.title)
        assertNull(parsed.year)
    }

    @Test
    fun `query carries the year so remakes can be told apart`() {
        assertEquals("The Matrix 1999", parse("The.Matrix.1999.1080p.mkv").query)
        assertEquals("Ratatouille", parse("Ratatouille.mkv").query)
    }

    /** A real name from a Telegram film channel, signature and all. U+1F142 is a squared "S". */
    @Test
    fun `a channel signature is not part of the title`() {
        val parsed = parse(
            "\uD83C\uDD42\uD83C\uDD42_Maareesan_2025_Tamil_HQ_HDRip_1080p_HEVC_x265_DD5_1_192Kbps_and.mkv",
        )
        assertEquals("Maareesan", parsed.title)
        assertEquals(2025, parsed.year)
        assertFalse(parsed.isEpisode)
    }

    @Test
    fun `handles and tracker domains go the same way`() {
        assertEquals("Vikram", parse("@FilmsChannel - Vikram (2022) 1080p.mkv").title)
        assertEquals("Vikram", parse("www.tamilblasters.to - Vikram 2022 1080p.mkv").title)
    }

    @Test
    fun `a language is a marker, not a title`() {
        val parsed = parse("Maareesan Tamil HQ HDRip 1080p.mkv")
        assertEquals("Maareesan", parsed.title)
        assertNull(parsed.year)
    }

    /**
     * "Tamil Padam" is a real film. A marker at the very front cannot be a boundary, because
     * cutting there would leave no title at all.
     */
    @Test
    fun `a language that opens the title is left alone`() {
        val parsed = parse("Tamil Padam (2010) 720p BluRay.mkv")
        assertEquals("Tamil Padam", parsed.title)
        assertEquals(2010, parsed.year)
    }

    @Test
    fun `a title in its own script survives`() {
        // Only symbols are stripped, never letters, whatever alphabet they are written in.
        assertEquals("\u0D2E\u0D30\u0D2F\u0D4D\u0D15\u0D4D\u0D15\u0D3E\u0D7C", parse("\u0D2E\u0D30\u0D2F\u0D4D\u0D15\u0D4D\u0D15\u0D3E\u0D7C 2021 1080p.mkv").title)
    }

    @Test
    fun `reads the episode out of a series name`() {
        val parsed = parse("Wednesday.S02E04.1080p.10bit.WEBRip.6CH.x265.HEVC-PS.mkv")
        assertEquals("Wednesday", parsed.title)
        assertEquals(2, parsed.season)
        assertEquals(4, parsed.episode)
        assertTrue(parsed.isEpisode)
        assertTrue(parsed.isSeries)
    }

    @Test
    fun `an episode written any of the usual ways still reads`() {
        listOf(
            "Wednesday 2x04 1080p.mkv",
            "Wednesday Season 2 Episode 4 1080p.mkv",
            "Wednesday S02 EP04 1080p.mkv",
            "Wednesday.S02.E04.1080p.mkv",
            "Wednesday S02E04-E05 1080p.mkv",
        ).forEach { name ->
            val parsed = parse(name)
            assertEquals(name, "Wednesday", parsed.title)
            assertEquals(name, 2, parsed.season)
            assertEquals(name, 4, parsed.episode)
        }
    }

    @Test
    fun `a whole season is television without being one episode`() {
        val parsed = parse("Wednesday Season 2 Complete 1080p.mkv")
        assertEquals("Wednesday", parsed.title)
        assertEquals(2, parsed.season)
        assertNull(parsed.episode)
        assertTrue(parsed.isSeries)
        assertFalse(parsed.isEpisode)
    }

    @Test
    fun `a resolution is never mistaken for a season and episode`() {
        // 1920x1080 must not read as season 19.
        val parsed = parse("Some Film 1920x1080 BluRay.mkv")
        assertNull(parsed.season)
        assertNull(parsed.episode)
    }

    @Test
    fun `the next episode is found by number, not by position`() {
        val chat = listOf(
            "Wednesday.S02E05.1080p.WEBRip.mkv",
            "Some.Film.2024.1080p.mkv",
            "Wednesday.S02E04.1080p.WEBRip.mkv",
            "Wednesday.S03E01.1080p.WEBRip.mkv",
        )
        val next = FilmName.nextEpisode("Wednesday.S02E04.1080p.WEBRip.mkv", chat) { it }
        assertEquals("Wednesday.S02E05.1080p.WEBRip.mkv", next)
    }

    @Test
    fun `the end of a season does not roll into the next one`() {
        val chat = listOf("Wednesday.S02E08.1080p.mkv", "Wednesday.S03E01.1080p.mkv")
        assertNull(FilmName.nextEpisode("Wednesday.S02E08.1080p.mkv", chat) { it })
    }

    @Test
    fun `a film has no next episode and a different series is not one either`() {
        val chat = listOf("Severance.S02E05.1080p.mkv", "Some.Film.2024.1080p.mkv")
        assertNull(FilmName.nextEpisode("Some.Film.2024.1080p.mkv", chat) { it })
        assertNull(FilmName.nextEpisode("Wednesday.S02E04.1080p.mkv", chat) { it })
    }
}
