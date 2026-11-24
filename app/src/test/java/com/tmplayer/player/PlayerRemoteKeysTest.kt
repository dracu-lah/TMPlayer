package com.tmplayer.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlayerRemoteKeysTest {

    @Test
    fun `a first press always jumps the plain step`() {
        assertEquals(10_000L, PlayerRemoteKeys.jumpStepMs(0, 10_000L, nowMs = 1_000, lastJumpMs = 999))
    }

    @Test
    fun `a held arrow lands a few jumps a second, not one per repeat`() {
        var last = 0L
        var jumps = 0
        // Repeats every 33 ms for two seconds.
        for (repeat in 1..60) {
            val now = repeat * 33L
            if (PlayerRemoteKeys.jumpStepMs(repeat, 10_000L, now, last) > 0) {
                jumps++
                last = now
            }
        }
        assertTrue("jumps=$jumps", jumps in 1..15)
    }

    @Test
    fun `a repeat inside the pacing window is dropped even on a slice that would move`() {
        assertEquals(0L, PlayerRemoteKeys.jumpStepMs(4, 10_000L, nowMs = 1_100, lastJumpMs = 1_000))
        assertEquals(10_000L, PlayerRemoteKeys.jumpStepMs(4, 10_000L, nowMs = 1_200, lastJumpMs = 1_000))
    }

    @Test
    fun `a held arrow's repeat that waited behind a slow seek over the picture does not seek (PB-04)`() {
        // 300 ms between the repeat and its handling: the key may be up already.
        assertEquals(0L, PlayerRemoteKeys.pictureStepMs(8, 10_000L, eventMs = 1_000, nowMs = 1_300, lastJumpMs = 0, durationMs = 3_600_000))
        // 100 ms late is still a live hold here (the bar's 80 ms rule would drop it).
        assertEquals(10_000L, PlayerRemoteKeys.pictureStepMs(8, 10_000L, eventMs = 1_000, nowMs = 1_100, lastJumpMs = 0, durationMs = 3_600_000))
        // The first press is never dropped, however late.
        assertEquals(10_000L, PlayerRemoteKeys.pictureStepMs(0, 10_000L, eventMs = 1_000, nowMs = 5_000, lastJumpMs = 4_990, durationMs = 3_600_000))
        // And the remote's own call sites pass the key's time.
        val source = File(repo(), "app/src/main/java/com/tmplayer/player/PlayerActivity.kt").readText()
        assertEquals(3, Regex("repeatCount = event.repeatCount, eventMs = event.eventTime\\)").findAll(source).count())
        val jump = source.substringAfter("private fun jumpFromRemote(").substringBefore("\n    }")
        assertTrue("PlayerRemoteKeys.pictureStepMs(repeatCount, base, eventMs, now, lastRemoteJumpAt, duration)" in jump)
    }

    @Test
    fun `a long hold grows the step`() {
        assertEquals(30_000L, PlayerRemoteKeys.jumpStepMs(40, 10_000L, 10_000, 0))
        assertEquals(60_000L, PlayerRemoteKeys.jumpStepMs(100, 10_000L, 10_000, 0))
    }

    @Test
    fun `a TV remote jumps 10 s both ways while Media3 keeps 5 s back and the notification follows the double tap amount`() {
        assertEquals(10_000L, PlayerRemoteKeys.remoteBaseMs(forward = false, tv = true))
        assertEquals(10_000L, PlayerRemoteKeys.remoteBaseMs(forward = true, tv = true))
        // A phone's keyboard or paired remote is unchanged.
        assertEquals(Skip.BACK_MS, PlayerRemoteKeys.remoteBaseMs(forward = false, tv = false))
        assertEquals(Skip.FORWARD_MS, PlayerRemoteKeys.remoteBaseMs(forward = true, tv = false))
        assertEquals("Skip.BACK_MS is never changed globally", 5_000L, Skip.BACK_MS)
        val source = File(repo(), "app/src/main/java/com/tmplayer/player/PlayerActivity.kt").readText()
        assertTrue(".setSeekBackIncrementMs(Skip.BACK_MS)" in source)
        // WP-36: the notification and lock screen seek by the double tap amount both ways, not by Skip.
        assertTrue("BackgroundPlayback.notificationButtons(touchPrefs.doubleTapMs, touchPrefs.doubleTapMs)" in source)
        val jump = source.substringAfter("private fun jumpFromRemote(").substringBefore("\n    }")
        assertTrue("PlayerRemoteKeys.remoteBaseMs(forward, FormFactor.isTv(this))" in jump)
    }

    @Test
    fun `a TV Left tap moves 10 s back from the bare picture`() {
        val tv = PlayerRemoteKeys.remoteBaseMs(forward = false, tv = true)
        assertEquals(10_000L, PlayerRemoteKeys.jumpStepMs(0, tv, nowMs = 5_000, lastJumpMs = 4_990, durationMs = 3_600_000))
    }

    @Test
    fun `the held steps follow the length, a short video stays at 10 s and a long one grows`() {
        val twentyMinutes = 20 * 60_000L
        val twoHours = 120 * 60_000L
        assertEquals(10_000L, PlayerRemoteKeys.jumpStepMs(100, 10_000L, 10_000, 0, twentyMinutes))
        assertEquals(36_000L, PlayerRemoteKeys.jumpStepMs(100, 10_000L, 10_000, 0, twoHours))
        // Still gated: a repeat 100 ms after the last jump waits, whatever the step.
        assertEquals(0L, PlayerRemoteKeys.jumpStepMs(100, 10_000L, 10_100, 10_000, twoHours))
        // Unknown length keeps today's steps.
        assertEquals(60_000L, PlayerRemoteKeys.jumpStepMs(100, 10_000L, 10_000, 0, -1L))
    }

    @Test
    fun `a held arrow over the bare picture lands at most one seek in 150 ms`() {
        // A fake clock: a repeat every 33 ms (the fastest remote) for 10 s.
        var last = 0L
        val landed = mutableListOf<Long>()
        for (repeat in 0..300) {
            val now = 1_000L + repeat * 33L
            if (PlayerRemoteKeys.jumpStepMs(repeat, 10_000L, now, last, 120 * 60_000L) > 0) {
                landed += now
                last = now
            }
        }
        assertTrue(landed.zipWithNext().all { (a, b) -> b - a >= PlayerRemoteKeys.MIN_REPEAT_JUMP_MS })
        assertTrue("seeks=${landed.size}", landed.size <= 10_000 / 150 + 1)
    }

    @Test
    fun `the bar drops a stale repeat and gates the rest`() {
        // Fresh: moves.
        assertEquals(10_000L, PlayerRemoteKeys.barStepMs(4, 10_000L, eventMs = 1_000, nowMs = 1_010, lastStepMs = 0, durationMs = 0))
        // Made 200 ms ago and only handled now: dropped, so the bar stops where the key was let go.
        assertEquals(0L, PlayerRemoteKeys.barStepMs(4, 10_000L, eventMs = 1_000, nowMs = 1_200, lastStepMs = 0, durationMs = 0))
        // The first press is never stale.
        assertEquals(10_000L, PlayerRemoteKeys.barStepMs(0, 10_000L, eventMs = 0, nowMs = 5_000, lastStepMs = 4_990, durationMs = 0))
        // Inside the gate: waits.
        assertEquals(0L, PlayerRemoteKeys.barStepMs(4, 10_000L, eventMs = 1_000, nowMs = 1_000, lastStepMs = 900, durationMs = 0))
    }

    @Test
    fun `the pace is how many plain steps a jump was`() {
        assertEquals(1f, PlayerRemoteKeys.pace(10_000L, 10_000L))
        assertEquals(3f, PlayerRemoteKeys.pace(30_000L, 10_000L))
        assertEquals(6f, PlayerRemoteKeys.pace(60_000L, 10_000L))
        // A step the length capped shows its real figure, not the floor.
        assertEquals(3.6f, PlayerRemoteKeys.pace(36_000L, 10_000L))
        assertEquals(1f, PlayerRemoteKeys.pace(60_000L, 0L))
    }

    @Test
    fun `Page Down is forward and Page Up back, and nothing else is a page key`() {
        assertEquals(true, PlayerRemoteKeys.pageStep(KeyEvent.KEYCODE_PAGE_DOWN, tv = true))
        assertEquals(false, PlayerRemoteKeys.pageStep(KeyEvent.KEYCODE_PAGE_UP, tv = true))
        assertNull(PlayerRemoteKeys.pageStep(KeyEvent.KEYCODE_CHANNEL_UP, tv = true))
        // A phone's or tablet's keyboard: no chapter step, a TV's meaning only. Its Page keys are
        // the episodes of PlayerKeyMap, as on the desktop and the web (PB-03).
        assertNull(PlayerRemoteKeys.pageStep(KeyEvent.KEYCODE_PAGE_DOWN, tv = false))
        assertNull(PlayerRemoteKeys.pageStep(KeyEvent.KEYCODE_PAGE_UP, tv = false))
        assertEquals(60_000L, PlayerRemoteKeys.PAGE_JUMP_MS)
        val source = File(repo(), "app/src/main/java/com/tmplayer/player/PlayerActivity.kt").readText()
        val page = source.substringAfter("private fun pageFromRemote(forward: Boolean) {").substringBefore("\n    }")
        assertTrue("a file with chapters steps the chapter", "if (chapters.isNotEmpty()) stepChapter(forward)" in page)
        assertTrue("else a minute", "jumpFromRemote(forward, fixedStepMs = PlayerRemoteKeys.PAGE_JUMP_MS)" in page)
    }

    @Test
    fun `the jump's figure shows the pace once the hold grows`() {
        assertEquals(com.tmplayer.i18n.L.playerSeekSeconds(10), seekLabel(10, 1f, back = false))
        val held = seekLabel(70, 3f, back = false)
        assertTrue(held, held.startsWith(com.tmplayer.i18n.L.playerSeekSeconds(70)))
        assertTrue(held, held.endsWith("▶▶ " + TouchPrefs.holdLabel(3f)))
        assertTrue(seekLabel(70, 6f, back = true).contains("◀◀ " + TouchPrefs.holdLabel(6f)))
        val capped = seekLabel(106, PlayerRemoteKeys.pace(36_000L, 10_000L), back = false)
        assertTrue(capped, capped.endsWith("▶▶ " + TouchPrefs.holdLabel(3.6f)))
        assertTrue(capped, "3.6" in capped)
    }

    /** Gradle runs unit tests from the module directory; an IDE may start from the repository. */
    private fun repo(): File = if (File("app/src/main").exists()) File(".") else File("..")

    @Test
    fun `next previous skip and channel keys step the episode`() {
        for (k in listOf(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD, KeyEvent.KEYCODE_CHANNEL_UP)) {
            assertEquals(true, PlayerRemoteKeys.episodeStep(k))
        }
        for (k in listOf(KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD, KeyEvent.KEYCODE_CHANNEL_DOWN)) {
            assertEquals(false, PlayerRemoteKeys.episodeStep(k))
        }
        assertNull(PlayerRemoteKeys.episodeStep(KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    @Test
    fun `stop is the leaving key`() {
        assertTrue(PlayerRemoteKeys.isStop(KeyEvent.KEYCODE_MEDIA_STOP))
        assertFalse(PlayerRemoteKeys.isStop(KeyEvent.KEYCODE_MEDIA_PAUSE))
    }

    @Test
    fun `a short Info press opens the panel and a long one does not`() {
        assertTrue(PlayerRemoteKeys.isInfoTap(KeyEvent.KEYCODE_INFO, 0, 200, canceled = false, longPressMs = 500))
        assertFalse(PlayerRemoteKeys.isInfoTap(KeyEvent.KEYCODE_INFO, 0, 700, canceled = false, longPressMs = 500))
        assertFalse(PlayerRemoteKeys.isInfoTap(KeyEvent.KEYCODE_INFO, 0, 200, canceled = true, longPressMs = 500))
        assertFalse(PlayerRemoteKeys.isInfoTap(KeyEvent.KEYCODE_DPAD_CENTER, 0, 200, canceled = false, longPressMs = 500))
    }

    @Test
    fun `the next-up card does not take OK or Left until it is walked onto`() {
        assertEquals(PlayerRemoteKeys.NextUpKey.Ignore, PlayerRemoteKeys.nextUpKey(KeyEvent.KEYCODE_DPAD_CENTER, engaged = false))
        assertEquals(PlayerRemoteKeys.NextUpKey.Ignore, PlayerRemoteKeys.nextUpKey(KeyEvent.KEYCODE_ENTER, engaged = false))
        assertEquals(PlayerRemoteKeys.NextUpKey.Ignore, PlayerRemoteKeys.nextUpKey(KeyEvent.KEYCODE_DPAD_LEFT, engaged = false))
        assertEquals(PlayerRemoteKeys.NextUpKey.Engage, PlayerRemoteKeys.nextUpKey(KeyEvent.KEYCODE_DPAD_RIGHT, engaged = false))
        assertEquals(PlayerRemoteKeys.NextUpKey.Engage, PlayerRemoteKeys.nextUpKey(KeyEvent.KEYCODE_DPAD_DOWN, engaged = false))
    }

    @Test
    fun `on the card OK plays and Left or Up walks off`() {
        assertEquals(PlayerRemoteKeys.NextUpKey.Pass, PlayerRemoteKeys.nextUpKey(KeyEvent.KEYCODE_DPAD_CENTER, engaged = true))
        assertEquals(PlayerRemoteKeys.NextUpKey.Pass, PlayerRemoteKeys.nextUpKey(KeyEvent.KEYCODE_DPAD_RIGHT, engaged = true))
        assertEquals(PlayerRemoteKeys.NextUpKey.Release, PlayerRemoteKeys.nextUpKey(KeyEvent.KEYCODE_DPAD_LEFT, engaged = true))
        assertEquals(PlayerRemoteKeys.NextUpKey.Release, PlayerRemoteKeys.nextUpKey(KeyEvent.KEYCODE_DPAD_UP, engaged = true))
        assertEquals(PlayerRemoteKeys.NextUpKey.Release, PlayerRemoteKeys.nextUpKey(KeyEvent.KEYCODE_DPAD_DOWN, engaged = true))
    }

    @Test
    fun `the CC toggle turns the shown track off, else the last or default one on, else says there is none`() {
        assertEquals(PlayerRemoteKeys.CaptionsToggle.None, PlayerRemoteKeys.captionsToggle(0, null, null, null))
        assertEquals(PlayerRemoteKeys.CaptionsToggle.Off, PlayerRemoteKeys.captionsToggle(3, selected = 1, last = null, defaultIndex = null))
        assertEquals(PlayerRemoteKeys.CaptionsToggle.On(2), PlayerRemoteKeys.captionsToggle(3, null, last = 2, defaultIndex = 1))
        assertEquals(PlayerRemoteKeys.CaptionsToggle.On(1), PlayerRemoteKeys.captionsToggle(3, null, last = null, defaultIndex = 1))
        assertEquals(PlayerRemoteKeys.CaptionsToggle.On(0), PlayerRemoteKeys.captionsToggle(3, null, null, null))
        // A remembered track the new file lacks falls back to the first.
        assertEquals(PlayerRemoteKeys.CaptionsToggle.On(0), PlayerRemoteKeys.captionsToggle(1, null, last = 4, defaultIndex = null))
    }
}
