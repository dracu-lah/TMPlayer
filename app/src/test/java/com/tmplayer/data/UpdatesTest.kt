package com.tmplayer.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The version comparison behind the Update badge.
 *
 * It decides whether the rail nags somebody who is already current, and whether the one release
 * that matters gets missed, so it is worth pinning down away from the network.
 */
class UpdatesTest {

    @Test
    fun `a later patch wins`() {
        assertTrue(Updates.isNewer("0.5.1", "0.5.0"))
    }

    @Test
    fun `the same version is not an update`() {
        assertFalse(Updates.isNewer("0.5.0", "0.5.0"))
    }

    @Test
    fun `an older release never offers itself`() {
        assertFalse(Updates.isNewer("0.4.9", "0.5.0"))
    }

    /** Text order would put 0.10.0 behind 0.9.0, which is where this starts to matter. */
    @Test
    fun `double figures beat single ones`() {
        assertTrue(Updates.isNewer("0.10.0", "0.9.0"))
        assertFalse(Updates.isNewer("0.9.0", "0.10.0"))
    }

    @Test
    fun `a shorter version is padded rather than misread`() {
        assertTrue(Updates.isNewer("1.0", "0.9.9"))
        assertFalse(Updates.isNewer("1.0", "1.0.0"))
        assertTrue(Updates.isNewer("1.0.1", "1.0"))
    }

    /** Tags in the wild carry suffixes; a "-beta" must not read as a different number. */
    @Test
    fun `suffixes are ignored rather than throwing`() {
        assertFalse(Updates.isNewer("0.5.0-beta", "0.5.0"))
        assertTrue(Updates.isNewer("0.6.0-rc1", "0.5.0"))
    }
}
