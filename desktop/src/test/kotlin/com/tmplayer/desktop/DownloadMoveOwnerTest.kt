package com.tmplayer.desktop

import com.tmplayer.data.AccountOwner
import com.tmplayer.data.AccountStamp
import com.tmplayer.data.DownloadStamps
import com.tmplayer.data.OfflineDownloads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The move of a finished download and the profile it belongs to (HX88, review D1): a fetch whose
 * last block lands while a switch is under way moves its file as the profile that fetched it, or
 * not at all, never as the profile open by then.
 */
class DownloadMoveOwnerTest {

    private val a = AccountStamp(1L, null)
    private val b = AccountStamp(2L, "pbbbbb")

    @Test
    fun `the move runs as the fetch job's own owner and rows, whoever is open now`() {
        val job = AccountOwner(a) + OfflineDownloads.Rows(3)
        val move = moveContextOf(job, rowsNow = { 3 })!!
        assertEquals(a, move[AccountOwner]?.stamp)
        assertEquals(3L, move[OfflineDownloads.Rows]?.generation)
    }

    @Test
    fun `a fetch whose rows went with a switch starts no move`() {
        val job = AccountOwner(a) + OfflineDownloads.Rows(3)
        assertNull(moveContextOf(job, rowsNow = { 4 }))
        // Work with no owner or no rows never guesses one.
        assertNull(moveContextOf(OfflineDownloads.Rows(3), rowsNow = { 3 }))
        assertNull(moveContextOf(AccountOwner(a), rowsNow = { 3 }))
    }

    @Test
    fun `why the owner is not looked up again by file id after a switch`() {
        // What the runner did before: after stopForSwitch cleared the stamps, the lookup records the
        // profile open at that moment (B), which is how a move of A's file ran as B.
        var open = a
        val stamps = DownloadStamps(now = { open }, rowsNow = { 3 })
        stamps.queued(7)
        open = b
        stamps.clear()
        assertEquals(b, stamps.of(7))
    }
}
