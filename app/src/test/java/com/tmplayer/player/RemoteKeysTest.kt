package com.tmplayer.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteKeysTest {

    @Test
    fun `a first press always jumps the plain step`() {
        assertEquals(10_000L, RemoteKeys.jumpStepMs(0, 10_000L, nowMs = 1_000, lastJumpMs = 999))
    }

    @Test
    fun `a held arrow lands a few jumps a second, not one per repeat`() {
        var last = 0L
        var jumps = 0
        // Repeats every 33 ms for two seconds.
        for (repeat in 1..60) {
            val now = repeat * 33L
            if (RemoteKeys.jumpStepMs(repeat, 10_000L, now, last) > 0) {
                jumps++
                last = now
            }
        }
        assertTrue("jumps=$jumps", jumps in 1..15)
    }

    @Test
    fun `a repeat inside the pacing window is dropped even on a slice that would move`() {
        assertEquals(0L, RemoteKeys.jumpStepMs(4, 10_000L, nowMs = 1_100, lastJumpMs = 1_000))
        assertEquals(10_000L, RemoteKeys.jumpStepMs(4, 10_000L, nowMs = 1_200, lastJumpMs = 1_000))
    }

    @Test
    fun `a long hold grows the step`() {
        assertEquals(30_000L, RemoteKeys.jumpStepMs(40, 10_000L, 10_000, 0))
        assertEquals(60_000L, RemoteKeys.jumpStepMs(100, 10_000L, 10_000, 0))
    }

    @Test
    fun `next previous skip and channel keys step the episode`() {
        for (k in listOf(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD, KeyEvent.KEYCODE_CHANNEL_UP)) {
            assertEquals(true, RemoteKeys.episodeStep(k))
        }
        for (k in listOf(KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD, KeyEvent.KEYCODE_CHANNEL_DOWN)) {
            assertEquals(false, RemoteKeys.episodeStep(k))
        }
        assertNull(RemoteKeys.episodeStep(KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    @Test
    fun `stop is the leaving key`() {
        assertTrue(RemoteKeys.isStop(KeyEvent.KEYCODE_MEDIA_STOP))
        assertFalse(RemoteKeys.isStop(KeyEvent.KEYCODE_MEDIA_PAUSE))
    }

    @Test
    fun `a short Info press opens the panel and a long one does not`() {
        assertTrue(RemoteKeys.isInfoTap(KeyEvent.KEYCODE_INFO, 0, 200, canceled = false, longPressMs = 500))
        assertFalse(RemoteKeys.isInfoTap(KeyEvent.KEYCODE_INFO, 0, 700, canceled = false, longPressMs = 500))
        assertFalse(RemoteKeys.isInfoTap(KeyEvent.KEYCODE_INFO, 0, 200, canceled = true, longPressMs = 500))
        assertFalse(RemoteKeys.isInfoTap(KeyEvent.KEYCODE_DPAD_CENTER, 0, 200, canceled = false, longPressMs = 500))
    }

    @Test
    fun `the next-up card does not take OK or Left until it is walked onto`() {
        assertEquals(RemoteKeys.NextUpKey.Ignore, RemoteKeys.nextUpKey(KeyEvent.KEYCODE_DPAD_CENTER, engaged = false))
        assertEquals(RemoteKeys.NextUpKey.Ignore, RemoteKeys.nextUpKey(KeyEvent.KEYCODE_ENTER, engaged = false))
        assertEquals(RemoteKeys.NextUpKey.Ignore, RemoteKeys.nextUpKey(KeyEvent.KEYCODE_DPAD_LEFT, engaged = false))
        assertEquals(RemoteKeys.NextUpKey.Engage, RemoteKeys.nextUpKey(KeyEvent.KEYCODE_DPAD_RIGHT, engaged = false))
        assertEquals(RemoteKeys.NextUpKey.Engage, RemoteKeys.nextUpKey(KeyEvent.KEYCODE_DPAD_DOWN, engaged = false))
    }

    @Test
    fun `on the card OK plays and Left or Up walks off`() {
        assertEquals(RemoteKeys.NextUpKey.Pass, RemoteKeys.nextUpKey(KeyEvent.KEYCODE_DPAD_CENTER, engaged = true))
        assertEquals(RemoteKeys.NextUpKey.Pass, RemoteKeys.nextUpKey(KeyEvent.KEYCODE_DPAD_RIGHT, engaged = true))
        assertEquals(RemoteKeys.NextUpKey.Release, RemoteKeys.nextUpKey(KeyEvent.KEYCODE_DPAD_LEFT, engaged = true))
        assertEquals(RemoteKeys.NextUpKey.Release, RemoteKeys.nextUpKey(KeyEvent.KEYCODE_DPAD_UP, engaged = true))
        assertEquals(RemoteKeys.NextUpKey.Release, RemoteKeys.nextUpKey(KeyEvent.KEYCODE_DPAD_DOWN, engaged = true))
    }
}
