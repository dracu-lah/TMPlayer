package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A download carries the profile it was queued under (HX88, review LOW 2), not the one open when it starts. */
class DownloadStampsTest {
    private var open = AccountStamp(1L, null)
    private var rows = 0L
    private val stamps = DownloadStamps(now = { open }, rowsNow = { rows })

    @Test
    fun `a download queued before a switch never starts in the next profile`() {
        stamps.queued(7)
        assertTrue(stamps.startable(7))
        open = AccountStamp(2L, "pnext1")
        assertFalse("its file id belongs to the profile that asked", stamps.startable(7))
        assertEquals(AccountStamp(1L, null), stamps.of(7))
    }

    @Test
    fun `a row put back on the queue in the open profile is that profile's`() {
        open = AccountStamp(5L, "pb")
        assertEquals(open, stamps.of(9))
        assertTrue(stamps.startable(9))
    }

    @Test
    fun `queued again in the next profile, it is the next profile's`() {
        stamps.queued(7)
        open = AccountStamp(2L, "pnext1")
        stamps.queued(7)
        assertTrue(stamps.startable(7))
        stamps.forget(7)
        stamps.clear()
        assertEquals(open, stamps.of(7))
    }

    @Test
    fun `a download queued before a sign out of the same profile never starts on its new database`() {
        stamps.queued(7)
        // A sign out or an ended session: the same stamp, a new database, the rows dropped.
        rows++
        assertFalse("its file id is the old database's", stamps.startable(7))
        assertEquals(0L, stamps.rowsOf(7))
    }
}
