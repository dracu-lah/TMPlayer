package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** The chat listings kept between launches read back as they were written, and go on sign out. */
class ChatGridDiskTest {

    @Test
    fun aKeptListingReadsBackWhole() {
        val dir = Files.createTempDirectory("grids").toFile()
        try {
            val disk = ChatGridDisk(dir.resolve("chat-grids.json"))
            val item = MediaItem(
                chatId = 9, messageId = 77, fileId = 5, title = "Harbour Days", sizeBytes = 1_000, durationSec = 60,
                mimeType = "video/mp4", thumbnailFileId = 3, miniThumbnail = byteArrayOf(1, 2), date = 10, fileName = "a.mp4",
            )
            val archive = ArchiveFile(9, 70, 6, "set.zip", 2_000, 9, ArchiveKind.Zip)
            val grid = ChatGrid(
                items = listOf(item),
                cursors = MediaCursors(video = 77, document = 70, animation = 0, animationDone = true),
                endReached = false,
                archives = listOf(archive),
                selfDestructing = 2,
                scrollIndex = 12,
                scrollOffset = 40,
            )
            disk.save(listOf(SavedGrid(9, 3, grid, 1234)), Accounts.stamp)
            val back = ChatGridDisk(dir.resolve("chat-grids.json")).load().single()
            assertEquals(9L, back.chatId)
            assertEquals(3, back.topicId)
            assertEquals(1234L, back.savedAt)
            assertEquals(grid.cursors, back.grid.cursors)
            assertEquals(grid.archives, back.grid.archives)
            assertEquals(12, back.grid.scrollIndex)
            assertEquals(77L, back.grid.items.single().messageId)
            assertTrue(back.grid.items.single().miniThumbnail!!.contentEquals(byteArrayOf(1, 2)))
            disk.clear()
            assertFalse(dir.resolve("chat-grids.json").exists())
            assertTrue(disk.load().isEmpty())
            assertTrue("an unreadable file is no listing", ChatGridDisk.decode("{nope").isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }
}
