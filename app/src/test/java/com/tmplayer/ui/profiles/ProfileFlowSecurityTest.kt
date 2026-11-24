package com.tmplayer.ui.profiles

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.tmplayer.data.AppLockSession
import com.tmplayer.data.AppLockSettings
import com.tmplayer.data.ProfileUnlock
import com.tmplayer.data.SettingsUnlock
import com.tmplayer.data.SignOutResult
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.lock.ProfileFlow
import com.tmplayer.ui.lock.ProfilesGate
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The security review of 2026-11-24 (SEC-01, SEC-02, SEC-04, SEC-07, SEC-11): a PIN given counts
 * for what it was given for, once; nothing of the profiles' notice is drawn over the app lock's pad;
 * a sign out acts only on the profile its question named.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-port-night-xhdpi")
class ProfileFlowSecurityTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val drain = DrainMainLooperRule()

    private var kit: ProfileKit? = null

    @Before
    fun setUp() {
        ProfileUnlock.lock()
        AppLockSession.lock()
        SettingsUnlock.lock()
    }

    @After
    fun tearDown() {
        kit?.close()
        ProfileUnlock.lock()
        AppLockSession.lock()
        SettingsUnlock.lock()
    }

    private fun waitUntil(what: () -> Boolean) = rule.waitUntil(15_000, what)

    private fun ProfileKit.tile(flow: ProfileFlow, index: Int) =
        flow.model(book.list.value, false).tiles.first { it.id == ids[index] }

    /** Ben's PIN given at the picker, his switch failing: the notice with Try again is up. */
    private fun failedSwitchToBen(k: ProfileKit, flow: ProfileFlow) {
        k.pin(1, "4321")
        k.host.failReopen = true
        rule.runOnUiThread {
            flow.open(k.tile(flow, 1))
            assertTrue(flow.stage is ProfileFlow.Stage.Pin)
            flow.pinAccepted(k.ids[1], ProfileFlow.PinWhy.Open)
        }
        waitUntil { flow.notice?.retry != null && flow.status == null }
        k.host.failReopen = false
    }

    @Test
    fun `Try again on a failed switch asks the profile's PIN again, it never opens it unlocked`() {
        val k = ProfileKit("Asha", "Ben").also { kit = it }
        val flow = k.flow()
        failedSwitchToBen(k, flow)
        rule.runOnUiThread { flow.retryNotice() }
        rule.waitForIdle()
        assertEquals("Ben's PIN is asked again", ProfileFlow.Stage.Pin(k.ids[1], ProfileFlow.PinWhy.Open), flow.stage)
        assertEquals("nothing was switched", k.ids[0], k.book.list.value.activeId)
        assertFalse(ProfileUnlock.isUnlocked(k.ids[1]))
    }

    @Test
    fun `a PIN given before the app left the screen is spent, the switch it was for opens the profile locked`() {
        val k = ProfileKit("Asha", "Ben").also { kit = it }
        k.pin(1, "4321")
        k.platform.playing = true
        val flow = k.flow()
        rule.runOnUiThread {
            flow.pinAccepted(k.ids[1], ProfileFlow.PinWhy.Open)
            assertTrue("a video plays: asked first", flow.stage is ProfileFlow.Stage.ConfirmSwitch)
        }
        // Left on the table with "ask when the app opens" off: the question stays, the PIN does not.
        SettingsUnlock.appLeft()
        rule.runOnUiThread { flow.confirmSwitch() }
        waitUntil { k.book.list.value.activeId == k.ids[1] && flow.status == null }
        assertFalse("Ben opened behind his own PIN", ProfileUnlock.isUnlocked(k.ids[1]))
        assertTrue(flow.needsUnlock(k.book.list.value))
    }

    @Test
    fun `the app lock closing drops the notice and any panel past a PIN`() {
        val k = ProfileKit("Asha", "Ben").also { kit = it }
        val flow = k.flow()
        failedSwitchToBen(k, flow)
        AppLockSession.unlock()
        AppLockSession.lock()
        assertEquals(null, flow.notice)
        assertEquals(ProfileFlow.Stage.Picker, flow.stage)
    }

    @Test
    fun `no notice, Try again or Send logs is drawn over the app lock's pad, and it shows once unlocked`() {
        val k = ProfileKit("Asha", "Ben").also { kit = it }
        k.platform.lock.value = AppLockSettings(pinSet = true, onOpen = true)
        k.host.failReopen = true
        val flow = k.flow()
        rule.setContent {
            TmMaterialTheme(dark = true) {
                ProfilesGate(flow, signInStep = false, sendLogs = { Text(LOGS) }) { Text(HOME) }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText(L.lockEnterForApp).assertExists()
        // A switch fails while the app is locked (asked before it locked, finishing after).
        rule.runOnUiThread { flow.open(k.tile(flow, 1)) }
        waitUntil { flow.notice?.retry != null && flow.status == null }
        rule.waitForIdle()
        rule.onNodeWithText(L.lockEnterForApp).assertExists()
        rule.onNodeWithText(L.commonTryAgain).assertDoesNotExist()
        rule.onNodeWithText(LOGS).assertDoesNotExist()
        rule.onNodeWithText(L.profilesSwitchFailed(name = "Ben")).assertDoesNotExist()
        // The one who knows the app's PIN sees it.
        rule.runOnUiThread { AppLockSession.unlock() }
        rule.waitForIdle()
        rule.onNodeWithText(L.commonTryAgain).assertExists()
        rule.onNodeWithText(LOGS).assertExists()
    }

    @Test
    fun `a sign out answered for one profile signs nobody out once another is open, and says so`() {
        val k = ProfileKit("Asha", "Ben").also { kit = it }
        val flow = k.flow()
        var result: SignOutResult? = null
        // Asha's question was answered; Ben opened while the backup was sent.
        kotlinx.coroutines.runBlocking { k.switcher.switchTo(k.ids[1]) }
        rule.runOnUiThread { flow.signOutOpen(deleteDownloads = false, expected = k.ids[0]) { result = it } }
        waitUntil { result != null }
        assertEquals(SignOutResult.Unknown, result)
        assertTrue("nobody was wiped", k.platform.wiped.isEmpty())
        assertEquals(2, k.book.list.value.profiles.size)
        assertNotNull("the refusal is said", flow.notice)
        assertEquals(L.profilesSignOutFailed(name = "Asha"), flow.notice?.body)
    }

    @Test
    fun `a sign out for the profile still open goes ahead`() {
        val k = ProfileKit("Asha", "Ben").also { kit = it }
        val flow = k.flow()
        var result: SignOutResult? = null
        rule.runOnUiThread { flow.signOutOpen(deleteDownloads = false, expected = k.ids[0]) { result = it } }
        waitUntil { result != null }
        assertTrue(result is SignOutResult.SignedOut)
        assertEquals(listOf(k.ids[0]), k.platform.wiped.toList())
    }

    @Test
    fun `a profile action that fails says a sentence, never the raw text with its folders (SEC-11)`() {
        val k = ProfileKit("Asha", "Ben").also { kit = it }
        val flow = k.flow()
        val raw = "/data/user/0/com.tmplayer/files/profiles/${k.ids[1]}/td.binlog: EACCES"
        k.platform.sayFails = IllegalStateException(raw)
        rule.runOnUiThread { flow.pinChosen(k.ids[1], "1111") }
        waitUntil { flow.notice != null }
        val body = flow.notice?.body.orEmpty()
        assertFalse(body, body.contains("/data/") || body.contains(k.ids[1]) || body.contains("EACCES"))
        assertEquals(com.tmplayer.data.Failures.humanise(raw), body)
    }

    private companion object {
        const val HOME = "The shell of the open profile"
        const val LOGS = "Send logs probe"
    }
}
