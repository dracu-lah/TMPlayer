package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Test

private const val MB = 1024L * 1024

class StorageBreakdownTest {

    @Test
    fun `a video posted as a file counts as video, not as other`() {
        // Which is most of this app's library: these chats post remuxes as documents.
        val breakdown = StorageBreakdown.of(
            listOf("FileTypeDocument" to 900 * MB, "FileTypeVideo" to 100 * MB),
        )
        assertEquals(1000 * MB, breakdown.videoBytes)
        assertEquals(0L, breakdown.otherBytes)
    }

    @Test
    fun `thumbnails and profile photos are pictures`() {
        val breakdown = StorageBreakdown.of(
            listOf(
                "FileTypeThumbnail" to 12 * MB,
                "FileTypeProfilePhoto" to 8 * MB,
                "FileTypePhoto" to 4 * MB,
            ),
        )
        assertEquals(24 * MB, breakdown.pictureBytes)
        assertEquals(0L, breakdown.videoBytes)
    }

    @Test
    fun `anything unrecognised lands in other rather than being dropped`() {
        // The total has to keep matching the disk, whatever TDLib grows a file type for next.
        val breakdown = StorageBreakdown.of(
            listOf("FileTypeWallpaper" to 3 * MB, "FileTypeSomethingNew" to 5 * MB),
        )
        assertEquals(8 * MB, breakdown.otherBytes)
        assertEquals(8 * MB, breakdown.totalBytes)
    }

    @Test
    fun `rows for the same type add up`() {
        // TDLib reports per chat, so one type arrives many times over.
        val breakdown = StorageBreakdown.of(
            listOf("FileTypeVideo" to 5 * MB, "FileTypeVideo" to 7 * MB),
        )
        assertEquals(12 * MB, breakdown.videoBytes)
    }

    @Test
    fun `empty and negative rows contribute nothing`() {
        val breakdown = StorageBreakdown.of(
            listOf("FileTypeVideo" to 0L, "FileTypePhoto" to -1L),
        )
        assertEquals(StorageBreakdown.EMPTY, breakdown)
        assertEquals(0L, breakdown.totalBytes)
    }
}
