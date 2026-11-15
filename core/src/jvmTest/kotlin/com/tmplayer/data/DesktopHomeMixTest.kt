package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The desktop's Home: [HomeFeatured.mix], [HomeRows.withArt] and [UiScale.forScreen]. */
class DesktopHomeMixTest {

    private var nextId = 1L

    private fun video(fileName: String, date: Int, art: Boolean = true, chatId: Long = 1) = MediaItem(
        chatId = chatId,
        messageId = nextId++,
        fileId = nextId.toInt(),
        title = fileName,
        sizeBytes = 1_500_000_000,
        durationSec = 6_000,
        mimeType = "video/x-matroska",
        thumbnailFileId = if (art) 7 else 0,
        miniThumbnail = null,
        date = date,
        fileName = fileName,
    )

    private fun fresh(vararg items: MediaItem) = HomeFeatured.pick(items.map { ShelfEntry.File(it) }, limit = 10)

    @Test
    fun `new and part way through take turns, two of the latter at most`() {
        val new = (1..6).map { video("New Film ${'A' + it} ${2000 + it}.mkv", date = 100 - it) }
        val going = (1..4).map { video("Going Film ${'A' + it} ${1990 + it}.mkv", date = 10 - it) }
        val mixed = HomeFeatured.mix(fresh(*new.toTypedArray()), going, complete = { true })
        assertEquals(listOf(new[0], going[0], new[1], going[1], new[2]).map { it.id }, mixed.map { it.item.id })
    }

    @Test
    fun `resume first leads with the video being watched, then new, then another`() {
        val new = (1..6).map { video("New Film ${'A' + it} ${2000 + it}.mkv", date = 100 - it) }
        val going = (1..4).map { video("Going Film ${'A' + it} ${1990 + it}.mkv", date = 10 - it) }
        val mixed = HomeFeatured.mix(fresh(*new.toTypedArray()), going, resumeFirst = true, complete = { true })
        assertEquals(listOf(going[0], new[0], going[1], new[1], new[2]).map { it.id }, mixed.map { it.item.id })
    }

    @Test
    fun `resume first without a complete resume page leads with a new one`() {
        val new = (1..2).map { video("New Film ${'A' + it} ${2000 + it}.mkv", date = 100 - it) }
        val going = video("Going Film Z 1999.mkv", date = 10)
        val mixed = HomeFeatured.mix(fresh(*new.toTypedArray()), listOf(going), resumeFirst = true, complete = { it.item.id != going.id })
        assertEquals(new.map { it.id }, mixed.map { it.item.id })
        assertTrue(HomeFeatured.mix(fresh(*new.toTypedArray()), emptyList(), resumeFirst = true, complete = { true }).isNotEmpty())
    }

    @Test
    fun `a video being watched with no match or art still leads when resume pages need none`() {
        val new = (1..2).map { video("New Film ${'A' + it} ${2000 + it}.mkv", date = 100 - it) }
        // No Telegram thumbnail and no online match: nothing but the generated cover to draw.
        val going = video("Kuruthi.2021.1080p.mkv", date = 10, art = false)
        assertTrue(!HomeFeatured.hasArt(going))
        val mixed = HomeFeatured.mix(
            fresh(*new.toTypedArray()), listOf(going), resumeFirst = true,
            complete = { it.item.id != going.id }, resumeComplete = { true },
        )
        assertEquals(listOf(going, new[0], new[1]).map { it.id }, mixed.map { it.item.id })
    }

    @Test
    fun `a new page still needs its match even when resume pages need none`() {
        val new = video("New Film B 2002.mkv", date = 100)
        val going = video("Going Film Z 1999.mkv", date = 10, art = false)
        val mixed = HomeFeatured.mix(
            fresh(new), listOf(going), resumeFirst = true,
            complete = { it.item.id != new.id }, resumeComplete = { true },
        )
        assertEquals(listOf(going.id), mixed.map { it.item.id })
    }

    @Test
    fun `a resume page with no art is still only a film or show of a real size`() {
        val small = video("Going Film Small 1999.mkv", date = 10, art = false).copy(sizeBytes = 50_000_000)
        val extra = video("Going Film Z 1999 Trailer.mkv", date = 10, art = false)
        val clip = video("VID_20241013_1200.mp4", date = 10, art = false)
        val mixed = HomeFeatured.mix(emptyList(), listOf(small, extra, clip), resumeFirst = true, complete = { true }, resumeComplete = { true })
        assertTrue(mixed.isEmpty())
    }

