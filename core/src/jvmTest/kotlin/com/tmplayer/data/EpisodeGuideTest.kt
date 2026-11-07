package com.tmplayer.data

import com.tmplayer.online.MetaAiring
import com.tmplayer.online.MetaEpisode
import com.tmplayer.online.MetaSeason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeGuideTest {

    private fun video(id: Long, name: String, chat: Long = 1) = MediaItem(
        chatId = chat,
        messageId = id,
        fileId = id.toInt(),
        title = name,
        sizeBytes = 100,
        durationSec = 60,
        mimeType = "video/x-matroska",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = id.toInt(),
        fileName = name,
    )

    private val chat = listOf(
        video(1, "Harbour.Notes.S01E01.mkv"),
        video(2, "Harbour.Notes.S01E02.mkv"),
        video(3, "Harbour.Notes.S02E01.mkv"),
        video(4, "Something.Else.2020.mkv"),
        video(5, "Harbour.Notes.S01E03.mkv", chat = 2),
    )

    @Test
    fun `the show is found among the same chat's videos only`() {
        val series = EpisodeGuide.seriesOf(chat[0], chat) ?: error("no series")
        assertEquals(listOf(1 to 2, 2 to 1), series.seasons.map { it.number to it.episodes.size })
        assertNull(EpisodeGuide.seriesOf(chat[3], chat))
    }

    @Test
    fun `seasons the chat lacks are missing once aired and upcoming before`() {
        val series = EpisodeGuide.seriesOf(chat[0], chat)
        val listed = listOf(MetaSeason(1, 3, "2021-01-01"), MetaSeason(2, 3, "2022-01-01"), MetaSeason(3, 8, "2023-01-01"))
        val next = MetaAiring(4, 1, "2027-03-12")
        val seasons = EpisodeGuide.seasons(series, listed, next, today = "2026-10-06")
        assertEquals(listOf(1, 2, 3, 4), seasons.map { it.number })
        assertEquals(
            listOf(EpisodeGuide.State.InChat, EpisodeGuide.State.InChat, EpisodeGuide.State.Missing, EpisodeGuide.State.Upcoming),
            seasons.map { it.state },
        )
        assertEquals(2, seasons[0].inChat)
        assertEquals(3, seasons[0].total)
        assertEquals("2027-03-12", seasons[3].airDate)
    }

    @Test
    fun `episodes are in chat, missing, or upcoming`() {
        val series = EpisodeGuide.seriesOf(chat[0], chat)
        val listed = listOf(
            MetaEpisode(1, 1, "One", airDate = "2021-01-01"),
            MetaEpisode(1, 2, "Two", airDate = "2021-01-08"),
            MetaEpisode(1, 3, "Three", airDate = "2021-01-15"),
            MetaEpisode(1, 4, "Four", airDate = "2026-12-01"),
            MetaEpisode(1, 5, "Five"),
        )
        val episodes = EpisodeGuide.episodes(1, series, listed, today = "2026-10-06")
        assertEquals(
            listOf(
                EpisodeGuide.State.InChat,
                EpisodeGuide.State.InChat,
                EpisodeGuide.State.Missing,
                EpisodeGuide.State.Upcoming,
                EpisodeGuide.State.Upcoming,
            ),
            episodes.map { it.state },
        )
        assertEquals("S01E03", episodes[2].code)
        assertEquals("One", episodes[0].meta?.name)
    }

    @Test
    fun `with no provider list the chat's own episodes still show`() {
        val series = EpisodeGuide.seriesOf(chat[0], chat)
        val episodes = EpisodeGuide.episodes(1, series, null, today = "2026-10-06")
        assertEquals(listOf(1, 2), episodes.map { it.number })
        val seasons = EpisodeGuide.seasons(series, emptyList(), null, today = "2026-10-06")
        assertEquals(listOf(1, 2), seasons.map { it.number })
    }

    /** Die Bergretter (2026-11-07): "S08 E06a" and "S08 E06b" share a number; the guide lists it once, on the first half. */
    @Test
    fun `the halves of a split episode are one row in the guide, on the first half`() {
        val names = listOf(
            "Die Bergretter - S08 E05.mp4",
            "Die Bergretter - S08 E06b - Schneeblind Teil 2.mp4",
            "Die Bergretter - S08 E06a - Schneeblind Teil 1.mp4",
        )
        val items = names.mapIndexed { at, name -> video(at + 1L, name) }
        val series = EpisodeGuide.seriesOf(items[0], items) ?: error("no series")
        val episodes = series.seasons.single().episodes
        assertEquals(listOf("S08E05", "S08E06a", "S08E06b"), episodes.map { it.code })
        assertEquals(listOf("", "a", "b"), episodes.map { it.partLetter })
        val guide = EpisodeGuide.episodes(8, series, null, today = "2026-10-06")
        assertEquals(listOf(5, 6), guide.map { it.number })
        assertEquals("S08E06a", guide.last().inChat?.code)
    }
}
