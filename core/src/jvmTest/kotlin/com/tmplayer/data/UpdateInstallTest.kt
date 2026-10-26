package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Install updates by itself" and the notice after an update (HX5). */
class UpdateInstallTest {

    private val idle = AutoInstall.Conditions(
        switchOn = true,
        silentAllowed = true,
        inFront = false,
        playing = false,
        casting = false,
        downloading = false,
        unmetered = true,
        powerOk = true,
        idleMs = AutoInstall.IDLE_MS,
    )

    private fun decide(
        conditions: AutoInstall.Conditions = idle,
        offered: String? = "1.29.0",
        installed: String = "1.28.1",
        skipped: String = "",
        tried: AutoInstall.Tried? = null,
        now: Long = 100 * AutoInstall.BACKOFF_MS,
    ) = AutoInstall.decide(offered, installed, skipped, tried, now, conditions)

    @Test
    fun `an idle device on Wi-Fi installs a newer release`() {
        assertEquals(AutoInstall.Decision.Go, decide())
    }

    @Test
    fun `the switch, Android's say and a newer release come first`() {
        assertEquals(AutoInstall.Decision.Off, decide(idle.copy(switchOn = false)))
        assertEquals(AutoInstall.Decision.NotSilent, decide(idle.copy(silentAllowed = false)))
        assertEquals(AutoInstall.Decision.NothingNew, decide(offered = null))
        assertEquals(AutoInstall.Decision.NothingNew, decide(offered = "1.28.1"))
        assertEquals(AutoInstall.Decision.NothingNew, decide(offered = "1.28.0"))
        assertEquals(AutoInstall.Decision.Skipped, decide(skipped = "1.29.0"))
    }

    @Test
    fun `a test version is installed only when it is what the feed offered`() {
        // The feed leaves test versions out unless "Get test versions" is on (Updates.check), so
        // whatever is offered is what the viewer chose to receive.
        assertEquals(AutoInstall.Decision.Go, decide(offered = "1.29.0-beta.1"))
        // A test version installed and the release after it out: that release goes in.
        assertEquals(AutoInstall.Decision.Go, decide(offered = "1.29.0", installed = "1.29.0-beta.2"))
    }

    @Test
    fun `nothing happens while the app is used`() {
        assertEquals(AutoInstall.Decision.InUse, decide(idle.copy(inFront = true)))
        assertEquals(AutoInstall.Decision.InUse, decide(idle.copy(playing = true)))
        assertEquals(AutoInstall.Decision.InUse, decide(idle.copy(casting = true)))
        assertEquals(AutoInstall.Decision.InUse, decide(idle.copy(downloading = true)))
        assertEquals(AutoInstall.Decision.Metered, decide(idle.copy(unmetered = false)))
        assertEquals(AutoInstall.Decision.Battery, decide(idle.copy(powerOk = false)))
        assertEquals(AutoInstall.Decision.NotIdle, decide(idle.copy(idleMs = AutoInstall.IDLE_MS - 1)))
    }

    @Test
    fun `one try per version, and a wait between tries`() {
        val now = 100 * AutoInstall.BACKOFF_MS
        // The same version is never tried twice, however long ago.
        assertEquals(AutoInstall.Decision.AlreadyTried, decide(tried = AutoInstall.Tried("1.29.0", 0L), now = now))
        // A newer one waits out the backoff after the last try.
        val recent = AutoInstall.Tried("1.29.0-beta.1", now - AutoInstall.BACKOFF_MS + 1)
        assertEquals(AutoInstall.Decision.Backoff, decide(tried = recent, now = now))
        val old = AutoInstall.Tried("1.29.0-beta.1", now - AutoInstall.BACKOFF_MS)
        assertEquals(AutoInstall.Decision.Go, decide(tried = old, now = now))
        // A clock set back before the last try does not hold it off for ever.
        val future = AutoInstall.Tried("1.29.0-beta.1", now + 1_000)
        assertEquals(AutoInstall.Decision.Go, decide(tried = future, now = now))
    }

    @Test
    fun `a run of tries stops on its own`() {
        // Every look after the first try of a version finds it tried: with nothing newer, nothing
        // more happens, look after look.
        var tried: AutoInstall.Tried? = null
        var installs = 0
        for (look in 0 until 50) {
            val now = look * AutoInstall.BACKOFF_MS
            if (decide(tried = tried, now = now) == AutoInstall.Decision.Go) {
                installs++
                tried = AutoInstall.Tried("1.29.0", now)
            }
        }
        assertEquals(1, installs)
    }

