package com.tmplayer.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * WP-41: the fingerprint opt-in is off until chosen, and means something only while a PIN is set and
 * the lock asks when the app opens; removing either takes it away, so a PIN set again later does not
 * find a fingerprint already on. It is the device's, never in a backup.
 */
class AppLockBiometricTest {

    private val dir = Files.createTempDirectory("tm-bio").toFile()
    private val settings = SettingsStore(SettingsStore.openDataStore(dir.resolve(SettingsStore.FILE_NAME)))

    @Test
    fun `off by default, also with the lock on`() = runBlocking {
        assertFalse(settings.appLockBiometric.first())
        settings.setPin("1234")
        settings.setLockOnOpen(true)
        assertFalse(settings.appLockBiometric.first())
    }

    @Test
    fun `on only with a PIN and the lock asking when the app opens`() = runBlocking {
        // The switch written with nothing to open: it reads as off.
        settings.setAppLockBiometric(true)
        assertFalse(settings.appLockBiometric.first())
        settings.setPin("1234")
        assertFalse("no lock on open yet", settings.appLockBiometric.first())
        settings.setLockOnOpen(true)
        assertTrue(settings.appLockBiometric.first())
    }

    @Test
    fun `turning the lock on open off takes the fingerprint with it`() = runBlocking {
        settings.setPin("1234")
        settings.setLockOnOpen(true)
        settings.setAppLockBiometric(true)
        assertTrue(settings.appLockBiometric.first())
        settings.setLockOnOpen(false)
        assertFalse(settings.appLockBiometric.first())
        settings.setLockOnOpen(true)
        assertFalse("not back on by itself", settings.appLockBiometric.first())
    }

    @Test
    fun `removing the PIN and signing out clear it`() = runBlocking {
        settings.setPin("1234")
        settings.setLockOnOpen(true)
        settings.setAppLockBiometric(true)
        settings.removePin()
        settings.setPin("5678")
        settings.setLockOnOpen(true)
        assertFalse(settings.appLockBiometric.first())

        settings.setAppLockBiometric(true)
        settings.clearAppLock()
        settings.setPin("1234")
        settings.setLockOnOpen(true)
        assertFalse(settings.appLockBiometric.first())
        assertEquals(AppLockSettings(pinSet = true, onOpen = true), settings.appLock.first())
    }

    @Test
    fun `it is not in a backup`() {
        assertTrue(AccountKeys.DEVICE.contains("app_lock_biometric"))
        assertFalse(AccountKeys.isAccount("app_lock_biometric"))
    }
}
