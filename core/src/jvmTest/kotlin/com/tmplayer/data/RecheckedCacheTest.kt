package com.tmplayer.data

import com.tmplayer.data.MediaItem.Locality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A tile's Cached badge, read again after the watch cache has moved on. */
class RecheckedCacheTest {

    private fun item(locality: Locality) = MediaItem(
        chatId = -1L, messageId = 1, fileId = 1, title = "Loki S01E04", sizeBytes = 10,
        durationSec = 0, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 0,
    ).withLocality(locality)

    @Test
    fun `an evicted copy loses its Cached badge`() {
        val after = item(Locality.Cached).recheckedCache(wholeOnDevice = false)
        assertEquals(Locality.Remote, after.locality)
        assertFalse(after.onDevice)
    }

    @Test
    fun `a video played since gains it`() {
        val after = item(Locality.Remote).recheckedCache(wholeOnDevice = true)
        assertEquals(Locality.Cached, after.locality)
        assertTrue(after.onDevice)
    }

    @Test
    fun `nothing moved hands back the same item`() {
        val cached = item(Locality.Cached)
        assertSame(cached, cached.recheckedCache(wholeOnDevice = true))
        val remote = item(Locality.Remote)
        assertSame(remote, remote.recheckedCache(wholeOnDevice = false))
    }

    @Test
    fun `a download is not the cache's to take away`() {
        val downloaded = item(Locality.Downloaded)
        assertSame(downloaded, downloaded.recheckedCache(wholeOnDevice = false))
    }
}
