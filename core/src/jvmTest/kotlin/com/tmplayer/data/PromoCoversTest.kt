package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromoCoversTest {
    private var next = 1L

    private fun video(chat: Long, fileName: String, mini: ByteArray?) = MediaItem(
        chatId = chat,
        messageId = next++,
        fileId = 1,
        title = fileName,
        sizeBytes = 1,
        durationSec = 60,
        mimeType = "video/mp4",
        thumbnailFileId = 0,
        miniThumbnail = mini,
        date = 1,
        fileName = fileName,
    )

    private val ad = byteArrayOf(1, 2, 3, 4, 5, 6)
    private fun unique(n: Int) = byteArrayOf(9, (n shr 8).toByte(), n.toByte())

    @Test
    fun anAdOnThreeDifferentFilmsIsFlagged() {
        val items = listOf(
            video(1, "Harbour Notes (2026) Tamil 1080p.mkv", ad.copyOf()),
            video(1, "Lantern Road (2025) Hindi 720p.mkv", ad.copyOf()),
            video(1, "Quiet River (2024) 1080p.mkv", ad.copyOf()),
            video(1, "Other Film (2023) 1080p.mkv", unique(1)),
        )
        val found = PromoCovers.of(items)
        assertTrue(items.take(3).all { found.isPromo(it) })
        assertFalse(found.isPromo(items[3]))
        assertTrue(found.isPromo(ad.copyOf()))
        assertFalse(found.isPromo(unique(1)))
    }

    @Test
    fun theEpisodesOfOneShowWithOneImageAreNotFlagged() {
        val items = (1..6).map { video(1, "Harbour.Notes.S01E0$it.1080p.mkv", ad.copyOf()) }
        assertTrue(PromoCovers.of(items).isEmpty)
    }

    @Test
    fun twoCopiesOfOneFilmAreNotFlagged() {
        val items = listOf(
            video(1, "Harbour Notes (2026) 720p.mkv", ad.copyOf()),
            video(1, "Harbour Notes (2026) 1080p.mkv", ad.copyOf()),
            video(1, "Harbour Notes (2026) 2160p.mkv", ad.copyOf()),
        )
        assertTrue(PromoCovers.of(items).isEmpty)
    }

    @Test
    fun twoFilesBelowTheThresholdAreUntouched() {
        val items = listOf(
            video(1, "Harbour Notes (2026).mkv", ad.copyOf()),
            video(1, "Lantern Road (2025).mkv", ad.copyOf()),
        )
        assertTrue(PromoCovers.of(items).isEmpty)
    }

    @Test
    fun nullAndEmptyPreviewsAreIgnored() {
        val items = listOf(
            video(1, "Harbour Notes (2026).mkv", null),
            video(1, "Lantern Road (2025).mkv", null),
            video(1, "Quiet River (2024).mkv", null),
            video(1, "Other Film (2023).mkv", ByteArray(0)),
            video(1, "Fourth Film (2022).mkv", ByteArray(0)),
            video(1, "Fifth Film (2021).mkv", ByteArray(0)),
        )
        val found = PromoCovers.of(items)
        assertTrue(found.isEmpty)
        assertFalse(found.isPromo(items[0]))
        assertFalse(found.isPromo(ByteArray(0)))
        assertFalse(found.isPromo(null as ByteArray?))
    }

    @Test
    fun theCountIsPerChat() {
        // Two files in each of two chats: three or more in no single chat, and only two chats.
        val items = listOf(
            video(1, "Harbour Notes (2026).mkv", ad.copyOf()),
            video(1, "Lantern Road (2025).mkv", ad.copyOf()),
            video(2, "Quiet River (2024).mkv", ad.copyOf()),
            video(2, "Other Film (2023).mkv", ad.copyOf()),
        )
        assertTrue(PromoCovers.of(items).isEmpty)
    }

    @Test
    fun oneCoverAcrossThreeChatsWithDifferentTitlesIsFlagged() {
        val items = listOf(
            video(1, "Harbour Notes (2026).mkv", ad.copyOf()),
            video(2, "Lantern Road (2025).mkv", ad.copyOf()),
            video(3, "Quiet River (2024).mkv", ad.copyOf()),
        )
        assertTrue(PromoCovers.of(items).isPromo(ad))
    }

    @Test
    fun oneFilmInThreeChatsIsNotFlagged() {
        val items = (1L..3L).map { video(it, "Harbour Notes (2026).mkv", ad.copyOf()) }
        assertTrue(PromoCovers.of(items).isEmpty)
    }

    @Test
    fun keysTellPicturesApartAndAreNeverZero() {
        assertEquals(PromoCovers.keyOf(ad), PromoCovers.keyOf(ad.copyOf()))
        assertNotEquals(PromoCovers.keyOf(ad), PromoCovers.keyOf(byteArrayOf(1, 2, 3, 4, 5, 7)))
        assertNotEquals(0L, PromoCovers.keyOf(ByteArray(0)))
    }

    @Test
    fun threeThousandVideosAreWorkedOutQuickly() {
        val items = (0 until 3000).map { n ->
            val mini = if (n % 5 == 0) ad.copyOf() else unique(n)
            video(1, "Film Number ${'a' + n % 26}${'a' + n / 26 % 26}${'a' + n / 676 % 26} (20${10 + n % 14}).mkv", mini)
        }
        // Warm, as a list already on screen is: names parsed once and kept on the items.
        items.forEach { it.parsedName }
        val started = System.nanoTime()
        val found = PromoCovers.of(items)
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue(found.isPromo(ad))
        assertEquals(1, found.flagged.size)
        assertTrue("took $millis ms", millis < 500)
    }
}
