package com.tmplayer.data

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How shows and films are really named when they are shared in chats and on trackers, one line per
 * style (a made-up title, so no real name stands in a test). Every show style must read the same
 * show, season and episode, and the files of one style must land in one shelf entry; every film
 * style must read its title and year. A failing style is listed with what was read, all at once.
 *
 * In a show template %E is the episode as two digits and %e as it is (6 then 06), and the season is
 * the one the row names (null when the name has none).
 */
class NameStylesTest {

    private class Show(val template: String, val season: Int? = null, val siblings: Boolean = false)
    private class Film(val name: String, val title: String = "Harbour Run", val year: Int? = 2019)

    private val shows: List<Show> = listOf(
        // Trackers and release groups.
        Show("Harbour.Notes.S02E%E.1080p.BluRay.x264-GROUP.mkv", 2),
        Show("Harbour Notes S02E%E 720p HDTV x264-GROUP.mkv", 2),
        Show("Harbour.Notes.2024.S02E%E.WEB-DL.DDP5.1.H.264-GROUP.mkv", 2),
        Show("Harbour.Notes.S02E%E.The.Long.Way.1080p.AMZN.WEB-DL.DDP5.1.H.264-GROUP.mkv", 2),
        Show("Harbour Notes (2024) - S02E%E - The Long Way (1080p BluRay x265 Silence).mkv", 2),
        Show("Harbour.Notes.S02E%E.REPACK.720p.HDTV.x264-GROUP.mkv", 2),
        Show("Harbour.Notes.S02E%E.PROPER.1080p.WEB.h264-GROUP.mkv", 2),
        Show("Harbour Notes S02E%E NF WEBRip 1080p ESub.mkv", 2),
        Show("Harbour Notes S02E%E [Hindi-English] 1080p WEB-DL.mkv", 2),
        Show("Harbour Notes S02E%E 480p HEVC 150MB.mkv", 2),
        Show("www.Site.com - Harbour Notes S02E%E 720p.mkv", 2),
        Show("[www.Site.org] Harbour Notes S02E%E.mkv", 2),
        Show("Harbour Notes S02E%E (2160p UHD HDR10+).mkv", 2),
        Show("Harbour Notes - 2x%E - The Long Way [HDTV].mkv", 2),
        Show("Harbour Notes Season 2 Episode %e - The Long Way 1080p.mkv", 2),
        Show("Harbour.Notes.s02e%E.720p.web.h264-grp.mkv", 2),
        Show("HARBOUR NOTES S02E%E 1080P WEB-DL.MKV", 2),
        Show("Harbour Notes S02E%E.mp4", 2),
        Show("Harbour.Notes.S02E%E.iNTERNAL.1080p.BluRay.x264-GROUP.mkv", 2),
        Show("Harbour.Notes.S02E%E.DUBBED.720p.WEB.x264-GROUP.mkv", 2),
        Show("Harbour.Notes.S02E%E.MULTi.1080p.WEB.H264-GROUP.mkv", 2),
        Show("Harbour.Notes.S02E%E.1080p.WEB-DL.DD+5.1.Atmos.H.264-GROUP.mkv", 2),
        Show("Harbour Notes S02E%E 1080p BluRay 10bit DTS-HD MA 5.1 x265-GROUP.mkv", 2),
        // Telegram channels: regional languages, sizes, handles.
        Show("Harbour Notes S02 EP%E [Tamil + Telugu + Hindi] 1080p HDRip x264 AAC 400MB ESub.mkv", 2),
        Show("Harbour Notes (2024) S02 Ep %E Hindi Dubbed 720p.mkv", 2),
        Show("Harbour Notes S02E%E Tam Tel Hin Eng.mkv", 2),
        Show("@Channel_Name Harbour Notes S02E%E 1080p.mkv", 2),
        Show("Harbour Notes [S02E%E] Hindi 480p.mkv", 2),
        Show("Harbour Notes S02E%E ~ TG @channel.mkv", 2),
        Show("Harbour Notes Ep %E (Hindi Dub) 1080p.mkv", null),
        Show("Harbour Notes EP%E Tamil HQ HDRip.mkv", null),
        Show("Harbour Notes Season 02 Episode %E Malayalam.mkv", 2),
        Show("Harbour Notes - S02 E%E - 720p WEB-DL.mkv", 2),
        Show("Harbour Notes Episode %e | Season 2 | Full Episode 1080p.mkv", 2),
        Show("Harbour Notes S02E%E Full Episode HD.mkv", 2),
        Show("Harbour Notes S2 Ep %E 720p Hindi.mkv", 2),
        Show("Harbour Notes Season 2 Epi %E Telugu 720p HDRip.mkv", 2),
        Show("[TG @channel] Harbour Notes S02E%E 1080p.mkv", 2),
        Show("Harbour Notes S02E%E Malayalam Dubbed HDRip.mkv", 2),
        Show("Harbour Notes S02E%E - Tamil - 1080p - AVC - (DD+5.1 - 640Kbps & AAC) - 450MB - ESub.mkv", 2),
        // Anime fansubs.
        Show("[SubsPlease] Harbour Notes - %E (1080p) [A1B2C3D4].mkv"),
        Show("[Erai-raws] Harbour Notes 2nd Season - %E [1080p][Multiple Subtitle].mkv", 2, siblings = true),
        Show("Harbour Notes Season 2 - %E [English Dub] 1080p.mkv", 2),
        Show("[Judas] Harbour Notes - S02E%E.mkv", 2),
        Show("[HorribleSubs] Harbour Notes - %E [720p].mkv"),
        Show("Harbour Notes - %E (Dual Audio) 720p x265.mkv"),
        Show("[Anime Time] Harbour Notes S02E%E 1080p BD Dual Audio.mkv", 2),
        Show("[Group] Harbour Notes - %E [BD 1080p HEVC FLAC][ABCD1234].mkv"),
        Show("[Group] Harbour Notes S2 - %Ev2 (BD 1080p) [ABCD1234].mkv", 2),
        Show("[Group] Harbour Notes (2024) - S02E%E (WEB 1080p x265 10bit) [Dual Audio].mkv", 2),
        Show("[Group] Harbour Notes - %E [WEB 1080p][AAC].mkv"),
        Show("[Group] Harbour Notes - %E (WEB 1080p x264 AAC) [Multi-Subs].mkv"),
        Show("Harbour Notes - %E [1080p].mkv"),
        Show("[Group] Harbour Notes - %E (WEB 1080p).mkv"),
        Show("Harbour Notes S2 - %E.mkv", 2),
        Show("Harbour Notes Season 2 - %E.mkv", 2),
        Show("Harbour Notes 2nd Season Ep %E.mkv", 2, siblings = true),
        Show("Harbour Notes Second Season - %E.mkv", 2, siblings = true),
        Show("Harbour Notes 2 - %E.mkv", 2, siblings = true),
        Show("[Group] Harbour Notes 2nd Season - %E [BD 1080p].mkv", 2, siblings = true),
        Show("[Group] Harbour Notes S2 - %E [BD 1080p].mkv", 2),
        // K-drama and daily style (the air date after the code).
        Show("Harbour Notes E%E 240315 720p-NEXT.mkv", siblings = true),
        Show("Harbour.Notes.E%E.240315.1080p.WEB-DL.H264-GROUP.mkv", siblings = true),
        Show("Harbour Notes Ep%E [Korean Drama] Eng Sub.mkv"),
        Show("Harbour Notes EP%E ENG SUB.mkv"),
        // Spellings of the episode word.
        Show("Harbour Notes - %E [1080p].mkv"),
        Show("Harbour Notes EP%E 720p.mkv"),
        Show("Harbour Notes Ep.%E.mkv"),
        Show("Harbour Notes - Episode %e.mkv"),
        Show("Harbour Notes S02 E%E.mkv", 2),
        Show("Harbour Notes Ep %E Eng Sub.mkv"),
        Show("Harbour Notes Ep-%E.mkv"),
        Show("Harbour Notes EP-%E [Tamil].mkv"),
        Show("Harbour Notes Episode-%E.mkv"),
        Show("Harbour Notes S2-E%E.mkv", 2),
        Show("Harbour Notes 02x%E 720p.mkv", 2),
        Show("Harbour Notes - S02E%E - The Long Way.mkv", 2),
        Show("Harbour Notes S02.E%E.mkv", 2),
        Show("Harbour Notes Ep.%e of 12.mkv"),
        Show("Harbour Notes - Ep %E - The Long Way (1080p).mkv"),
        Show("Harbour Notes 2024 S02E%E.mkv", 2),
        Show("Harbour Notes Eps %E.mkv"),
        Show("Harbour Notes #%E.mkv", siblings = true),
        Show("Harbour Notes Ep. %E.mkv"),
        Show("Harbour_Notes_S02E%E_720p.mp4", 2),
        Show("Harbour Notes E%E.mkv", siblings = true),
        Show("Harbour Notes Season 2 Ep %E [Multi Audio].mkv", 2),
        Show("Harbour Notes S02 Ep%E 1080p HEVC.mkv", 2),
        Show("Harbour Notes - 2x%E - Long Way.mkv", 2),
        Show("Harbour Notes [S02E%E] [1080p].mkv", 2),
        Show("Harbour Notes (S02E%E).mkv", 2),
        Show("Harbour Notes - Season 02 Episode %E - 1080p.mkv", 2),
        Show("Harbour Notes Chapter %e.mkv", siblings = true),
        Show("Harbour Notes Part %e.mkv", siblings = true),
        Show("Harbour Notes Eps%E.mkv"),
        Show("Harbour Notes EPISODE %E.mkv"),
        Show("Harbour Notes s2 e%e.mkv", 2),
        Show("Harbour Notes S2E%e.mkv", 2),
        Show("Harbour Notes - S2 E%e.mkv", 2),
        Show("Harbour Notes Season2 Episode%e.mkv", 2),
        Show("Harbour Notes Season.2.Episode.%E.mkv", 2),
        Show("Harbour Notes.2x%E.mkv", 2),
        Show("Harbour Notes - %E.mkv"),
        Show("Harbour Notes %E.mkv", siblings = true),
        Show("Harbour Notes - Ep%E.mkv"),
        Show("Harbour Notes Episode %E of 12.mkv"),
        Show("Harbour Notes Ep %e/12.mkv"),
        // The code first.
        Show("Ep-%E S2 Harbour Notes [Multi] [HDRip].mkv", 2),
        Show("S02 EP%E Harbour Notes.mkv", 2),
        Show("Ep%E_Harbour_Notes_S2.mp4", 2),
        Show("[ENG SUB] Harbour Notes Season 2 Episode %e.mkv", 2),
        Show("%E - Harbour Notes S2.mkv", 2, siblings = true),
        Show("%E. Harbour Notes S02.mkv", 2, siblings = true),
        Show("E%E - Harbour Notes S2 720p.mkv", 2, siblings = true),
        Show("Episode %E - Harbour Notes S2.mkv", 2),
        Show("Ep %E | Harbour Notes | Season 2.mkv", 2),
        Show("S2E%E Harbour Notes.mkv", 2),
        Show("Season 2 Episode %e Harbour Notes.mkv", 2),
        Show("EP%E Harbour Notes 1080p.mkv"),
        Show("Episode %e Harbour Notes.mkv"),
        Show("E%E Harbour Notes.mkv", siblings = true),
        Show("S02E%E Harbour Notes 1080p.mkv", 2),
        Show("S02E%E - Harbour Notes.mkv", 2),
        Show("Ep.%E - Harbour Notes.mkv"),
        Show("#%E Harbour Notes.mkv", siblings = true),
        Show("%E Harbour Notes.mkv", siblings = true),
        // Spanish soap channels: "Capítulo 80", with or without the accent, a "Final" or a handle after it.
        Show("Harbour Notes Capítulo %e.mp4"),
        Show("Harbour Notes Capitulo %e Final.mp4"),
        Show("Harbour Notes Capitulo %e @Channel_Name.mp4"),
        Show("Harbour Notes Episodio %e.mp4"),
    )

