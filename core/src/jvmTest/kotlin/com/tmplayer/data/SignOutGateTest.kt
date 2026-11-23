package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SignOutGateTest {
    @Test
    fun `a lock not yet read waits, it never opens`() {
        assertEquals(SignOutGate.Wait, SignOutGate.of(null, settingsUnlocked = false, pinGiven = false))
    }

    @Test
    fun `null then a PIN set asks the PIN, and null then no PIN opens`() {
        assertEquals(SignOutGate.Wait, SignOutGate.of(null, false, false))
        assertEquals(SignOutGate.Pin, SignOutGate.of(AppLockSettings(pinSet = true), false, false))
        assertEquals(SignOutGate.Open, SignOutGate.of(AppLockSettings(pinSet = false), false, false))
    }

    @Test
    fun `Settings open or the PIN given opens whatever the lock says`() {
        assertEquals(SignOutGate.Open, SignOutGate.of(null, settingsUnlocked = true, pinGiven = false))
        assertEquals(SignOutGate.Open, SignOutGate.of(AppLockSettings(pinSet = true), settingsUnlocked = true, pinGiven = false))
        assertEquals(SignOutGate.Open, SignOutGate.of(AppLockSettings(pinSet = true), settingsUnlocked = false, pinGiven = true))
    }
}
