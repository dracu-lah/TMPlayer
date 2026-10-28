package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningStallTest {

    @Test
    fun `ten seconds with no new bytes while opening is a stall`() {
        assertFalse(OpeningStall.stalled(opening = true, local = false, lastProgressAtMs = 1_000, nowMs = 10_999))
        assertTrue(OpeningStall.stalled(opening = true, local = false, lastProgressAtMs = 1_000, nowMs = 11_000))
    }

    @Test
    fun `the connections are reopened before the sheet, and not for a file on this device`() {
        assertFalse(OpeningStall.nudgeDue(local = false, lastProgressAtMs = 1_000, nowMs = 6_999))
        assertTrue(OpeningStall.nudgeDue(local = false, lastProgressAtMs = 1_000, nowMs = 7_000))
        assertTrue(OpeningStall.NUDGE_MS < OpeningStall.STALL_MS)
        assertFalse(OpeningStall.nudgeDue(local = true, lastProgressAtMs = 0, nowMs = 60_000))
    }

    @Test
    fun `a new byte starts the count again`() {
        // The last byte came two seconds ago, however long the whole wait has been.
        assertFalse(OpeningStall.stalled(opening = true, local = false, lastProgressAtMs = 58_000, nowMs = 60_000))
    }

    @Test
    fun `a file on this device in full never stalls on Telegram`() {
        assertFalse(OpeningStall.stalled(opening = true, local = true, lastProgressAtMs = 0, nowMs = 60_000))
    }

    @Test
    fun `once the picture is up a wait is a rebuffer, not this`() {
        assertFalse(OpeningStall.stalled(opening = false, local = false, lastProgressAtMs = 0, nowMs = 60_000))
    }

    @Test
    fun `no line says so, a line with no bytes is a slow start`() {
        for (link in listOf(TelegramLink.Starting, TelegramLink.WaitingForNetwork, TelegramLink.ConnectingToProxy, TelegramLink.Connecting)) {
            assertEquals(link.name, OpeningStall.Reason.NoLine, OpeningStall.reason(link))
        }
        assertEquals(OpeningStall.Reason.Slow, OpeningStall.reason(TelegramLink.Updating))
        assertEquals(OpeningStall.Reason.Slow, OpeningStall.reason(TelegramLink.Ready))
    }
}
