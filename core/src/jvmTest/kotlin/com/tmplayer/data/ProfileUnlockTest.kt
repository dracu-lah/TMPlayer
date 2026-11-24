package com.tmplayer.data

import kotlinx.coroutines.flow.first
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

    /** A book whose open profile has a PIN, unlocked, as Accounts' own. */
    private fun lockedBookUnlocked(): AccountBook {
        val own = AccountBook(AccountStore(File(Files.createTempDirectory("away").toFile(), Accounts.FILE_NAME)))
        Accounts.resetForTest(own)
        own.update { it.replace(ProfilePin.set(it.active!!, "1234")!!) }
        ProfileUnlock.unlock(own.list.value.activeId)
        return own
    }

    @Test
    fun `still away past the grace, the profile counts as locked before any timer or return`() {
        val own = lockedBookUnlocked()
        ProfileUnlock.onBackground(now = 1_000L, lockAfterMs = 600_000L)
        assertFalse("30 s away is still within the grace inside the app", ProfileUnlock.lockedNow(now = 31_000L))
        assertTrue("but outside the app a PIN profile is hidden the moment it leaves the screen", ProfileUnlock.hiddenNow(now = 31_000L))
        assertTrue("61 s away is locked when read", ProfileUnlock.lockedNow(now = 62_000L))
        assertFalse(ProfileUnlock.isUnlocked(own.list.value.activeId, now = 62_000L))
        assertTrue("a clock that went backwards counts as long", ProfileUnlock.lockedNow(now = 10L))
        // Back on screen within the grace: unlocked inside, shown outside again.
        ProfileUnlock.onForeground(now = 31_000L)
        assertFalse(ProfileUnlock.lockedNow(now = 31_000L))
        assertFalse(ProfileUnlock.hiddenNow(now = 31_000L))
    }

    @Test
    fun `the delayed lock fires the listeners while the app is still away`() {
        lockedBookUnlocked()
        val fired = java.util.concurrent.CountDownLatch(1)
        ProfileUnlock.onLock { fired.countDown() }
        ProfileUnlock.onBackground(lockAfterMs = 50L)
        assertTrue("the lock came on its own", fired.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertNull(ProfileUnlock.unlockedId.value)
    }

    @Test
    fun `coming back in time cancels the delayed lock`() {
        val own = lockedBookUnlocked()
        ProfileUnlock.onBackground(lockAfterMs = 200L)
        ProfileUnlock.onForeground()
        Thread.sleep(400L)
        assertTrue(ProfileUnlock.isUnlocked(own.list.value.activeId))
    }

    @Test
    fun `a cold start with a locked profile is hidden from the first value`() = kotlinx.coroutines.runBlocking {
        val own = AccountBook(AccountStore(File(Files.createTempDirectory("cold").toFile(), Accounts.FILE_NAME)))
        own.update { it.replace(ProfilePin.set(it.active!!, "1234")!!) }
        Accounts.resetForTest(own)
        assertTrue(ProfileUnlock.hidden().first())
    }

    @Test
    fun `nothing is written outside the app while hidden or for a profile no longer open, a removal always is`() {
        val now = AccountStamp(3L, "pa")
        assertTrue(OutsideWrites.mayWrite(hidden = false, stamp = now, current = now))
        assertTrue(OutsideWrites.mayWrite(hidden = false, stamp = null, current = now))
        assertFalse(OutsideWrites.mayWrite(hidden = true, stamp = now, current = now))
        assertFalse("the profile it was made for is closed", OutsideWrites.mayWrite(hidden = false, stamp = AccountStamp(2L, null), current = now))
        assertTrue(OutsideWrites.mayWrite(hidden = true, stamp = AccountStamp(2L, null), current = now, removing = true))
        assertTrue(OutsideWrites.secureWindow(openHasPin = true))
        assertFalse(OutsideWrites.secureWindow(openHasPin = false))
    }

    @Test
    fun `the player's write after leaving the screen comes after the clear and is refused`() {
        val own = lockedBookUnlocked()
        assertTrue(OutsideWrites.mayWriteNow(Accounts.stamp))
        // The player's onStop: the app leaves the screen first (the clear), then it publishes.
        ProfileUnlock.onBackground(lockAfterMs = 600_000L)
        assertFalse(OutsideWrites.mayWriteNow(Accounts.stamp))
        ProfileUnlock.onForeground()
        assertTrue(OutsideWrites.mayWriteNow(Accounts.stamp))
        assertTrue(ProfileUnlock.isUnlocked(own.list.value.activeId))
    }

    @Test
    fun `a PIN profile's cast names only the app, another profile's the video`() {
        assertEquals("TMPlayer" to "", OutsideWrites.castTitles("A private title", "Episode 4", openHasPin = true, appName = "TMPlayer"))
        assertEquals("A private title" to "Episode 4", OutsideWrites.castTitles("A private title", "Episode 4", openHasPin = false, appName = "TMPlayer"))
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

    @Test
    fun `a PIN set on the open profile is told at once, for the launcher's shortcut (SEC2-11)`() = kotlinx.coroutines.runBlocking {
        val own = AccountBook(AccountStore(File(Files.createTempDirectory("haspin").toFile(), Accounts.FILE_NAME)))
        Accounts.resetForTest(own)
        val flow = ProfileUnlock.activeHasPin()
        assertFalse(flow.first())
        own.update { it.replace(ProfilePin.set(it.active!!, "1234")!!) }
        assertTrue("no lock change, no switch: the PIN alone", flow.first())
    }
}
