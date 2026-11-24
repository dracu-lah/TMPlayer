package com.tmplayer.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The arithmetic of where a toast stands, and the end states of its icon's movements. */
class ToastLayoutTest {

    @Test
    fun `a phone toast fills the width less 16 dp each side up to 560 dp`() {
        assertEquals(379.dp, ToastLayout.phoneWidth(411.dp))
        assertEquals(ToastLayout.PhoneMax, ToastLayout.phoneWidth(1000.dp))
        assertEquals(560.dp, ToastLayout.phoneWidth(592.dp))
        assertEquals(0.dp, ToastLayout.phoneWidth(10.dp))
    }

    @Test
    fun `the toast stands a gap over the highest of the bar, the keyboard and the safe margin`() {
        assertEquals(100 + 12, ToastLayout.clearancePx(floor = 100, inset = 40, safe = 0, gap = 12))
        assertEquals(300 + 12, ToastLayout.clearancePx(floor = 100, inset = 300, safe = 0, gap = 12))
        assertEquals(54 + 12, ToastLayout.clearancePx(floor = 0, inset = 0, safe = 54, gap = 12))
        assertEquals(12, ToastLayout.clearancePx(0, 0, 0, 12))
    }

    @Test
    fun `the floor is the highest bar reported, and a bar that leaves takes itself out`() {
        val nav = Any()
        val banner = Any()
        ToastFloor.clear(nav)
        ToastFloor.clear(banner)
        assertEquals(0, ToastFloor.px)
        ToastFloor.report(nav, 160)
        assertEquals(160, ToastFloor.px)
        ToastFloor.report(banner, 240)
        assertEquals(240, ToastFloor.px)
        ToastFloor.clear(banner)
        assertEquals(160, ToastFloor.px)
        ToastFloor.report(nav, 200)
        assertEquals(200, ToastFloor.px)
        ToastFloor.clear(nav)
        assertEquals(0, ToastFloor.px)
    }

    @Test
    fun `each icon starts apart from where it ends and settles in its final state`() {
        assertEquals(0f, shake(0f), 0.001f)
        assertEquals(0f, shake(1f), 0.001f)
        assertEquals(1f, pulse(0f), 0.001f)
        assertEquals(1f, pulse(1f), 0.001f)
        assertTrue("the pulse grows in between", pulse(0.5f) > 1.1f)
        assertTrue("the shake moves in between", (1..9).any { kotlin.math.abs(shake(it / 10f)) > 0.2f })
    }

    @Test
    fun `the tick takes about 300 ms and nothing takes longer than half a second`() {
        assertEquals(300, glyphMs(ToastKind.Success))
        assertTrue(ToastKind.entries.all { glyphMs(it) <= 500 })
    }

    @Test
    fun `a television's words are at least 16 sp`() {
        assertTrue(ToastLayout.TvText.value >= 16f)
    }
}
