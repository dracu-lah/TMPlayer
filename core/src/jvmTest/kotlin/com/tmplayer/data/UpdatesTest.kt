package com.tmplayer.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `the universal apk wins even when a split asset appears first`() {
        val assets = JSONArray()
            .put(asset("TMPlayer-0.5.2-armeabi-v7a.apk"))
            .put(asset("TMPlayer-0.5.2-universal.apk"))

        val selected = Updates.selectApkAsset(assets, arrayOf("armeabi-v7a"))

        assertEquals("TMPlayer-0.5.2-universal.apk", selected?.optString("name"))
    }

    @Test
    fun `an older split release still picks the tv architecture`() {
        val assets = JSONArray()
            .put(asset("TMPlayer-0.5.1-arm64-v8a.apk"))
            .put(asset("TMPlayer-0.5.1-armeabi-v7a.apk"))

        val selected = Updates.selectApkAsset(assets, arrayOf("armeabi-v7a", "arm64-v8a"))

        assertEquals("TMPlayer-0.5.1-armeabi-v7a.apk", selected?.optString("name"))
    }

    @Test
    fun `an incompatible split release is ignored`() {
        val assets = JSONArray().put(asset("TMPlayer-0.5.1-x86_64.apk"))

        assertNull(Updates.selectApkAsset(assets, arrayOf("armeabi-v7a")))
    }

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

    private fun asset(name: String): JSONObject = JSONObject().put("name", name)
}
