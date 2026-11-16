package com.tmplayer.ui.browse

import com.tmplayer.data.DetailAction
import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.MediaItem
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.i18n.L
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The detail page's download line and Watched switch (Raju 5.1, 5.2): a number under the buttons
 * while a video comes down, in the stage's own word, and a switch that is on drawn as on.
 */
class DetailDownloadLineTest {

    private val s = L.messages
    private val mb = 1024L * 1024

    private fun progress(stage: OfflineDownloads.Stage, done: Long, total: Long) = OfflineDownloads.Progress(
        request = DownloadRequest.from(
            MediaItem(
                chatId = 2, messageId = 5, fileId = 5, title = "Clip", sizeBytes = total, durationSec = 0,
                mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 0,
            ),
            "Chat",
        ),
        downloadedBytes = done,
        totalBytes = total,
        stage = stage,
    )

    @Test
    fun `a running download says downloading, how far and how much`() {
        val line = downloadStatusLine(progress(OfflineDownloads.Stage.Running, 140 * mb, 2_800 * mb), s)
        assertEquals(
            listOf(s.gridStageDownloading, s.formatter.percent(0.05), s.commonOfTotal(s.formatter.bytes(140 * mb), s.formatter.bytes(2_800 * mb))).joinToString(", "),
            line,
        )
        assertTrue(line.startsWith(s.gridStageDownloading))
        assertTrue("a number, not only a ring", line.any { it.isDigit() })
    }

    @Test
    fun `a paused download says Download paused, not the bare word that reads as a button`() {
        val line = downloadStatusLine(progress(OfflineDownloads.Stage.Paused, 140 * mb, 2_800 * mb), s)
        assertTrue(line.startsWith(s.gridStagePausedLong))
        assertFalse(line.startsWith(s.gridStageDownloading))
    }

    @Test
    fun `a download that has not started says what it is waiting for and its size, no percent of nothing`() {
        val queued = downloadStatusLine(progress(OfflineDownloads.Stage.Queued, 0, 700 * mb), s)
        assertEquals("${s.gridStageQueued}, ${s.formatter.bytes(700 * mb)}", queued)
        val offline = downloadStatusLine(progress(OfflineDownloads.Stage.Offline, 0, 700 * mb), s)
        assertTrue(offline.startsWith(s.gridStageWaiting))
        val wifi = downloadStatusLine(progress(OfflineDownloads.Stage.NoWifi, 0, 700 * mb), s)
        assertTrue(wifi.startsWith(s.gridStageNoWifi))
    }

    @Test
    fun `an unknown size and a failure never throw`() {
        assertEquals(s.gridStageDownloading, downloadStatusLine(progress(OfflineDownloads.Stage.Running, 0, 0), s))
        assertEquals(s.gridStageDownloading, downloadStatusLine(progress(OfflineDownloads.Stage.Moving, 0, 0), s))
        assertTrue(downloadStatusLine(progress(OfflineDownloads.Stage.Failed, 5 * mb, 10 * mb), s).startsWith(s.gridStageFailed))
    }

    @Test
    fun `a Watched button that is on is drawn on, with the check, and a Watch later one with its clock`() {
        val off = detailRow(DetailAction.MarkWatched, s, null, "", {})
        val on = detailRow(DetailAction.MarkUnwatched, s, null, "", {})
        assertFalse(off.on)
        assertTrue(on.on)
        // The check, in both: the fill says the difference, so the icon does not swap to a cross.
        assertEquals(off.roundIcon, on.roundIcon)
        // One word under it whichever way it stands.
        assertEquals(roundLabel(off, s), roundLabel(on, s))
        assertEquals(s.detailRoundWatched, roundLabel(on, s))
        val later = detailRow(DetailAction.AddWatchLater, s, null, "", {})
        val laterOn = detailRow(DetailAction.RemoveWatchLater, s, null, "", {})
        assertFalse(later.on)
        assertTrue(laterOn.on)
        assertEquals(roundLabel(later, s), roundLabel(laterOn, s))
    }

    @Test
    fun `Cancel is the word on the detail button, as on the Downloads page`() {
        val cancel = detailRow(DetailAction.CancelDownload, s, null, "", {})
        assertEquals(s.commonCancel, roundLabel(cancel, s))
        assertEquals("Cancel", s.commonCancel)
    }
}
