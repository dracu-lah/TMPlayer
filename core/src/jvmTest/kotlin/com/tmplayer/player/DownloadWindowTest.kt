package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seek matrix from PLAN.md §6, expressed against the arithmetic that decides whether a
 * read can be served or the download has to be moved.
 */
class DownloadWindowTest {

    private val size = 1_000_000L

    @Test
    fun `reads inside the downloaded window return what is left of it`() {
        val available = DownloadWindow.availableAt(
            position = 500,
            size = size,
            downloadOffset = 0,
            downloadedPrefixSize = 2_000,
            completed = false,
        )
        assertEquals(1_500, available)
    }

    @Test
    fun `a byte before the window is not readable`() {
        val available = DownloadWindow.availableAt(
            position = 100,
            size = size,
            downloadOffset = 5_000,
            downloadedPrefixSize = 2_000,
            completed = false,
        )
        assertEquals(0, available)
    }

    @Test
    fun `a byte past the window is not readable`() {
        val available = DownloadWindow.availableAt(
            position = 9_000,
            size = size,
            downloadOffset = 0,
            downloadedPrefixSize = 8_000,
            completed = false,
        )
        assertEquals(0, available)
    }

    @Test
    fun `the byte exactly at the window edge is not readable yet`() {
        val available = DownloadWindow.availableAt(
            position = 8_000,
            size = size,
            downloadOffset = 0,
            downloadedPrefixSize = 8_000,
            completed = false,
        )
        assertEquals(0, available)
    }

    @Test
    fun `a fully downloaded file reads to the end from anywhere`() {
        val available = DownloadWindow.availableAt(
            position = 999_000,
            size = size,
            downloadOffset = 0,
            downloadedPrefixSize = 0,
            completed = true,
        )
        assertEquals(1_000, available)
    }

    @Test
    fun `reading past the end of a finished file yields nothing rather than a negative count`() {
        val available = DownloadWindow.availableAt(
            position = size + 10,
            size = size,
            downloadOffset = 0,
            downloadedPrefixSize = 0,
            completed = true,
        )
        assertEquals(0, available)
    }

    // --- Seek case 1: backwards into an area TDLib has moved past ---
    @Test
    fun `seeking backwards restarts the download`() {
        assertTrue(
            DownloadWindow.needsRestart(
                position = 1_000,
                downloadOffset = 500_000,
                downloadedPrefixSize = 10_000,
                active = true,
                completed = false,
            ),
        )
    }

    // --- Seek case 2: a long jump forward the running download will never reach in time ---
    @Test
    fun `seeking far forward restarts the download`() {
        assertTrue(
            DownloadWindow.needsRestart(
                position = 500_000_000,
                downloadOffset = 0,
                downloadedPrefixSize = 1_000,
                active = true,
                completed = false,
            ),
        )
    }

    // --- Seek case 3: a small nudge forward is cheaper to wait out than to restart ---
    @Test
    fun `seeking just ahead of the buffer lets the running download catch up`() {
        assertFalse(
            DownloadWindow.needsRestart(
                position = 1_000_000 + 1_000,
                downloadOffset = 0,
                downloadedPrefixSize = 1_000_000,
                active = true,
                completed = false,
            ),
        )
    }

    // --- Seek case 4: normal sequential playback inside the window ---
    @Test
    fun `reading inside the window never restarts`() {
        assertFalse(
            DownloadWindow.needsRestart(
                position = 400_000,
                downloadOffset = 0,
                downloadedPrefixSize = 500_000,
                active = true,
                completed = false,
            ),
        )
    }

    // --- Seek case 5: TDLib gave up while we sat at the edge of the window ---
    @Test
    fun `a stalled download at the buffer edge is restarted`() {
        assertTrue(
            DownloadWindow.needsRestart(
                position = 500_000,
                downloadOffset = 0,
                downloadedPrefixSize = 500_000,
                active = false,
                completed = false,
            ),
        )
    }

    // --- Seek case 6: everything is already on disk, so any position is fine ---
    @Test
    fun `a completed file never restarts`() {
        assertFalse(
            DownloadWindow.needsRestart(
                position = 0,
                downloadOffset = 900_000,
                downloadedPrefixSize = 0,
                active = false,
                completed = true,
            ),
        )
    }

    /**
     * The sequence that took playback down on a real film: seek forward, watch, seek back.
     *
     * TDLib had moved its window to the forward position, so the far end of that window was a
     * long way past the byte the player now wanted. Judging the read on that end alone said
     * "half a megabyte available" for bytes that had been freed, and the partial file keeps its
     * length, so the read succeeded and returned a hole. The extractor died on the zeroes.
     * Only the near end of the window rules that out.
     */
    @Test
    fun `a byte behind a window that has moved forward is not readable`() {
        val available = DownloadWindow.availableAt(
            position = 120_000,
            size = size,
            downloadOffset = 600_000,
            downloadedPrefixSize = 500_000,
            completed = false,
        )
        assertEquals(0, available)
    }

    @Test
    fun `a negative position reads nothing`() {
        assertEquals(
            0,
            DownloadWindow.availableAt(
                position = -1,
                size = size,
                downloadOffset = 0,
                downloadedPrefixSize = 5_000,
                completed = false,
            ),
        )
    }
}
