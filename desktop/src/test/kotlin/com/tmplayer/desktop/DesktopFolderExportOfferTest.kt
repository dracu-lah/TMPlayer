package com.tmplayer.desktop

import com.tmplayer.data.AccountStamp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Save to folder asks first, and the question names one profile's videos (HX88, review round 2): an
 * offer made under profile A is not confirmable under profile B, so 'Remove' can never delete A's
 * originals under B's stamp.
 */
class DesktopFolderExportOfferTest {

    private val a = AccountStamp(1L, null)
    private val b = AccountStamp(2L, "pbbbbb")


    @Test
    fun `an offer is mine only for the profile it was made for`() {
        assertTrue(DesktopFolderExport.offerIsMine(a, now = a))
        assertFalse(DesktopFolderExport.offerIsMine(a, now = b))
        assertFalse(DesktopFolderExport.offerIsMine(b, now = a))
    }
}