    @Test
    fun `the same video in new and being watched is one page, led by the resume one`() {
        val going = video("Going Film Z 1999.mkv", date = 10, art = false)
        val mixed = HomeFeatured.mix(
            fresh(going), listOf(going), resumeFirst = true,
            complete = { false }, resumeComplete = { true },
        )
        assertEquals(listOf(going.id), mixed.map { it.item.id })
    }

    @Test
    fun `a finished or stale record is never offered, art or not`() {
        val now = 100L * 24 * 3600 * 1000
        val finished = record(1, 5_900_000, 6_000_000, now)
        val stale = record(2, 600_000, 6_000_000, now - HomeFeatured.RESUME_FRESH_MS - 1)
        val live = record(3, 600_000, 6_000_000, now - 1000)
        assertEquals(listOf(3L), HomeFeatured.leading(listOf(finished, stale, live), now).map { it.messageId })
    }

    private fun record(messageId: Long, positionMs: Long, durationMs: Long, updatedAt: Long) = ResumeRecord(
        chatId = 1, messageId = messageId, fileId = 1, title = "t", chatTitle = "c", sizeBytes = 1, durationSec = (durationMs / 1000).toInt(),
        positionMs = positionMs, durationMs = durationMs, updatedAt = updatedAt,
    )

    @Test
    fun `only a started video from the last two weeks leads, newest first`() {
        val now = 100L * 24 * 3_600_000
        val day = 24 * 3_600_000L
        val hour = 3_600_000L
        val recent = record(1, 600_000, 2 * hour, now - 2 * day)
        val newer = record(2, 600_000, 2 * hour, now - 1 * day)
        val old = record(3, 600_000, 2 * hour, now - 20 * day)
        val atTheEnd = record(4, 2 * hour - 60_000, 2 * hour, now - day)
        val sampling = record(5, 10_000, 2 * hour, now - day)
        val noLength = record(6, 600_000, 0, now + day)
        assertEquals(
            listOf(6L, 2L, 1L),
            HomeFeatured.leading(listOf(recent, newer, old, atTheEnd, sampling, noLength), now).map { it.messageId },
        )
        assertTrue(HomeFeatured.leading(emptyList(), now).isEmpty())
    }

    @Test
    fun `either side fills the other's places`() {
        val going = (1..4).map { video("Going Film ${'A' + it} ${1990 + it}.mkv", date = 10 - it) }
        assertEquals(4, HomeFeatured.mix(emptyList(), going, complete = { true }).size)
        val new = (1..2).map { video("New Film ${'A' + it} ${2000 + it}.mkv", date = 100 - it) }
        assertEquals(2, HomeFeatured.mix(fresh(*new.toTypedArray()), emptyList(), complete = { true }).size)
    }

    @Test
    fun `an episode part way through stands for its show`() {
        val episode = video("Three.Body.Problem.S01E02.1080p.mkv", date = 10)
        val page = HomeFeatured.mix(emptyList(), listOf(episode), complete = { true }).single()
        assertNull(page.series)
        assertTrue(page.isShow)
        assertEquals("S01E02", page.loneEpisodeCode)
        assertEquals("three body problem", page.loneEpisode?.title?.lowercase())
        val film = HomeFeatured.mix(emptyList(), listOf(video("Night.Train.2019.1080p.mkv", date = 5)), complete = { true }).single()
        assertTrue(!film.isShow)
        assertNull(film.loneEpisodeCode)
    }

    @Test
    fun `nothing incomplete, and one page for a film in both`() {
        val bare = video("Bare Film 2001.mkv", date = 50, art = false)
        val both = video("Night.Train.2019.1080p.mkv", date = 40)
        val copy = video("Night.Train.2019.720p.mkv", date = 5, chatId = 2)
        // Telegram's own thumbnail does not make a page complete; only the online match does.
        val mixed = HomeFeatured.mix(fresh(bare, both), listOf(copy), complete = { it.item.id != bare.id })
        assertEquals(listOf(both.id), mixed.map { it.item.id })
        assertEquals(2, HomeFeatured.mix(fresh(bare, both), listOf(copy), complete = { true }).size)
        assertTrue(HomeFeatured.mix(fresh(bare, both), listOf(copy), complete = { false }).isEmpty())
    }

