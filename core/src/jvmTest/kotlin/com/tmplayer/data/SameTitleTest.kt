package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One film or show once: [SameTitle], the carousel ([HomeFeatured]) and Home's rows ([HomeRows]). */
class SameTitleTest {

    private fun video(
        chatId: Long,
        messageId: Long,
        fileName: String,
        caption: String = "",
        date: Int = messageId.toInt(),
        duration: Int = 8_000,
        onDevice: Boolean = false,
    ) = MediaItem(
        chatId = chatId,
        messageId = messageId,
        fileId = (chatId * 100 + messageId).toInt(),
        title = fileName,
        sizeBytes = 1_500 * SizeFilter.MB,
        durationSec = duration,
        mimeType = "video/x-matroska",
        thumbnailFileId = 7,
        miniThumbnail = null,
        date = date,
        fileName = fileName,
        caption = caption,
        onDevice = onDevice,
    )

    private val noOnline: (MediaItem) -> String? = { null }

    @Test
    fun `a film's name and year come from the caption when the file name has none`() {
        assertEquals("film:khalifa|2026", SameTitle.filmKey(video(1, 1, "Khalifa.mkv", caption = "Khalifa (2026)")))
        assertEquals("film:khalifa|2026", SameTitle.filmKey(video(2, 2, "Khalifa.2026.1080p.WEB-DL.mkv")))
        // No year anywhere: a name alone never folds two videos.
        assertNull(SameTitle.filmKey(video(3, 3, "Lecture 1080p.mkv")))
        // An episode is not a film.
        assertNull(SameTitle.filmKey(video(4, 4, "Harbour.Notes.S01E02.mkv")))
    }

    @Test
    fun `the same film in two chats is one tile, the best copy leading`() {
        val entries = listOf(
            ShelfEntry.File(video(1, 10, "Khalifa.mkv", caption = "Khalifa (2026)", date = 300)),
            ShelfEntry.File(video(2, 11, "Night.Train.2019.1080p.mkv", date = 200)),
            ShelfEntry.File(video(3, 12, "Khalifa 2026 2160p.mkv", date = 100)),
        )
        val folded = SameTitle.dedupe(entries, noOnline)
        assertEquals(2, folded.size)
        val film = (folded.first() as ShelfEntry.File).item
        // Where the newest copy stood, led by the 4K copy, with both as its copies.
        assertEquals(12L, film.messageId)
        assertEquals(setOf(10L, 12L), film.versions.map { it.messageId }.toSet())
    }

    @Test
    fun `one online match is one tile whatever the names say`() {
        val entries = listOf(
            ShelfEntry.File(video(1, 1, "KLF_final_cut.mkv")),
            ShelfEntry.File(video(2, 2, "Khalifa.2026.720p.mkv")),
        )
        val folded = SameTitle.dedupe(entries) { "tmdb:film:42" }
        assertEquals(1, folded.size)
        assertEquals(2, (folded.single() as ShelfEntry.File).item.versions.size)
    }

    @Test
    fun `the carousel shows a film once, posted twice without a shared file name`() {
        val picks = HomeFeatured.pick(
            listOf(
                ShelfEntry.File(video(1, 10, "Khalifa.mkv", caption = "Khalifa (2026)", date = 500)),
                ShelfEntry.File(video(2, 11, "Night.Train.2019.1080p.mkv", date = 400)),
                ShelfEntry.File(video(3, 12, "Khalifa (2026) 1080p.mkv", date = 300)),
                ShelfEntry.File(video(4, 13, "Ledger.of.Small.Hours.2021.mkv", date = 200)),
            ),
            online = noOnline,
        )
        val titles = picks.map { SameTitle.filmKey(it.item) }
        assertEquals(listOf("film:khalifa|2026", "film:night train|2019", "film:ledger of small hours|2021"), titles)
    }

    @Test
    fun `the desktop carousel does not repeat a film from Continue watching under another name`() {
        val fresh = HomeFeatured.pick(listOf(ShelfEntry.File(video(1, 10, "Khalifa 2026 1080p.mkv"))), online = noOnline)
        val going = listOf(video(2, 20, "Khalifa.mkv", caption = "Khalifa (2026)"))
        val mixed = HomeFeatured.mix(fresh, going, complete = { true }, matched = noOnline)
        assertEquals(1, mixed.size)
    }

    @Test
    fun `Home's Recently added lists a film once`() {
        val chat = ChatSummary(1, "Films", null, 0, ChatKind.Channel)
        val other = ChatSummary(2, "More films", null, 0, ChatKind.Channel)
        val rows = HomeRows.build(
            continueWatching = emptyList(),
            favourites = listOf(chat, other),
            loaded = mapOf(
                1L to listOf(video(1, 10, "Khalifa.mkv", caption = "Khalifa (2026)", date = 300), video(1, 11, "Night.Train.2019.mkv", date = 200)),
                2L to listOf(video(2, 12, "Khalifa.2026.1080p.mkv", date = 100)),
            ),
            recent = emptyList(),
            autoFill = true,
        )
        val movies = rows.filterIsInstance<HomeRow.Recent>().single()
        val keys = movies.entries.map { SameTitle.filmKey((it as ShelfEntry.File).item) }
        assertEquals(keys.distinct(), keys)
        assertTrue("film:khalifa|2026" in keys)
    }

    @Test
    fun `a row read on past its tiles folds the copies it finds into the tile already there`() {
        val lead = video(1, 10, "Khalifa.2026.1080p.WEB-DL.mkv", date = 300)
        val row = HomeRow.Recent(listOf(ShelfEntry.File(lead)), loaded = true, chatIds = listOf(1L, 2L))
        // The next pages bring the same film from another chat, smaller, and from the same chat at 720p.
        val read = listOf(
            video(2, 11, "Khalifa (2026) 720p.mkv", date = 200).copy(sizeBytes = 700 * SizeFilter.MB),
            video(1, 12, "Khalifa.2026.2160p.mkv", date = 100).copy(sizeBytes = 9_000 * SizeFilter.MB),
            video(2, 13, "Night.Train.2019.1080p.mkv", date = 50),
        )
        val shown = HomeRows.seeAll(row, read)!!
        assertEquals(2, shown.size)
        val film = (shown.first() as ShelfEntry.File).item
        assertEquals(setOf(10L, 11L, 12L), film.versions.map { it.messageId }.toSet())
    }
}
