package com.tmplayer.ui.profiles

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tmplayer.data.FormFactor
import com.tmplayer.data.ProfilePin
import com.tmplayer.data.ProfileUnlock
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.lock.ProfileFlow
import com.tmplayer.ui.lock.ProfilesGate
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The gate round the shell on a phone (HX88): one profile goes straight in, two meet the picker,
 * and a profile's PIN guards its content on every way in (a cold start, a sign out, a remove, a
 * process started again), with the five tries and the 30 second wait of [ProfilePin].
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-port-night-xhdpi")
class ProfilesGateTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private var kit: ProfileKit? = null

    @org.junit.Before
    fun setUp() {
        ProfileUnlock.lock()
    }

    @After
    fun tearDown() {
        kit?.close()
        ProfileUnlock.lock()
        FormFactor.override(false)
    }

    private fun show(flow: ProfileFlow, signInStep: Boolean = false) {
        rule.setContent {
            TmMaterialTheme(dark = true) {
                ProfilesGate(flow, signInStep = signInStep) { Text(HOME) }
            }
        }
        rule.waitForIdle()
    }

    private fun typePin(pin: String) {
        for (digit in pin) {
            rule.onNodeWithText(digit.toString()).performClick()
        }
        rule.waitForIdle()
    }

    private fun waitFor(text: String) {
        rule.waitUntil(15_000) { runCatching { rule.onNodeWithText(text, substring = true).assertExists() }.isSuccess }
    }

    @Test
    fun `with one profile the launch goes straight in, no picker`() {
        val k = ProfileKit("Asha").also { kit = it }
        show(k.flow())
        rule.onNodeWithText(HOME).assertExists()
        rule.onNodeWithText(L.profilesWhoIsWatching).assertDoesNotExist()
        rule.onNodeWithText(L.profilesManage).assertDoesNotExist()
    }

    @Test
    fun `with two profiles the picker comes first, a tap opens the profile`() {
        val k = ProfileKit("Asha", "Ben").also { kit = it }
        show(k.flow())
        rule.onNodeWithText(L.profilesWhoIsWatching).assertExists()
        rule.onNodeWithText(HOME).assertDoesNotExist()
        rule.onNodeWithText(L.profilesAdd).assertExists()
        rule.onNodeWithContentDescription("Ben", substring = true).performClick()
        waitFor(HOME)
        assertEquals(k.ids[1], k.book.list.value.activeId)
    }

    @Test
    fun `at the limit of three the Add tile goes and the line says the limit`() {
        val k = ProfileKit("Asha", "Ben", "Cleo").also { kit = it }
        show(k.flow())
        rule.onNodeWithText(L.profilesAdd).assertDoesNotExist()
        rule.onNodeWithText(L.profilesLimit(count = 3)).assertExists()
    }

    @Test
    fun `one profile with a PIN asks it on a cold start, and a process started again asks again`() {
        val k = ProfileKit("Asha").also { kit = it }
        k.pin(0, "1234")
        val flow = k.flow()
        show(flow)
        rule.onNodeWithText(L.profilesPinFor(name = "Asha")).assertExists()
        rule.onNodeWithText(HOME).assertDoesNotExist()
        typePin("1234")
        waitFor(HOME)
        assertTrue(ProfileUnlock.isUnlocked(k.ids[0]))
        // The process dies: the unlock lived in memory alone, so the next process asks again
        // (what a new process starts with: nothing unlocked).
        ProfileUnlock.lock()
        val again = k.flow()
        assertTrue(again.needsUnlock(k.book.list.value))
    }

    @Test
    fun `signing out one profile leaves the other behind its PIN`() {
        val k = ProfileKit("Asha", "Ben").also { kit = it }
        k.pin(1, "4321")
        val flow = k.flow()
        show(flow)
        // Asha is open and picked; she signs herself out from Settings.
        rule.onNodeWithContentDescription("Asha", substring = true).performClick()
        waitFor(HOME)
        flow.signOutOpen(deleteDownloads = false)
        waitFor(L.profilesPinFor(name = "Ben"))
        rule.onNodeWithText(HOME).assertDoesNotExist()
        assertEquals(listOf(k.ids[0]), k.platform.wiped.toList())
        typePin("4321")
        waitFor(HOME)
    }

    @Test
    fun `removing a broken profile keeps the picker, and the locked one still asks its PIN`() {
        val k = ProfileKit("Asha", "Ben", "Cleo").also { kit = it }
        k.pin(1, "4321")
        // Cleo's folder is gone behind the app's back.
        val cleo = k.book.list.value.byId(k.ids[2])!!
        com.tmplayer.data.AccountDirs.pathsFor(k.base, cleo).databaseDir.deleteRecursively()
        val flow = k.flow()
        show(flow)
        rule.onNodeWithText(L.profilesNeedsAttention).assertExists()
        rule.onNodeWithContentDescription("Cleo", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(L.profilesRemove).performClick()
        rule.waitUntil(15_000) { k.book.list.value.byId(k.ids[2]) == null }
        rule.waitForIdle()
        // The book changes before the remove's own work ends (the lock and the picker follow it
        // on another thread): wait for both, so a loaded machine does not race the check.
        rule.waitUntil(15_000) { ProfileUnlock.unlockedId.value == null }
        assertEquals("a remove locks the open profile too", null, ProfileUnlock.unlockedId.value)
        waitFor(L.profilesWhoIsWatching)
        rule.onNodeWithContentDescription("Ben", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(L.profilesPinFor(name = "Ben")).assertExists()
        rule.onNodeWithText(HOME).assertDoesNotExist()
    }

    @Test
    fun `five wrong PINs start the 30 second wait, counted down`() {
        val k = ProfileKit("Asha", "Ben").also { kit = it }
        k.pin(1, "4321")
        show(k.flow())
        rule.onNodeWithContentDescription("Ben", substring = true).performClick()
        rule.waitForIdle()
        repeat(ProfilePin.TRIES) { n ->
            typePin("0000")
            rule.waitUntil(15_000) { k.book.list.value.byId(k.ids[1])!!.pinFailures == n + 1 }
            rule.waitForIdle()
        }
        waitFor(L.lockWaitSeconds(seconds = 30).substringBefore("30"))
        rule.onNodeWithText(HOME).assertDoesNotExist()
        assertEquals(ProfilePin.WAIT_MS, k.book.list.value.byId(k.ids[1])!!.pinWaitLeftMs)
    }

    @Test
    fun `switching while a video plays asks first, then stops it before the switch`() {
        val k = ProfileKit("Asha", "Ben").also { kit = it }
        val flow = k.flow()
        show(flow)
        rule.onNodeWithContentDescription("Asha", substring = true).performClick()
        waitFor(HOME)
        k.platform.playing = true
        flow.ask()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Ben", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(L.profilesSwitchPlayTitle(name = "Ben")).assertExists()
        assertEquals(k.ids[0], k.book.list.value.activeId)
        rule.onNodeWithText(L.profilesSwitch).performClick()
        waitFor(HOME)
        assertEquals(1, k.platform.stopped)
        assertEquals(k.ids[1], k.book.list.value.activeId)
    }

    @Test
    fun `Back on the picker the menu opened goes back to the open profile`() {
        val k = ProfileKit("Asha", "Ben").also { kit = it }
        val flow = k.flow()
        show(flow)
        rule.onNodeWithContentDescription("Asha", substring = true).performClick()
        waitFor(HOME)
        flow.ask()
        rule.waitForIdle()
        rule.onNodeWithText(L.profilesWhoIsWatching).assertExists()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithText(HOME).assertExists()
        assertFalse(rule.activity.isFinishing)
    }

    @Test
    fun `Back on the picker at launch leaves the app`() {
        val k = ProfileKit("Asha", "Ben").also { kit = it }
        show(k.flow())
        rule.onNodeWithText(L.profilesWhoIsWatching).assertExists()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertTrue(rule.activity.isFinishing)
    }

    @Test
    fun `Back on a sign in screen with another profile beside it brings the picker`() {
        val k = ProfileKit("Asha", "Ben").also { kit = it }
        val flow = k.flow()
        show(flow, signInStep = true)
        rule.onNodeWithText(L.profilesSignInAgain).assertExists()
        rule.onNodeWithContentDescription("Asha", substring = true).performClick()
        waitFor(HOME)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithText(L.profilesWhoIsWatching).assertExists()
        assertFalse(rule.activity.isFinishing)
    }

    @Test
    fun `switching away from a profile and back asks its PIN again`() {
        val k = ProfileKit("Asha", "Ben").also { kit = it }
        k.pin(1, "4321")
        val flow = k.flow()
        show(flow)
        rule.onNodeWithContentDescription("Ben", substring = true).performClick()
        rule.waitForIdle()
        typePin("4321")
        waitFor(HOME)
        assertTrue(ProfileUnlock.isUnlocked(k.ids[1]))
        flow.ask()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Asha", substring = true).performClick()
        waitFor(HOME)
        assertFalse("switching away locks it", ProfileUnlock.isUnlocked(k.ids[1]))
        flow.ask()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Ben", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(L.profilesPinFor(name = "Ben")).assertExists()
        rule.onNodeWithText(HOME).assertDoesNotExist()
    }

    @Test
    fun `signing out a locked profile asks its PIN first`() {
        val k = ProfileKit("Asha", "Ben").also { kit = it }
        k.pin(1, "4321")
        val flow = k.flow()
        show(flow)
        flow.signOut(k.ids[1])
        rule.waitForIdle()
        rule.onNodeWithText(L.profilesPinFor(name = "Ben")).assertExists()
        rule.onNodeWithText(L.profilesSignOutTitle(name = "Ben")).assertDoesNotExist()
        // A caller that skips the gate is sent through it.
        flow.confirmSignOut(k.ids[1], deleteDownloads = false)
        rule.waitForIdle()
        assertTrue(k.platform.wiped.isEmpty())
        rule.onNodeWithText(L.profilesPinFor(name = "Ben")).assertExists()
        typePin("4321")
        waitFor(L.profilesSignOutTitle(name = "Ben"))
    }

    @Test
    fun `remove all accounts with one wrong PIN wipes nothing`() {
        val k = ProfileKit("Asha", "Ben").also { kit = it }
        k.pin(1, "4321")
        val flow = k.flow()
        show(flow)
        rule.onNodeWithContentDescription("Asha", substring = true).performClick()
        waitFor(HOME)
        flow.removeAll(deleteDownloads = false)
        rule.waitForIdle()
        rule.onNodeWithText(L.profilesPinFor(name = "Ben")).assertExists()
        typePin("0000")
        rule.waitUntil(15_000) { k.book.list.value.byId(k.ids[1])!!.pinFailures == 1 }
        rule.waitForIdle()
        // Given up at the wrong PIN: back where it came from, every profile still there.
        rule.onNodeWithText(L.commonCancel).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(HOME).assertExists()
        assertEquals(2, k.book.list.value.profiles.size)
        assertTrue(k.platform.wiped.isEmpty())
    }

    @Test
    fun `remove all accounts asks the app lock's PIN, then every profile's, then wipes`() {
        val k = ProfileKit("Asha", "Ben").also { kit = it }
        k.pin(1, "4321")
        k.platform.appPin = "9999"
        val flow = k.flow()
        show(flow)
        rule.onNodeWithContentDescription("Asha", substring = true).performClick()
        waitFor(HOME)
        flow.removeAll(deleteDownloads = false)
        rule.waitForIdle()
        rule.onNodeWithText(L.lockEnterPin).assertExists()
        typePin("9999")
        rule.waitUntil(15_000) { runCatching { rule.onNodeWithText(L.profilesPinFor(name = "Ben")).assertExists() }.isSuccess }
        assertTrue("nothing wiped before every PIN", k.platform.wiped.isEmpty())
        typePin("4321")
        rule.waitUntil(15_000) { k.book.list.value.profiles.size == 1 && k.platform.wiped.size == 2 }
    }

    @Test
    fun `the app lock not read yet keeps the picker away until it is`() {
        val k = ProfileKit("Asha", "Ben").also { kit = it }
        val later = kotlinx.coroutines.flow.MutableSharedFlow<com.tmplayer.data.AppLockSettings?>(replay = 1)
        k.platform.readable = false
        k.platform.lockFlow = later
        val flow = k.flow()
        assertTrue("fail closed: everything asks the app lock", flow.appLockPin)
        show(flow)
        rule.onNodeWithText(L.profilesWhoIsWatching).assertDoesNotExist()
        rule.onNodeWithText(HOME).assertDoesNotExist()
        later.tryEmit(com.tmplayer.data.AppLockSettings())
        waitFor(L.profilesWhoIsWatching)
        assertFalse(flow.appLockPin)
    }

    @Test
    fun `with one profile Add account asks first while a video plays`() {
        val k = ProfileKit("Asha").also { kit = it }
        val flow = k.flow()
        show(flow)
        rule.onNodeWithText(HOME).assertExists()
        k.platform.playing = true
        flow.add()
        rule.waitForIdle()
        rule.onNodeWithText(L.profilesSwitchPlayTitle(name = L.profilesAdd)).assertExists()
        // The shell stays composed under the panel, so Cancel lands back where it came from.
        rule.onNodeWithText(HOME).assertExists()
        rule.onNodeWithText(L.commonCancel).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(HOME).assertExists()
        assertEquals(1, k.book.list.value.profiles.size)
    }

    @Test
    fun `a PIN pad without a checker refuses every PIN`() {
        var done = 0
        rule.setContent {
            TmMaterialTheme(dark = true) {
                com.tmplayer.ui.lock.PinPad(
                    checker = null,
                    purpose = com.tmplayer.ui.lock.PinPurpose.Check,
                    title = "PIN",
                    onDone = { done++ },
                    onCancel = null,
                )
            }
        }
        rule.waitForIdle()
        typePin("1234")
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText(L.lockWrong(left = 0), substring = true).assertExists() }.isSuccess }
        assertEquals(0, done)
    }

    @Test
    fun `a switch waiting for work to stop says what it waits for, and Cancel stays on the open profile`() {
        val k = ProfileKit("Asha", "Ben").also { kit = it }
        k.platform.hold = kotlinx.coroutines.CompletableDeferred()
        show(k.flow())
        rule.onNodeWithContentDescription("Ben", substring = true).performClick()
        waitFor(L.profilesWaiting(what = "a sync"))
        rule.onNodeWithText(L.commonCancel).performClick()
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText(L.profilesWaiting(what = "a sync")).assertDoesNotExist() }.isSuccess }
        k.platform.hold?.complete(Unit)
        Thread.sleep(300)
        rule.waitForIdle()
        assertEquals("nothing was switched", k.ids[0], k.book.list.value.activeId)
    }

    @Test
    fun `with only downloads moving the question speaks of the downloads, not a video's place`() {
        val k = ProfileKit("Asha", "Ben").also { kit = it }
        val flow = k.flow()
        show(flow)
        rule.onNodeWithContentDescription("Asha", substring = true).performClick()
        waitFor(HOME)
        k.platform.downloading = true
        flow.ask()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Ben", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(L.profilesSwitchDownloadsBody).assertExists()
        rule.onNodeWithText(L.profilesSwitchPlayBody).assertDoesNotExist()
    }

    @Test
    fun `a Settings sign out cancelled while it waits tells Settings, so its line clears`() {
        val k = ProfileKit("Asha", "Ben").also { kit = it }
        k.platform.hold = kotlinx.coroutines.CompletableDeferred()
        val flow = k.flow()
        show(flow)
        rule.onNodeWithContentDescription("Asha", substring = true).performClick()
        waitFor(HOME)
        var told: com.tmplayer.data.SignOutResult? = null
        flow.signOutOpen(deleteDownloads = false) { told = it }
        rule.waitUntil(5_000) { flow.waitCancellable }
        flow.cancelWaiting()
        rule.waitUntil(5_000) { told != null }
        k.platform.hold?.complete(Unit)
        assertEquals(com.tmplayer.data.SignOutResult.Unknown, told)
        assertEquals("nobody was signed out", 2, k.book.list.value.profiles.size)
        assertTrue(k.platform.wiped.isEmpty())
    }

    @Test
    fun `an Add account with no room left, with one profile, resumes what its wait paused`() {
        val k = ProfileKit("Asha").also { kit = it }
        k.base.free = 1L
        val flow = k.flow()
        show(flow)
        flow.add()
        rule.waitUntil(15_000) { k.platform.resumed == 1 }
        rule.waitForIdle()
        rule.onNodeWithText(L.profilesNoSpaceTitle).assertExists()
        assertEquals(1, k.book.list.value.profiles.size)
    }

    @Test
    fun `an Add account at the limit resumes what its wait paused`() {
        val k = ProfileKit("Asha", "Ben", "Cleo").also { kit = it }
        val flow = k.flow()
        show(flow)
        flow.add()
        rule.waitUntil(15_000) { k.platform.resumed == 1 }
    }

    @Test
    fun `a switch that fails resumes what its wait paused, and so does its retry`() {
        val k = ProfileKit("Asha", "Ben").also { kit = it }
        k.host.failReopen = true
        show(k.flow())
        rule.onNodeWithContentDescription("Ben", substring = true).performClick()
        rule.waitUntil(15_000) { k.platform.resumed == 1 }
        rule.waitForIdle()
        rule.onNodeWithText(L.commonOk).performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Ben", substring = true).performClick()
        rule.waitUntil(15_000) { k.platform.resumed == 2 }
        assertEquals(k.ids[0], k.book.list.value.activeId)
    }

    @Test
    fun `a Settings sign out that throws part way tells Settings and resumes what its wait paused`() {
        val k = ProfileKit("Asha", "Ben").also { kit = it }
        k.host.failEndSession = true
        val flow = k.flow()
        show(flow)
        rule.onNodeWithContentDescription("Asha", substring = true).performClick()
        waitFor(HOME)
        var told: com.tmplayer.data.SignOutResult? = null
        flow.signOutOpen(deleteDownloads = false) { told = it }
        rule.waitUntil(15_000) { told != null }
        assertEquals(com.tmplayer.data.SignOutResult.Unknown, told)
        rule.waitUntil(5_000) { k.platform.resumed == 1 }
    }

    @Test
    fun `a Cancel's resume ends before a quick retry's wait begins`() {
        val k = ProfileKit("Asha", "Ben").also { kit = it }
        k.platform.hold = kotlinx.coroutines.CompletableDeferred()
        val flow = k.flow()
        show(flow)
        rule.onNodeWithContentDescription("Ben", substring = true).performClick()
        rule.waitUntil(5_000) { flow.waitCancellable }
        flow.cancelWaiting()
        // The retry as soon as the picker takes presses again.
        k.platform.hold = null
        rule.waitUntil(5_000) { flow.status == null }
        rule.onNodeWithContentDescription("Ben", substring = true).performClick()
        rule.waitUntil(15_000) { k.platform.order.count { it == "wait" } == 2 }
        val order = k.platform.order.toList()
        assertEquals("the retry waits for the resume: $order", listOf("wait", "resume", "wait"), order.take(3))
    }

    private companion object {
        const val HOME = "The shell of the open profile"
    }
}
