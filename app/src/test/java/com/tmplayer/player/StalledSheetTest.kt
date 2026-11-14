package com.tmplayer.player

import com.tmplayer.data.OpeningStall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.tmplayer.player.StalledSheet.Action

/** The "this video hasn't started" sheet's buttons (U1), and what makes it come at all. */
class StalledSheetTest {

    @Test
    fun `try again leads, back ends, and the copies sit between`() {
        val all = StalledSheet.actions(OpeningStall.Reason.Slow, canDownload = true, lighterCopy = true, canOpenInAnotherApp = true)
        assertEquals(listOf(Action.TryAgain, Action.DownloadFirst, Action.LighterCopy, Action.Back), all)
    }

    @Test
    fun `a chat that does not allow a copy gets no download, and the sheet still has a way out`() {
        val few = StalledSheet.actions(OpeningStall.Reason.Slow, canDownload = false, lighterCopy = false, canOpenInAnotherApp = false)
        assertEquals(listOf(Action.TryAgain, Action.Back), few)
    }

    @Test
    fun `with no line to Telegram a lighter copy is not offered`() {
        val none = StalledSheet.actions(OpeningStall.Reason.NoLine, canDownload = true, lighterCopy = true, canOpenInAnotherApp = false)
        assertFalse(Action.LighterCopy in none)
        assertTrue(Action.DownloadFirst in none)
    }

    @Test
    fun `open in another app waits for its hook and is not on the sheet yet`() {
        assertFalse(StalledSheet.OPEN_IN_ANOTHER_APP_ON_SHEET)
        for (reason in OpeningStall.Reason.entries) {
            assertFalse(Action.OpenInAnotherApp in StalledSheet.actions(reason, true, true, true))
        }
    }

    @Test
    fun `the flood wait line names the hold-off only while there is one`() {
        assertEquals(null, StalledSheet.floodWaitSeconds(0))
        assertEquals(null, StalledSheet.floodWaitSeconds(-5))
        assertEquals(42, StalledSheet.floodWaitSeconds(42))
        assertEquals(StalledSheet.MAX_FLOOD_NOTE_S, StalledSheet.floodWaitSeconds(StalledSheet.MAX_FLOOD_NOTE_S))
        assertEquals(null, StalledSheet.floodWaitSeconds(Int.MAX_VALUE))
    }

    @Test
    fun `a slow line that keeps sending never reaches the sheet`() {
        // A byte every 4 s for a minute: the count starts again with each, so it is never ten seconds old.
        var last = 0L
        for (now in 4_000L..60_000L step 4_000L) {
            assertFalse(OpeningStall.stalled(opening = true, local = false, lastProgressAtMs = last, nowMs = now))
            last = now
        }
        // And ten seconds of nothing is the sheet.
        assertTrue(OpeningStall.stalled(opening = true, local = false, lastProgressAtMs = last, nowMs = last + OpeningStall.STALL_MS))
    }
}
