package com.tmplayer.ui.components

import com.tmplayer.data.MediaItem
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromoCoverBookTest {
    private var next = 1L

    private fun video(name: String, mini: ByteArray?) = MediaItem(
        chatId = 1, messageId = next++, fileId = 1, title = name, sizeBytes = 1, durationSec = 60,
        mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = mini, date = 1, fileName = name,
    )

    @After
    fun clear() = PromoCoverBook.clear()

    @Test
    fun anAdvertFoundInOneListIsKnownToEveryTile() {
        val ad = byteArrayOf(5, 4, 3, 2, 1)
        assertFalse(PromoCoverBook.isPromo(ad))
        PromoCoverBook.observe(
            listOf(
                video("Harbour Notes (2026).mkv", ad.copyOf()),
                video("Lantern Road (2025).mkv", ad.copyOf()),
                video("Quiet River (2024).mkv", ad.copyOf()),
            ),
        )
        assertTrue(PromoCoverBook.isPromo(ad.copyOf()))
        assertFalse(PromoCoverBook.isPromo(byteArrayOf(1, 1, 1)))
        assertFalse(PromoCoverBook.isPromo(null))
        assertFalse(PromoCoverBook.isPromo(ByteArray(0)))
    }

    @Test
    fun theEpisodesOfOneShowLeaveTheBookEmpty() {
        val art = byteArrayOf(7, 7, 7)
        PromoCoverBook.observe((1..5).map { video("Harbour.Notes.S01E0$it.mkv", art.copyOf()) })
        assertFalse(PromoCoverBook.isPromo(art))
    }

    @Test
    fun theBookKeepsAtMostItsCap() {
        repeat(PromoCoverBook.MAX_KEYS + 50) { n ->
            val art = byteArrayOf(n.toByte(), (n shr 8).toByte(), 9, 9)
            PromoCoverBook.observe(listOf("Alpha (2020)", "Bravo (2021)", "Charlie (2022)").map { video("$it.mkv", art.copyOf()) })
        }
        assertFalse(PromoCoverBook.isPromo(byteArrayOf(0, 0, 9, 9)))
        val last = PromoCoverBook.MAX_KEYS + 49
        assertTrue(PromoCoverBook.isPromo(byteArrayOf(last.toByte(), (last shr 8).toByte(), 9, 9)))
    }
}
