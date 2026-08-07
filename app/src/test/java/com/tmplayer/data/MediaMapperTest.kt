package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaMapperTest {

    @Test
    fun `a video mime type is enough on its own`() {
        assertTrue(MediaMapper.looksLikeVideo("whatever", "video/mp4"))
        assertTrue(MediaMapper.looksLikeVideo("", "VIDEO/X-MATROSKA"))
    }

    @Test
    fun `movies uploaded as generic binaries are recognised by extension`() {
        // How most film remuxes actually arrive in a Telegram channel.
        assertTrue(MediaMapper.looksLikeVideo("Dune.2021.2160p.mkv", "application/octet-stream"))
        assertTrue(MediaMapper.looksLikeVideo("clip.AVI", ""))
    }

    @Test
    fun `subtitles archives and other junk are kept out of the grid`() {
        assertFalse(MediaMapper.looksLikeVideo("Dune.2021.srt", "application/x-subrip"))
        assertFalse(MediaMapper.looksLikeVideo("pack.rar", "application/x-rar"))
        assertFalse(MediaMapper.looksLikeVideo("cover.jpg", "image/jpeg"))
        assertFalse(MediaMapper.looksLikeVideo("no-extension", ""))
    }

    @Test
    fun `a file name wins over a caption`() {
        assertEquals(
            "Arrival.2016.mkv",
            MediaMapper.displayTitle("Arrival.2016.mkv", "Arrival — great film", "Video"),
        )
    }

    @Test
    fun `a caption is used when the upload has no file name`() {
        assertEquals(
            "Arrival (2016)",
            MediaMapper.displayTitle("", "Arrival (2016)\n1080p BluRay", "Video"),
        )
    }

    @Test
    fun `blank leading caption lines are skipped`() {
        assertEquals("Real title", MediaMapper.displayTitle(null, "\n   \nReal title\nmore", "Video"))
    }

    @Test
    fun `the fallback is used when there is nothing else`() {
        assertEquals("Video", MediaMapper.displayTitle(null, null, "Video"))
        assertEquals("Video", MediaMapper.displayTitle("   ", "  ", "Video"))
    }

    @Test
    fun `sizes read the way a person would say them`() {
        assertEquals("1.5 GB", MediaMapper.formatSize(1_610_612_736))
        assertEquals("700 MB", MediaMapper.formatSize(734_003_200))
        assertEquals("", MediaMapper.formatSize(0))
    }

    @Test
    fun `durations read the way a person would say them`() {
        assertEquals("2h 22m", MediaMapper.formatDuration(8_520))
        assertEquals("47m", MediaMapper.formatDuration(2_820))
        assertEquals("1m", MediaMapper.formatDuration(20))
        assertEquals("", MediaMapper.formatDuration(0))
    }
}
