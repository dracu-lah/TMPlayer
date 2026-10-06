package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaNameTest {

    // Pinned rather than read off the clock, so a test that passes today still passes in 2030.
    private fun parse(name: String) = MediaName.parse(name, maxYear = 2027)

    @Test
    fun `reads a descriptive creator upload`() {
        val parsed = parse("Harbour Notes (2026) Malayalam (1080p WEB-Rip E-AC3).mkv")
        assertEquals("Harbour Notes", parsed.title)
        assertEquals(2026, parsed.year)
        assertFalse(parsed.isEpisode)
    }

    @Test
    fun `treats dots as spaces`() {
        val parsed = parse("City.Archive.1999.1080p.BluRay.x264-GROUP.mkv")
        assertEquals("City Archive", parsed.title)
        assertEquals(1999, parsed.year)
    }

    @Test
    fun `handles underscores and mixed separators`() {
        val parsed = parse("Weekend_Sketchbook_2001_720p_BRRip.mkv")
        assertEquals("Weekend Sketchbook", parsed.title)
        assertEquals(2001, parsed.year)
    }

    @Test
    fun `a year inside the title is not the release year`() {
        // 2049 is beyond next year, so it cannot be a release date and stays in the title.
        val parsed = parse("Studio Log 4021 1080p BluRay x265.mkv")
        assertEquals("Studio Log 4021", parsed.title)
        assertNull(parsed.year)
    }

    @Test
    fun `a real year after a title year wins`() {
        val parsed = parse("Studio Log 4021 2017 2160p UHD BluRay REMUX HDR.mkv")
        assertEquals("Studio Log 4021", parsed.title)
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
        val parsed = parse("Design Tutorial Part Two 2160p WEB-DL DDP5 1 Atmos HDR HEVC.mkv")
        assertEquals("Design Tutorial Part Two", parsed.title)
        assertNull(parsed.year)
    }

    @Test
    fun `strips brackets left dangling by the cut`() {
        val parsed = parse("Garden Notes [2014] [1080p] [BluRay].mkv")
        assertEquals("Garden Notes", parsed.title)
        assertEquals(2014, parsed.year)
    }

    @Test
    fun `recognises a television episode`() {
        val parsed = parse("Studio.Sessions.S02E05.1080p.WEB-DL.mkv")
        assertEquals("Studio Sessions", parsed.title)
        assertEquals(2, parsed.season)
        assertEquals(5, parsed.episode)
        assertTrue(parsed.isEpisode)
    }

    @Test
    fun `keeps a number that is part of the title`() {
        val parsed = parse("Workshop 2 (2018) 1080p.mkv")
        assertEquals("Workshop 2", parsed.title)
        assertEquals(2018, parsed.year)
    }

    @Test
    fun `a bare title survives with nothing stripped`() {
        val parsed = parse("Kitchen Journal.mkv")
        assertEquals("Kitchen Journal", parsed.title)
        assertNull(parsed.year)
    }

    @Test
    fun `does not mistake a short title for a file extension`() {
        // "2" is not an extension, so "Workshop 2" must not become "Workshop".
        assertEquals("Workshop 2", parse("Workshop 2").title)
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
        assertEquals("City Archive 1999", parse("City.Archive.1999.1080p.mkv").query)
        assertEquals("Kitchen Journal", parse("Kitchen Journal.mkv").query)
    }

    /** A decorated creator upload; U+1F142 is a squared "S". */
    @Test
    fun `a channel signature is not part of the title`() {
        val parsed = parse(
            "\uD83C\uDD42\uD83C\uDD42_Harbour_Notes_2025_Tamil_HQ_HDRip_1080p_HEVC_x265_DD5_1_192Kbps_and.mkv",
        )
        assertEquals("Harbour Notes", parsed.title)
        assertEquals(2025, parsed.year)
        assertFalse(parsed.isEpisode)
    }

    @Test
    fun `handles and tracker domains go the same way`() {
        assertEquals("Workshop", parse("@CreatorClips - Workshop (2022) 1080p.mkv").title)
        assertEquals("Workshop", parse("www.creatorfiles.org - Workshop 2022 1080p.mkv").title)
    }

    @Test
    fun `a language is a marker, not a title`() {
        val parsed = parse("Harbour Notes Tamil HQ HDRip 1080p.mkv")
        assertEquals("Harbour Notes", parsed.title)
        assertNull(parsed.year)
    }

    /**
     * A marker at the very front cannot be a boundary because cutting there would leave no title.
     */
    @Test
    fun `a language that opens the title is left alone`() {
        val parsed = parse("Tamil Lessons (2010) 720p BluRay.mkv")
        assertEquals("Tamil Lessons", parsed.title)
        assertEquals(2010, parsed.year)
    }

    @Test
    fun `a title in its own script survives`() {
        // Only symbols are stripped, never letters, whatever alphabet they are written in.
        assertEquals("\u0D2E\u0D30\u0D2F\u0D4D\u0D15\u0D4D\u0D15\u0D3E\u0D7C", parse("\u0D2E\u0D30\u0D2F\u0D4D\u0D15\u0D4D\u0D15\u0D3E\u0D7C 2021 1080p.mkv").title)
    }

    @Test
    fun `reads the episode out of a series name`() {
        val parsed = parse("Creative.Course.S02E04.1080p.10bit.WEBRip.6CH.x265.HEVC-PS.mkv")
        assertEquals("Creative Course", parsed.title)
        assertEquals(2, parsed.season)
        assertEquals(4, parsed.episode)
        assertTrue(parsed.isEpisode)
        assertTrue(parsed.isSeries)
    }

    @Test
    fun `an episode written any of the usual ways still reads`() {
        listOf(
            "Creative Course 2x04 1080p.mkv",
            "Creative Course Season 2 Episode 4 1080p.mkv",
            "Creative Course S02 EP04 1080p.mkv",
            "Creative Course.S02.E04.1080p.mkv",
            "Creative Course S02E04-E05 1080p.mkv",
        ).forEach { name ->
            val parsed = parse(name)
            assertEquals(name, "Creative Course", parsed.title)
            assertEquals(name, 2, parsed.season)
            assertEquals(name, 4, parsed.episode)
        }
    }

    @Test
    fun `a whole season is television without being one episode`() {
        val parsed = parse("Creative Course Season 2 Complete 1080p.mkv")
        assertEquals("Creative Course", parsed.title)
        assertEquals(2, parsed.season)
        assertNull(parsed.episode)
        assertTrue(parsed.isSeries)
        assertFalse(parsed.isEpisode)
    }

    @Test
    fun `a resolution is never mistaken for a season and episode`() {
        // 1920x1080 must not read as season 19.
        val parsed = parse("Studio Recording 1920x1080 BluRay.mkv")
        assertNull(parsed.season)
        assertNull(parsed.episode)
    }

    @Test
    fun `the next episode is found by number, not by position`() {
        val chat = listOf(
            "Creative.Course.S02E05.1080p.WEBRip.mkv",
            "Studio.Recording.2024.1080p.mkv",
            "Creative.Course.S02E04.1080p.WEBRip.mkv",
            "Creative.Course.S03E01.1080p.WEBRip.mkv",
        )
        val next = MediaName.nextEpisode("Creative.Course.S02E04.1080p.WEBRip.mkv", chat) { it }
        assertEquals("Creative.Course.S02E05.1080p.WEBRip.mkv", next)
    }

    @Test
    fun `of several copies of the next episode, the one most like this copy plays`() {
        data class File(val name: String, val size: Long)
        val mb = 1024L * 1024
        val same = "Family_Full_House_With_Rohit_Sharma_S01E02_Mumbai_Punters_vs_Team.mp4"
        // As posted: the biggest first, which is the copy auto next used to pick.
        val chat = listOf(File(same, 1_843 * mb), File(same, 599 * mb), File(same, 360 * mb))
        val current = "Family_Full_House_With_Rohit_Sharma_S01E01_Mumbai_Punters_vs_Team.mp4"
        assertEquals(360 * mb, MediaName.nextEpisode(current, chat, 354 * mb, { it.size }) { it.name }?.size)
        assertEquals(599 * mb, MediaName.nextEpisode(current, chat, 599 * mb, { it.size }) { it.name }?.size)

        // A quality marker in the name beats a nearer size.
        val tagged = listOf(File("Show.S01E03.1080p.x265.mkv", 400 * mb), File("Show.S01E03.720p.mkv", 700 * mb))
        assertEquals(400 * mb, MediaName.nextEpisode("Show.S01E02.1080p.x265.mkv", tagged, 900 * mb, { it.size }) { it.name }?.size)
        assertEquals(700 * mb, MediaName.nextEpisode("Show.S01E02.720p.mkv", tagged, 300 * mb, { it.size }) { it.name }?.size)
    }

    @Test
    fun `a bare E number reads only the lenient way`() {
        assertNull(MediaName.parse("Family Full House With Rohit Sharma \u2014 E5. Vlog.mp4").episode)
        val loose = MediaName.looseEpisode("Family Full House With Rohit Sharma \u2014 E5. Vlog.mp4")!!
        assertEquals("Family Full House With Rohit Sharma", loose.title)
        assertEquals(5, loose.episode)
        assertNull(loose.season)
        assertNull(MediaName.looseEpisode("E05.mkv"))
        assertNull(MediaName.looseEpisode("City.Archive.1999.1080p.mkv"))
    }

    @Test
    fun `the end of a season does not roll into the next one`() {
        val chat = listOf("Creative.Course.S02E08.1080p.mkv", "Creative.Course.S03E01.1080p.mkv")
        assertNull(MediaName.nextEpisode("Creative.Course.S02E08.1080p.mkv", chat) { it })
    }

    @Test
    fun `a standalone video has no next episode and a different series is not one either`() {
        val chat = listOf("Studio.Sessions.S02E05.1080p.mkv", "Studio.Recording.2024.1080p.mkv")
        assertNull(MediaName.nextEpisode("Studio.Recording.2024.1080p.mkv", chat) { it })
        assertNull(MediaName.nextEpisode("Creative.Course.S02E04.1080p.mkv", chat) { it })
    }

    @Test
    fun `the previous episode is the one before it in the same season`() {
        val chat = listOf(
            "Creative.Course.S02E05.1080p.WEBRip.mkv",
            "Creative.Course.S02E03.1080p.WEBRip.mkv",
            "Creative.Course.S02E04.1080p.WEBRip.mkv",
        )
        val previous = MediaName.previousEpisode("Creative.Course.S02E04.1080p.WEBRip.mkv", chat) { it }
        assertEquals("Creative.Course.S02E03.1080p.WEBRip.mkv", previous)
    }

    @Test
    fun `the first episode of a season does not roll back into the one before it`() {
        val chat = listOf("Creative.Course.S01E08.1080p.mkv", "Creative.Course.S02E01.1080p.mkv")
        assertNull(MediaName.previousEpisode("Creative.Course.S02E01.1080p.mkv", chat) { it })
    }

    @Test
    fun `a standalone video has no previous episode`() {
        val chat = listOf("Studio.Recording.2024.1080p.mkv", "Studio.Sessions.S02E05.1080p.mkv")
        assertNull(MediaName.previousEpisode("Studio.Recording.2024.1080p.mkv", chat) { it })
    }

    // ---- CP27: the other ways an episode is written, and the caption as a second source ------

    private fun parse(name: String, caption: String?) = MediaName.parse(name, caption, maxYear = 2027)

    @Test
    fun `a season by episode code reads from real release names`() {
        listOf(
            "Harbour.Notes.1x02.720p.HDTV.x264-GROUP.mkv" to (1 to 2),
            "Harbour Notes 3x11 Return 1080p WEB-DL.mkv" to (3 to 11),
            "Harbour_Notes_2x05_hdtv.mp4" to (2 to 5),
        ).forEach { (name, expected) ->
            val parsed = parse(name)
            assertEquals(name, "Harbour Notes", parsed.title)
            assertEquals(name, expected.first, parsed.season)
            assertEquals(name, expected.second, parsed.episode)
        }
    }

    @Test
    fun `an episode with no season still reads`() {
        listOf(
            "Kitchen Journal Ep 02 1080p.mp4",
            "Kitchen.Journal.Ep02.720p.mkv",
            "Kitchen Journal EP.02 (2024).mkv",
            "Kitchen Journal Episode 2 Malayalam 720p.mkv",
            "Kitchen Journal Ep-02.mkv",
        ).forEach { name ->
            val parsed = parse(name)
            assertEquals(name, "Kitchen Journal", parsed.title)
            assertNull(name, parsed.season)
            assertEquals(name, 2, parsed.episode)
            assertTrue(name, parsed.isEpisode)
        }
    }

    @Test
    fun `the fansub dash form reads as an absolute episode`() {
        listOf(
            "[SubsPlease] Sky Garden - 02 (1080p) [A1B2C3D4].mkv" to 2,
            "[Erai-raws] Sky Garden - 12 [1080p][Multiple Subtitle].mkv" to 12,
            "Sky Garden - 1071 [720p].mkv" to 1071,
            "[Group] Sky Garden - 05v2 (1080p).mkv" to 5,
            "Sky Garden - 07.mkv" to 7,
        ).forEach { (name, episode) ->
            val parsed = parse(name)
            assertEquals(name, "Sky Garden", parsed.title)
            assertNull(name, parsed.season)
            assertEquals(name, episode, parsed.episode)
        }
    }

    @Test
    fun `the dash form takes a season when one is written beside it`() {
        val parsed = parse("[SubsPlease] Sky Garden S2 - 03 (1080p).mkv")
        assertEquals("Sky Garden", parsed.title)
        assertEquals(2, parsed.season)
        assertEquals(3, parsed.episode)
    }

    @Test
    fun `a dash before a year or a resolution is not an episode`() {
        listOf(
            "City Archive - 2019 1080p BluRay.mkv",
            "City Archive - 1080p WEB-DL.mkv",
            "City Archive - 720p.mkv",
            "Workshop - 2 (2018).mkv",
            "City Archive - Directors Cut 2160p.mkv",
        ).forEach { name ->
            val parsed = parse(name)
            assertNull(name, parsed.episode)
            assertFalse(name, parsed.isEpisode)
        }
        assertEquals(2019, parse("City Archive - 2019 1080p BluRay.mkv").year)
    }

    @Test
    fun `codecs, resolutions and channel counts are never episodes`() {
        listOf(
            "City.Archive.2016.1080p.BluRay.x265.10bit.mkv",
            "City Archive 2016 2160p HDR x264 DDP5 1.mkv",
            "City Archive 720p 1280x720 H 265.mkv",
            "City Archive 720p HEVC AAC2 0.mkv",
            "City Archive (2016) 4K 60fps.mkv",
        ).forEach { name ->
            val parsed = parse(name)
            assertEquals(name, "City Archive", parsed.title)
            assertNull(name, parsed.season)
            assertNull(name, parsed.episode)
        }
    }

    @Test
    fun `a bracketed year at the front is not a fansub tag`() {
        val parsed = parse("[2014] Garden Notes 1080p.mkv")
        assertTrue(parsed.title.contains("Garden Notes"))
        assertEquals(2014, parsed.year)
    }

    @Test
    fun `the caption gives the episode when the file name has none`() {
        val parsed = parse(
            "harbour_notes_720p.mkv",
            "🎬 Harbour Notes S02E04\n\nJoin @CreatorClips for more",
        )
        assertEquals("Harbour Notes", parsed.title)
        assertEquals(2, parsed.season)
        assertEquals(4, parsed.episode)
    }

    @Test
    fun `a caption that names only the episode keeps the file's title`() {
        val parsed = parse("Harbour Notes 1080p WEB-DL.mkv", "Episode 4")
        assertEquals("Harbour Notes", parsed.title)
        assertNull(parsed.season)
        assertEquals(4, parsed.episode)
    }

    @Test
    fun `the file name wins over the caption when both carry an episode`() {
        val parsed = parse("Harbour.Notes.S01E03.1080p.mkv", "Harbour Notes S01E09 out now")
        assertEquals(3, parsed.episode)
    }

    @Test
    fun `a caption with no episode changes nothing`() {
        val plain = parse("City.Archive.1999.1080p.mkv")
        assertEquals(plain, parse("City.Archive.1999.1080p.mkv", "Watch in HD, link in bio"))
        assertEquals(plain, parse("City.Archive.1999.1080p.mkv", null))
        assertEquals(plain, parse("City.Archive.1999.1080p.mkv", "  \n "))
    }

    @Test
    fun `a caption alone, with no file name, still reads`() {
        val parsed = parse("", "Sky Garden Ep 3")
        assertEquals("Sky Garden", parsed.title)
        assertEquals(3, parsed.episode)
    }

    @Test
    fun `the existing season and episode forms still read the same`() {
        listOf(
            "Studio.Sessions.S01E02.1080p.WEB-DL.mkv",
            "Studio Sessions s01e02 720p.mkv",
            "Studio Sessions S01.E02.mkv",
            "Studio Sessions S1E2 (2024).mkv",
            "Studio Sessions Season 1 Episode 2.mkv",
        ).forEach { name ->
            val parsed = parse(name, "something else entirely S09E09")
            assertEquals(name, "Studio Sessions", parsed.title)
            assertEquals(name, 1, parsed.season)
            assertEquals(name, 2, parsed.episode)
        }
    }
}
