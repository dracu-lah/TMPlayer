package com.tmplayer.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The open profile's unlock (HX88): memory only, only ever the open profile, locked again by a
 * switch, by the app lock and by more than a minute away, with no screen involved; and what shows
 * a profile outside the app (the widget here) hides a PIN locked profile.
 */
class ProfileUnlockTest {

    @After
    fun tearDown() {
        ProfileUnlock.resetForTest()
        Accounts.resetForTest()
    }

    @Test
    fun `a minute and more away locks the open profile, a short absence does not`() {
        ProfileUnlock.resetForTest()
        ProfileUnlock.unlock("p1")
        ProfileUnlock.onBackground(now = 1_000L)
        ProfileUnlock.onForeground(now = 11_000L)
        assertTrue("10 s away asks nothing", ProfileUnlock.isUnlocked("p1"))
        ProfileUnlock.onBackground(now = 20_000L)
        ProfileUnlock.onForeground(now = 81_001L)
        assertFalse("61 s away asks again", ProfileUnlock.isUnlocked("p1"))
        ProfileUnlock.unlock("p1")
        ProfileUnlock.onBackground(now = 90_000L)
        ProfileUnlock.onForeground(now = 5L)
        assertFalse("a clock that started again (a reboot) counts as long", ProfileUnlock.isUnlocked("p1"))
    }

    @Test
    fun `the app lock locking the app locks the profile, with no screen at all`() {
        ProfileUnlock.install()
        AppLockSession.unlock()
        ProfileUnlock.unlock("p1")
        AppLockSession.lock()
        assertNull(ProfileUnlock.unlockedId.value)
        // A lock of an app already locked changes nothing.
        ProfileUnlock.unlock("p1")
        AppLockSession.lock()
        assertTrue(ProfileUnlock.isUnlocked("p1"))
    }

    @Test
    fun `a switch locks the profile that was open, and a locked profile is hidden outside the app`() {
        ProfileUnlock.install()
        val own = AccountBook(AccountStore(File(Files.createTempDirectory("unlock").toFile(), Accounts.FILE_NAME)))
        Accounts.resetForTest(own)
        own.update { it.replace(ProfilePin.set(it.active!!, "1234")!!) }
        assertTrue("a PIN never given in this process", ProfileUnlock.hiddenNow())
        ProfileUnlock.unlock(own.list.value.activeId)
        assertFalse(ProfileUnlock.hiddenNow())
        // Another profile opens, as a switch publishes it: the last one is locked again.
        val other = AccountBook(AccountStore(File(Files.createTempDirectory("unlock2").toFile(), Accounts.FILE_NAME)))
        other.update { it.copy(profiles = it.profiles + Profile("pb", "pb"), activeId = "pb") }
        Accounts.resetForTest(other)
        assertNull(ProfileUnlock.unlockedId.value)
    }

    @Test
    fun `a profile with no PIN is never hidden`() {
        val own = AccountBook(AccountStore(File(Files.createTempDirectory("unlock3").toFile(), Accounts.FILE_NAME)))
        Accounts.resetForTest(own)
        assertFalse(ProfileUnlock.hiddenNow())
        assertFalse(ProfileUnlock.needsUnlock(own.list.value))
    }

    @Test
    fun `the widget shows the locked tile for a PIN locked profile`() {
        val record = ResumeRecord(
            chatId = 1L, messageId = 2L, fileId = 3, title = "Episode", chatTitle = "Chat", sizeBytes = 1_000L,
            durationSec = 600, positionMs = 60_000L, durationMs = 600_000L, updatedAt = 1L,
        )
        val locked = ContinueWidget.decide(ContinueWidget.Account.SignedIn, ContinueWidget.lockedBy(AppLockSettings(), profileLocked = true), listOf(record))
        assertEquals(ContinueWidget.Tile.Locked, locked)
        val open = ContinueWidget.decide(ContinueWidget.Account.SignedIn, ContinueWidget.lockedBy(AppLockSettings(), profileLocked = false), listOf(record))
        assertNotEquals(ContinueWidget.Tile.Locked, open)
    }
}
