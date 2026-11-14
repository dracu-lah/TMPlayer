package com.tmplayer.player

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which presses of the fast-forward key start the hold speed (U3). */
class HoldSpeedKeyTest {

    private fun starts(
        key: Int = KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        repeat: Int = 1,
        long: Boolean = true,
        holding: Boolean = false,
        speed: Float = 2f,
        scans: Boolean = false,
    ) = HoldSpeedKey.startsHold(key, repeat, long, holding, speed, scans)

    @Test
    fun `the long press of the key starts the hold, once`() {
        assertTrue(starts())
        assertFalse(starts(holding = true))
    }

    @Test
    fun `a plain press and the repeats before the long press still jump`() {
        assertFalse(starts(repeat = 0, long = false))
        assertFalse(starts(repeat = 1, long = false))
        // A press that carries the flag but is the first event is not a hold yet.
        assertFalse(starts(repeat = 0, long = true))
    }

    @Test
    fun `off in Settings leaves a held key repeating its jump`() {
        assertFalse(starts(speed = 0f))
        assertFalse(starts(speed = -1f))
    }

    @Test
    fun `a remote that scans in growing steps when held keeps doing that`() {
        assertFalse(starts(scans = true))
    }

    @Test
    fun `only the fast-forward key holds, the arrows keep their jump and the other keys are left alone`() {
        assertFalse(starts(key = KeyEvent.KEYCODE_DPAD_RIGHT))
        assertFalse(starts(key = KeyEvent.KEYCODE_MEDIA_REWIND))
        assertFalse(starts(key = KeyEvent.KEYCODE_DPAD_CENTER))
    }
}
