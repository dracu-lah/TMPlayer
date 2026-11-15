package com.tmplayer.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkPermissionTest {

    @Test
    fun `at target 35 nothing is asked on any Android version`() {
        for (sdk in listOf(23, 31, 34, 35, 36, 37, 38)) assertFalse("sdk $sdk", LocalNetworkPermission.required(sdk, 35))
    }

    @Test
    fun `at target 36 nothing is asked either, the permission is implicit there`() {
        for (sdk in listOf(35, 36, 37)) assertFalse(LocalNetworkPermission.required(sdk, 36))
    }

    @Test
    fun `at target 37 it is asked on Android 17 and newer only`() {
        assertTrue(LocalNetworkPermission.required(37, 37))
        assertTrue(LocalNetworkPermission.required(38, 37))
        assertFalse("an older phone has no such permission", LocalNetworkPermission.required(36, 37))
    }

    @Test
    fun `the permission is the one the docs name`() {
        assertEquals("android.permission.ACCESS_LOCAL_NETWORK", LocalNetworkPermission.NAME)
    }

    @Test
    fun `the picker counts Cast and DLNA TVs apart`() {
        var n = 0
        fun row(kind: CastKind) = CastDeviceInfo(id = "${kind.name}${n++}", name = "TV", model = "", kind = kind)
        assertEquals(0 to 0, deviceCounts(emptyList()))
        assertEquals(2 to 0, deviceCounts(listOf(row(CastKind.GoogleCast), row(CastKind.GoogleCast))))
        assertEquals(1 to 3, deviceCounts(listOf(row(CastKind.GoogleCast)) + List(3) { row(CastKind.Dlna) }))
    }
}
