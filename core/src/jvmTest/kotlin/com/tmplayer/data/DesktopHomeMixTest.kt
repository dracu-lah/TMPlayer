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
        val mixed = HomeFeatured.mix(fresh(*new.toTypedArray()), going)
        assertEquals(listOf(new[0], going[0], new[1], going[1], new[2]).map { it.id }, mixed.map { it.item.id })
    }

    @Test
    fun `either side fills the other's places`() {
        val going = (1..4).map { video("Going Film ${'A' + it} ${1990 + it}.mkv", date = 10 - it) }
        assertEquals(4, HomeFeatured.mix(emptyList(), going).size)
        val new = (1..2).map { video("New Film ${'A' + it} ${2000 + it}.mkv", date = 100 - it) }
        assertEquals(2, HomeFeatured.mix(fresh(*new.toTypedArray()), emptyList()).size)
    }

    @Test
    fun `an episode part way through stands for its show`() {
        val episode = video("Three.Body.Problem.S01E02.1080p.mkv", date = 10)
        val page = HomeFeatured.mix(emptyList(), listOf(episode)).single()
        assertNull(page.series)
        assertTrue(page.isShow)
        assertEquals("S01E02", page.loneEpisodeCode)
        assertEquals("three body problem", page.loneEpisode?.title?.lowercase())
        val film = HomeFeatured.mix(emptyList(), listOf(video("Night.Train.2019.1080p.mkv", date = 5))).single()
        assertTrue(!film.isShow)
        assertNull(film.loneEpisodeCode)
    }

    @Test
    fun `nothing without a picture, and one page for a film in both`() {
        val bare = video("Bare Film 2001.mkv", date = 50, art = false)
        val both = video("Night.Train.2019.1080p.mkv", date = 40)
        val copy = video("Night.Train.2019.720p.mkv", date = 5, chatId = 2)
        val mixed = HomeFeatured.mix(fresh(bare, both), listOf(copy))
        assertEquals(listOf(both.id), mixed.map { it.item.id })
        // The providers' picture counts as one.
        assertEquals(2, HomeFeatured.mix(fresh(bare, both), listOf(copy), online = { it.item.id == bare.id }).size)
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
    fun `a record shows once its video is known to have a picture`() {
        fun record(item: MediaItem) = ResumeRecord(
            chatId = item.chatId, messageId = item.messageId, fileId = 1, title = item.title, chatTitle = "",
            sizeBytes = item.sizeBytes, durationSec = item.durationSec, positionMs = 60_000, durationMs = 6_000_000, updatedAt = 1,
        )
        val pictured = video("Pictured 2001.mkv", date = 2)
        val bare = video("Bare 2002.mkv", date = 1, art = false)
        val row = HomeRow.Continue(listOf(record(pictured), record(bare)))
        assertNull(HomeRows.withArt(row, emptyMap()))
        val art = listOf(pictured, bare).associateBy { SettingsStore.progressKey(it.chatId, it.messageId) }
        assertEquals(listOf(pictured.messageId), (HomeRows.withArt(row, art) as HomeRow.Continue).records.map { it.messageId })
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
