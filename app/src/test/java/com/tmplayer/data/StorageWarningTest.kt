package com.tmplayer.data

import com.tmplayer.data.StorageWarning.LOW_FREE_BYTES
import com.tmplayer.data.StorageWarning.NEVER_DISMISSED
import com.tmplayer.data.StorageWarning.RENOTIFY_DROP_BYTES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageWarningTest {

    private val MB = 1024L * 1024
    private val GB = 1024 * MB
    private val total = 8 * GB

    @Test
    fun `the threshold is two gigabytes`() {
        assertEquals(2 * GB, LOW_FREE_BYTES)
    }

    @Test
    fun `exactly two gigabytes free is not low`() {
        assertFalse(StorageWarning.isLow(LOW_FREE_BYTES, total))
    }

    @Test
    fun `a byte under two gigabytes is low`() {
        assertTrue(StorageWarning.isLow(LOW_FREE_BYTES - 1, total))
    }

    @Test
    fun `a byte over two gigabytes is not low`() {
        assertFalse(StorageWarning.isLow(LOW_FREE_BYTES + 1, total))
    }

    @Test
    fun `plenty of room is not low`() {
        assertFalse(StorageWarning.isLow(6 * GB, total))
    }

    @Test
    fun `a failed disk read never warns`() {
        // DiskSpace.read returns DiskInfo(0, 0) when StatFs throws. Zero free out of zero total
        // is not a full disk, it is an unmeasured one, and it must not alarm anybody.
        assertFalse(StorageWarning.isLow(0, 0))
        assertFalse(StorageWarning.shouldShow(0, 0, NEVER_DISMISSED))
    }

    @Test
    fun `a genuinely full disk still warns`() {
        assertTrue(StorageWarning.isLow(0, total))
        assertTrue(StorageWarning.shouldShow(0, total, NEVER_DISMISSED))
    }

    @Test
    fun `an undismissed warning shows as soon as space is low`() {
        assertTrue(StorageWarning.shouldShow(1500 * MB, total, NEVER_DISMISSED))
    }

    @Test
    fun `dismissing hides the warning while things stay roughly as they were`() {
        val dismissedAt = 1900 * MB
        assertFalse(StorageWarning.shouldShow(dismissedAt, total, dismissedAt))
        assertFalse(StorageWarning.shouldShow(1700 * MB, total, dismissedAt))
    }

    @Test
    fun `a dismissed warning returns once space gets meaningfully worse`() {
        val dismissedAt = 1900 * MB
        assertTrue(StorageWarning.shouldShow(dismissedAt - RENOTIFY_DROP_BYTES, total, dismissedAt))
        assertTrue(StorageWarning.shouldShow(300 * MB, total, dismissedAt))
    }

    @Test
    fun `freeing space up hides the warning regardless of any dismissal`() {
        assertFalse(StorageWarning.shouldShow(5 * GB, total, 1900 * MB))
    }

    @Test
    fun `a dismissal is stale only once there is comfortable room again`() {
        assertFalse(StorageWarning.hasRecovered(1900 * MB, total))
        assertFalse(StorageWarning.hasRecovered(LOW_FREE_BYTES, total))
        assertTrue(StorageWarning.hasRecovered(LOW_FREE_BYTES + RENOTIFY_DROP_BYTES, total))
        assertTrue(StorageWarning.hasRecovered(6 * GB, total))
    }

    @Test
    fun `a failed disk read is not mistaken for a recovery`() {
        assertFalse(StorageWarning.hasRecovered(0, 0))
    }
}
