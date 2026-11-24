package com.tmplayer.ui.settings

import androidx.compose.runtime.saveable.SaverScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The sign out's state between the press and the end, after the security review of 2026-11-24. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SignOutStateTest {

    private fun saved(state: SignOutState): String = with(SignOutState.Saver) { SaverScope { true }.save(state) }!!

    @Test
    fun `a question survives a rotation but not a process started again (SEC-05)`() {
        val asking = SignOutState().also { assertTrue(it.request()) }
        assertTrue("the same process: a rotation keeps it", SignOutState.Saver.restore(saved(asking))!!.asking)
        assertFalse("a process that died asks nothing before the lock is read", SignOutState.Saver.restore("true")!!.asking)
        assertFalse(SignOutState.Saver.restore(saved(SignOutState()))!!.asking)
    }

    @Test
    fun `leaving the app drops the question, the PIN given for it and a failed backup's Sign out anyway (SEC-03)`() {
        val state = SignOutState()
        state.request()
        state.pinOk = true
        state.appLocked()
        assertFalse(state.pinOk)
        assertEquals(SignOutPrompt.None, state.promptNow())
        state.show(SignOutPrompt.BackupFailed)
        state.appLocked()
        assertEquals(SignOutPrompt.None, state.promptNow())
        // A backup being sent is not cut.
        state.show(SignOutPrompt.Saving)
        state.appLocked()
        assertEquals(SignOutPrompt.Saving, state.promptNow())
    }

    @Test
    fun `a sign out with no profiles that fails ends Signing out and says so with Try again (SEC-06)`() = runBlocking {
        val state = SignOutState()
        state.signingOut = true
        var loggedOut = false
        val done = state.signOutAlone(forget = { throw java.io.IOException("disk gone") }, logOut = { loggedOut = true })
        assertFalse(done)
        assertFalse("Signing out is not left up for good", state.signingOut)
        assertEquals(SignOutPrompt.Failed, state.promptNow())
        assertFalse(loggedOut)
        assertEquals(com.tmplayer.ui.settings.SignOutPhase.Running, state.phase)
        // Try again goes through.
        state.reset()
        state.signingOut = true
        assertTrue(state.signOutAlone(forget = {}, logOut = { loggedOut = true }))
        assertTrue(loggedOut)
        assertFalse(state.signingOut)
        assertEquals(SignOutPrompt.None, state.promptNow())
    }
}
