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

    @Test
    fun `Settings unlocked stops counting once the app leaves the screen, whatever the lock's options`() {
        val pin = AppLockSettings(pinSet = true, onOpen = false)
        SettingsUnlock.unlock()
        assertEquals(SignOutGate.Open, SignOutGate.now(pin, pinGiven = false))
        var heard = 0
        val off = SettingsUnlock.onAppLeft { heard++ }
        // "Ask when the app opens" off: AppLockSession never unlocked, so its lock said nothing.
        AppLockSession.lock()
        SettingsUnlock.appLeft()
        assertEquals(SignOutGate.Pin, SignOutGate.now(pin, pinGiven = false))
        assertEquals(1, heard)
        off()
        SettingsUnlock.appLeft()
        assertEquals("a listener taken off hears nothing more", 1, heard)
    }

    @Test
    fun `leaving Settings locks it for every route`() {
        SettingsUnlock.unlock()
        SettingsUnlock.lock()
        assertEquals(SignOutGate.Pin, SignOutGate.now(AppLockSettings(pinSet = true), pinGiven = false))
        assertEquals(SignOutGate.Wait, SignOutGate.now(null, pinGiven = false))
    }
}