    private val films: List<Film> = listOf(
        Film("Harbour Run (2019) 1080p BluRay x264.mkv"),
        Film("Harbour.Run.2019.1080p.BluRay.x264-GROUP.mkv"),
        Film("Harbour Run 2019 720p HDRip Hindi Dubbed ESub.mkv"),
        Film("Harbour Run (2019) [1080p] [BluRay] [5.1] [YTS.MX].mp4"),
        Film("Harbour Run (2019) Malayalam HQ HDRip 720p x264 AAC 1.4GB.mkv"),
        Film("[Group] Harbour Run (2019) 1080p WEB-DL.mkv"),
        Film("Harbour.Run.2019.REMASTERED.1080p.BluRay.x265.10bit-GROUP.mkv"),
        Film("Harbour Run 2019 Dual Audio [Hindi-English] 480p.mkv"),
        Film("Harbour Run (2019) 4K UHD HDR HEVC.mkv"),
        Film("Harbour Run 2019 CAMRip.mkv"),
        Film("Harbour Run (2019) Tamil TRUE WEB-DL - 1080p - AVC - (DD+5.1 - 640Kbps & AAC) - 2.8GB - ESub.mkv"),
        Film("Harbour.Run.2019.BDRip.x264.AC3.mkv"),
        Film("Harbour Run 2019 1080p Telugu HDRip x264 DD5.1 ESub.mkv"),
        Film("Harbour Run - 2019 - 720p.mkv"),
        Film("Harbour Run [2019] 1080p.mkv"),
        Film("Harbour Run (2019) Hindi 1080p AMZN WEB-DL DDP5.1 ESub.mkv"),
        Film("Harbour.Run.2019.1080p.BluRay.x264.DTS-HD.MA.5.1-GROUP.mkv"),
        Film("Harbour Run.2019.mkv"),
        Film("Harbour Run (2019).mkv"),
        Film("Harbour Run.mp4", year = null),
        Film("Harbour_Run_2019_720p_BRRip_x264.mp4"),
        Film("Harbour Run 2019 HDTC 720p Hindi.mkv"),
        Film("Harbour Run (2019) 1080p 10bit BluRay 6CH x265 HEVC-PSA.mkv"),
        Film("Harbour Run (2019) Malayalam 1080p NF WEB-DL DDP5.1 H.264 ESub.mkv"),
        Film("@Channel Harbour Run (2019) 720p HDRip.mkv"),
        Film("Harbour Run 2019 Hindi 720p WEB-HD x264 ESub 1GB.mkv"),
        Film("Harbour Run (2019) {tmdb-123456} 1080p.mkv"),
        Film("Harbour Run (2019) [tt1234567] 1080p.mkv"),
        Film("Harbour Run 2019 Full Movie HD.mp4"),
        Film("Harbour Run (2019) Full Movie Tamil.mp4"),
        Film("Harbour Run 2019 1080p BluRay REMUX AVC DTS-HD MA 5.1-GROUP.mkv"),
        Film("Harbour Run 2019 UNRATED 1080p BluRay.mkv"),
        Film("Harbour Run 2019 EXTENDED 1080p BluRay.mkv"),
        Film("Harbour Run (2019) IMAX 2160p.mkv"),
        Film("Harbour Run 2019 DVDRip XviD-GROUP.avi"),
        Film("Harbour Run 2019 480p HEVC 300MB.mkv"),
        // Found in real chats: an audio layout glued to the codec, a group's "-SP", a size after a dash.
        Film("Harbour.Run.1995.1080p.10bit.BluRay.ENG.DDP5.1x265.HEVC.ESub.mkv", year = 1995),
        Film("Harbour.Run.1981.DUAL.1080p.BD-Rip.DDP5.1.10bit.AV1.-.SP.mkv", year = 1981),
        Film("Harbour.Run.2013.BluRay.1080p.10bit.Malayalam.DD5.1.x265.mkv", year = 2013),
        Film("[MM] Harbour Run (2023) Malayalam HDRip - 720p - (DD5.1 - 1.4GB).mkv", year = 2023),
        Film("[GKL] - Harbour Run (2024) Malayalam HQ HDRip - 1.4GB.mkv", year = 2024),
        Film("[GKL] - Harbour Run (2024) Malayalam HQ HDRip - 700MB.mkv", year = 2024),
        Film("[GKL]  - Harbour Run (2024) Malayalam HQ HDRip - 1.mkv", year = 2024),
        Film("[GKL]  - Harbour Run (2024) Malayalam HQ HDRip - 7.mkv", year = 2024),
        Film("Harbour Run (2019) 720p WEBRip x264 AAC [Hindi + English] ESub.mkv"),
        Film("Harbour Run 2019 WEB-DL 1080p x264 Hindi DD 5.1 Eng DD 5.1 ESubs.mkv"),
        // Titles that carry digits, which are not episodes.
        Film("1917 (2019) 1080p BluRay.mkv", title = "1917"),
        Film("2012 (2009) 1080p BluRay.mkv", title = "2012", year = 2009),
        Film("Blade Harbour 2049 (2017) 1080p.mkv", title = "Blade Harbour 2049", year = 2017),
        Film("Harbour Run 2 (2021) 1080p.mkv", title = "Harbour Run 2", year = 2021),
        Film("Harbour Run Part 2 (2021) 1080p.mkv", title = "Harbour Run Part 2", year = 2021),
        Film("Harbour Run 3 2023 720p.mkv", title = "Harbour Run 3", year = 2023),
        Film("Harbour Run - The Return (2021) 1080p.mkv", title = "Harbour Run - The Return", year = 2021),
        Film("Harbour Run Chapter 2 (2022).mkv", title = "Harbour Run Chapter 2", year = 2022),
    )