    @Test
    fun `the checks after the download`() {
        assertNull(AutoInstall.quiet(idle))
        assertEquals(AutoInstall.Decision.InUse, AutoInstall.quiet(idle.copy(inFront = true)))
        assertEquals(AutoInstall.Decision.Metered, AutoInstall.quiet(idle.copy(unmetered = false)))
    }

    @Test
    fun `a try is written and read back`() {
        val tried = AutoInstall.Tried("1.29.0-beta.1", 1_700_000_000_000L)
        assertEquals(tried, AutoInstall.Tried.decode(tried.encode()))
        assertNull(AutoInstall.Tried.decode(""))
        assertNull(AutoInstall.Tried.decode("1.29.0"))
        assertNull(AutoInstall.Tried.decode("|12"))
        assertNull(AutoInstall.Tried.decode("1.29.0|soon"))
    }

    @Test
    fun `space for the download and Android's copy of it`() {
        val mb = 1024L * 1024
        assertEquals(48 * mb * 2 + 32 * mb, AutoInstall.spaceNeeded(48 * mb))
        assertEquals(AutoInstall.UNKNOWN_SIZE_BYTES * 2 + 32 * mb, AutoInstall.spaceNeeded(0))
    }

    // ---- after the restart -------------------------------------------------------------------

    private val day = 24 * 60 * 60_000L

    @Test
    fun `the first launch of the new version says so once`() {
        val stored = UpdateRestart.Pending("1.29.0", 1_000L, session = true, foreground = true).encode()
        val launch = UpdateRestart.onLaunch(stored, installed = "1.29.0", now = 2_000L)
        assertEquals("1.29.0", launch.updatedTo)
        assertTrue(launch.clear)
        // Once cleared, the next launch has nothing to say.
        assertEquals(UpdateRestart.Launch(null, clear = false), UpdateRestart.onLaunch("", "1.29.0", 3_000L))
    }

    @Test
    fun `a newer version than the one installed counts too`() {
        val stored = UpdateRestart.Pending("1.29.0-beta.1", 0L, session = false, foreground = true).encode()
        assertEquals("1.29.0-beta.1", UpdateRestart.onLaunch(stored, installed = "1.29.0", now = 1L).updatedTo)
    }

    @Test
    fun `an install still waiting is kept, a stale one forgotten`() {
        val stored = UpdateRestart.Pending("1.29.0", 10 * day, session = false, foreground = true).encode()
        // The old version still runs: Android may still be asking (the system installer's screen).
        assertEquals(UpdateRestart.Launch(null, clear = false), UpdateRestart.onLaunch(stored, "1.28.1", 10 * day + 60_000))
        // Days later it never happened.
        assertEquals(UpdateRestart.Launch(null, clear = true), UpdateRestart.onLaunch(stored, "1.28.1", 10 * day + UpdateRestart.STALE_MS + 1))
        // A clock set back: forgotten rather than kept for ever.
        assertEquals(UpdateRestart.Launch(null, clear = true), UpdateRestart.onLaunch(stored, "1.28.1", day))
        // A record that does not read.
        assertEquals(UpdateRestart.Launch(null, clear = true), UpdateRestart.onLaunch("garbage", "1.28.1", day))
    }

    @Test
    fun `a pending install is written and read back`() {
        val pending = UpdateRestart.Pending("1.29.0", 123L, session = true, foreground = false)
        assertEquals(pending, UpdateRestart.Pending.decode(pending.encode()))
        assertNull(UpdateRestart.Pending.decode("1.29.0|123|1"))
        assertNull(UpdateRestart.Pending.decode("|123|1|1"))
    }

    @Test
    fun `the notification after the replace is for a session install of this version`() {
        val session = UpdateRestart.Pending("1.29.0", 0L, session = true, foreground = true).encode()
        val dialog = UpdateRestart.Pending("1.29.0", 0L, session = false, foreground = true).encode()
        assertTrue(UpdateRestart.notifyAfterReplace(session, "1.29.0"))
        // The system installer's screen has its own Open button on Android 6 to 11.
        assertFalse(UpdateRestart.notifyAfterReplace(dialog, "1.29.0"))
        // Updated some other way (a browser, another store) with a record for a later version.
        assertFalse(UpdateRestart.notifyAfterReplace(session, "1.28.2"))
        assertFalse(UpdateRestart.notifyAfterReplace("", "1.29.0"))
    }
}
