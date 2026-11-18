package com.tmplayer.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LowEndDeviceTest {

    private val gib = 1024L * 1024 * 1024

    @Test
    fun aNormalDeviceKeepsNextLibsThreads() {
        for (cores in listOf(1, 2, 4, 8, 12)) assertNull(LowEndDevice.softwareVideoThreads(cores, lowEnd = false))
    }

    @Test
    fun aLowEndDeviceLeavesACoreFree() {
        // The Redmi 6A of the ANR: 4 cores, 2 of them for the picture.
        assertEquals(2, LowEndDevice.softwareVideoThreads(4, lowEnd = true))
        assertEquals(4, LowEndDevice.softwareVideoThreads(8, lowEnd = true))
        assertEquals(2, LowEndDevice.softwareVideoThreads(3, lowEnd = true))
        assertEquals(1, LowEndDevice.softwareVideoThreads(2, lowEnd = true))
        assertEquals(1, LowEndDevice.softwareVideoThreads(1, lowEnd = true))
        for (cores in 2..64) {
            val threads = LowEndDevice.softwareVideoThreads(cores, lowEnd = true)!!
            assertTrue(threads in 1 until cores, "$cores cores, $threads threads")
        }
        // A core count nobody could read is left to NextLib.
        assertNull(LowEndDevice.softwareVideoThreads(0, lowEnd = true))
        assertNull(LowEndDevice.softwareVideoThreads(-3, lowEnd = true))
    }

    @Test
    fun lowEndIsMemoryNotAge() {
        // A 2 GB phone reports a little under 2 GiB.
        assertTrue(LowEndDevice.isLowEnd(lowRamDevice = false, totalMemoryBytes = 1_900_000_000L, tv = false))
        assertFalse(LowEndDevice.isLowEnd(lowRamDevice = false, totalMemoryBytes = 3 * gib, tv = false))
        // A 1.5 GB stick is low end, a 2 GB TV is not.
        assertTrue(LowEndDevice.isLowEnd(lowRamDevice = false, totalMemoryBytes = 1_400_000_000L, tv = true))
        assertFalse(LowEndDevice.isLowEnd(lowRamDevice = false, totalMemoryBytes = 1_900_000_000L, tv = true))
        assertTrue(LowEndDevice.isLowEnd(lowRamDevice = true, totalMemoryBytes = 8 * gib, tv = false))
        // Unknown or nonsense memory is not low end by itself.
        assertFalse(LowEndDevice.isLowEnd(lowRamDevice = false, totalMemoryBytes = null, tv = false))
        assertFalse(LowEndDevice.isLowEnd(lowRamDevice = false, totalMemoryBytes = 0, tv = false))
        assertFalse(LowEndDevice.isLowEnd(lowRamDevice = false, totalMemoryBytes = -5, tv = true))
    }
}