    @Test
    fun `every show style reads the show, season and episode`() {
        val bad = mutableListOf<String>()
        for (style in shows) {
            // A style with no code of its own is read by its siblings, in the shelf test below.
            if (style.siblings) continue
            val name = style.template.replace("%E", "06").replace("%e", "6")
            val p = MediaName.parse(name, maxYear = 2027)
            if (!p.title.equals("Harbour Notes", ignoreCase = true) || p.episode != 6 || (style.season != null && p.season != style.season)) {
                bad += "$name  ->  title='${p.title}' s=${p.season} e=${p.episode}"
            }
        }
        assertTrue("${bad.size} of ${shows.size} show styles read wrong:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun `the files of one show style land in one show`() {
        val bad = mutableListOf<String>()
        var id = 1L
        fun video(name: String) = MediaItem(
            chatId = 7, messageId = id++, fileId = 0, title = name, sizeBytes = 1, durationSec = 60,
            mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = id.toInt(),
            fileName = name, caption = "",
        )
        for (style in shows) {
            val files = (1..4).map { n -> video(style.template.replace("%E", "0$n").replace("%e", "$n")) }
            val entries = SeriesShelf.arrange(files)
            val grouped = entries.filterIsInstance<ShelfEntry.Show>()
            if (grouped.size != 1 || entries.size != 1) bad += "${style.template}  ->  ${entries.size} entries, ${grouped.size} shows"
        }
        assertTrue("${bad.size} of ${shows.size} show styles did not group:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun `numbered files that are no listing stay apart`() {
        var id = 1L
        fun video(name: String) = MediaItem(
            chatId = 7, messageId = id++, fileId = 0, title = name, sizeBytes = 1, durationSec = 60,
            mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = id.toInt(),
            fileName = name, caption = "",
        )
        val sets = listOf(
            listOf("Holiday Photos 1.mp4", "Holiday Photos 2.mp4"),
            listOf("Harbour Run 2.mkv", "Harbour Run 9.mkv", "Harbour Run 40.mkv"),
            listOf("Harbour Run 2 (2021).mkv", "Harbour Run 3 (2023).mkv", "Harbour Run 4 (2025).mkv"),
            listOf("Clip 100.mp4", "Clip 104.mp4"),
            listOf("Harbour Notes Season 1.mkv", "Harbour Notes Season 2.mkv", "Harbour Notes Season 3.mkv"),
            listOf("Harbour Run 1080p.mkv", "Harbour Run 720p.mkv", "Harbour Run 480p.mkv"),
            listOf("Harbour Run DD5.1.mkv", "Harbour Run DD2.0.mkv", "Harbour Run H.264.mkv"),
            listOf("[GKL] - Harbour Run (2024) Malayalam HQ HDRip - 1.4GB.mkv", "[GKL] - Harbour Run (2024) Malayalam HQ HDRip - 700MB.mkv"),
            listOf("[GKL]  - Harbour Run (2024) Malayalam HQ HDRip - 1.mkv", "[GKL]  - Harbour Run (2024) Malayalam HQ HDRip - 7.mkv"),
            listOf("Harbour.Run.1995.1080p.BluRay.DDP5.1x265.mkv", "Harbour.Run.1996.1080p.BluRay.DDP5.1x265.mkv", "Harbour.Run.1997.1080p.BluRay.DDP5.1x265.mkv"),
        )
        val bad = sets.filter { names -> SeriesShelf.arrange(names.map { video(it) }).any { it is ShelfEntry.Show } }
        assertTrue("grouped as a show: $bad", bad.isEmpty())
    }

    @Test
    fun `a chat of twenty thousand numbered files is arranged in time`() {
        var id = 1L
        val files = (1..20_000).map { n ->
            val name = if (n % 2 == 0) "Clip $n of the trip.mp4" else "Harbour Notes - ${n % 997} [1080p] ${n}x.mkv"
            MediaItem(
                chatId = 7, messageId = id++, fileId = 0, title = name, sizeBytes = 1, durationSec = 60,
                mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = n, fileName = name, caption = "",
            )
        }
        val started = System.nanoTime()
        SeriesShelf.arrange(files)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $ms ms", ms < 8_000)
    }

    @Test
    fun `garbage names do not break the sibling reading`() {
        var id = 1L
        val random = java.util.Random(7)
        val names = listOf("", " ", "1", "123", "1 2 3", "E1 E2 E3", "#1 #2 #3", "9".repeat(5_000), "a1 ".repeat(3_000), "\u0000\u0001 7", "\uD83D\uDE00 5 \uD83D\uDE00 6", ". . . 4 . . .") +
            List(300) { String(CharArray(random.nextInt(60)) { (random.nextInt(0x2FF)).toChar() }) + " " + random.nextInt(1000) }
        val items = names.map { name ->
            MediaItem(
                chatId = 7, messageId = id++, fileId = 0, title = name, sizeBytes = 1, durationSec = 60,
                mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = id.toInt(), fileName = name, caption = "",
            )
        }
        val started = System.nanoTime()
        SeriesShelf.arrange(items)
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 5_000_000_000L)
    }

    @Test
    fun `every film style reads the title and year and is no episode`() {
        val bad = mutableListOf<String>()
        for (film in films) {
            val p = MediaName.parse(film.name, maxYear = 2027)
            if (!p.title.equals(film.title, ignoreCase = true) || p.year != film.year || p.isEpisode) {
                bad += "${film.name}  ->  title='${p.title}' y=${p.year} s=${p.season} e=${p.episode}"
            }
        }
        assertTrue("${bad.size} of ${films.size} film styles read wrong:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