    @Test
    fun `Continue watching's small files and clips stay out of the carousel`() {
        val small = video("Small Film 2001.mkv", date = 10).copy(sizeBytes = 100 * SizeFilter.MB)
        val clip = video("holiday_clip_b.mp4", date = 9).copy(durationSec = 90)
        val film = video("Night.Train.2019.1080p.mkv", date = 8)
        val mixed = HomeFeatured.mix(emptyList(), listOf(small, clip, film), complete = { true })
        assertEquals(listOf(film.id), mixed.map { it.item.id })
    }

    @Test
    fun `a row keeps only tiles with a picture`() {
        val pictured = video("Pictured 2001.mkv", date = 2)
        val bare = video("Bare 2002.mkv", date = 1, art = false)
        val recent = HomeRow.Recent(listOf(ShelfEntry.File(pictured), ShelfEntry.File(bare)), loaded = true)
        assertEquals(listOf(pictured.id), (HomeRows.withArt(recent, emptyMap()) as HomeRow.Recent).entries.map { (it as ShelfEntry.File).item.id })
        assertNull(HomeRows.withArt(HomeRow.Recent(listOf(ShelfEntry.File(bare)), loaded = true), emptyMap()))
        // Still loading: as it is.
        val loading = HomeRow.Recent(emptyList(), loaded = false)
        assertEquals(loading, HomeRows.withArt(loading, emptyMap()))
    }

    @Test
    fun `a record keeps its card without a picture and goes only when gone`() {
        fun record(item: MediaItem) = ResumeRecord(
            chatId = item.chatId, messageId = item.messageId, fileId = 1, title = item.title, chatTitle = "",
            sizeBytes = item.sizeBytes, durationSec = item.durationSec, positionMs = 60_000, durationMs = 6_000_000, updatedAt = 1,
        )
        val pictured = video("Pictured 2001.mkv", date = 2)
        val bare = video("Bare 2002.mkv", date = 1, art = false)
        val row = HomeRow.Continue(listOf(record(pictured), record(bare)))
        // Kept without a picture known yet: the generated cover stands in until it arrives.
        assertEquals(listOf(pictured.messageId, bare.messageId), (HomeRows.withArt(row, emptyMap()) as HomeRow.Continue).records.map { it.messageId })
        val art = listOf(pictured, bare).associateBy { SettingsStore.progressKey(it.chatId, it.messageId) }
        assertEquals(listOf(pictured.messageId, bare.messageId), (HomeRows.withArt(row, art) as HomeRow.Continue).records.map { it.messageId })
        // Dropped only when the video is gone.
        val goneBare = HomeRows.withArt(row, art) { _, message -> message == bare.messageId } as HomeRow.Continue
        assertEquals(listOf(pictured.messageId), goneBare.records.map { it.messageId })
        assertNull(HomeRows.withArt(HomeRow.WatchLater(listOf(WatchLaterRecord(chatId = bare.chatId, messageId = bare.messageId, fileId = 9, title = "Bare", fileName = "bare.mkv", chatTitle = "Films", sizeBytes = 1, durationSec = 1, mimeType = "video/mp4", addedAt = 1))), art) { _, _ -> true })
    }

    @Test
    fun `the unchosen interface size follows the screen`() {
        assertEquals(UiScale.Largest, UiScale.forScreen(1920, 1080))
        assertEquals(UiScale.Largest, UiScale.forScreen(2560, 1440))
        assertEquals(UiScale.Largest, UiScale.forScreen(3840, 2160))
        assertEquals(UiScale.Larger, UiScale.forScreen(1536, 864))
        assertEquals(UiScale.Larger, UiScale.forScreen(1440, 900))
        assertEquals(UiScale.Default, UiScale.forScreen(1366, 768))
        assertEquals(UiScale.Default, UiScale.forScreen(1280, 800))
        assertTrue(UiScale.from(null, UiScale.Largest) == UiScale.Largest && UiScale.from("Default", UiScale.Largest) == UiScale.Default)
    }
}
