package com.tmplayer.ui.profiles

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import com.tmplayer.data.Account
import com.tmplayer.data.AppLockSettings
import com.tmplayer.data.FormFactor
import com.tmplayer.data.ProfileUnlock
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.browse.TV_ACCOUNT_ROW_TAG
import com.tmplayer.ui.browse.TV_ACCOUNT_SHEET_TAG
import com.tmplayer.ui.browse.TouchNav
import com.tmplayer.ui.browse.TvBarTab
import com.tmplayer.ui.browse.TvTopBar
import com.tmplayer.ui.browse.switchOrAdd
import com.tmplayer.ui.lock.ProfileFlow
import com.tmplayer.ui.lock.ProfilesGate
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Add account with one profile (user, 2026-11-25: on the Mi TV the account sheet closed and nothing
 * else happened). Whatever happens after the press, the viewer sees it: the line over the shell
 * while the open profile's work stops and the next client opens (with Cancel while it waits), then
 * the new profile's sign in; or a notice that says why not, with Try again. Pressed before the app
 * lock was read, it waits for the lock and then goes on without asking a PIN nobody set. Driven
 * from the TV's account sheet with the remote, from Settings' row and the phone's sheet the same
 * flow ([ProfileFlow.add]) is pressed.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class AddAccountTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private var kit: ProfileKit? = null
    private val homeFocus = FocusRequester()
    private val asha = Account(name = "Asha Rao", username = "asha", miniThumbnail = null, photoFileId = 0)

    @Before
    fun setUp() {
        ProfileUnlock.lock()
    }

    @After
    fun tearDown() {
        kit?.platform?.hold?.complete(Unit)
        kit?.close()
        ProfileUnlock.lock()
        FormFactor.override(false)
        Motion.reduced = false
    }

    private fun exists(text: String) = rule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(what: String, timeout: Long = 15_000, check: () -> Boolean) {
        try {
            rule.waitUntil(timeout) { check() }
        } catch (e: Throwable) {
            throw AssertionError("waited for $what", e)
        }
    }

    // ---- the TV: the account sheet over Home, with the remote ---------------------------------

    private fun showTv(flow: ProfileFlow) {
        FormFactor.override(true)
        Motion.reduced = true
        rule.setContent {
            LocalInputModeManager.current.requestInputMode(InputMode.Keyboard)
            // As the shell wires it (MainActivity): one profile, the row is Add account through the flow.
            val nav = TouchNav(
                account = asha, favoriteCount = 0, downloadCount = 0, updateVersion = null,
                onHome = {}, onChats = {}, onSearch = {}, onSettings = {},
                onFavourites = {}, onDownloads = {}, onHistory = {}, onUpdate = {},
                onSwitchProfile = null,
                onSwitchOrAdd = { switchOrAdd(flow, several = false) },
                profile = flow.switcher.profiles.value.active,
            )
            TMPlayerTheme {
                ProfilesGate(flow, signInStep = false) {
                    Column(Modifier.fillMaxSize().background(Tone.background)) {
                        TvTopBar(
                            nav = nav, current = TvBarTab.Home, inProfile = false, searchOpen = false,
                            onHome = {}, onChats = {}, onSearch = {}, onRefresh = null,
                            currentFocus = homeFocus, contentFocus = FocusRequester(), overArt = false,
                        )
                        Text(HOME)
                    }
                }
            }
        }
        remoteWindows()
    }

    /** Every window out of touch mode, the last one focused: what a remote gives on the device. */
    private fun remoteWindows() {
        val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        val roots = org.robolectric.util.ReflectionHelpers.getField<List<Any>>(global, "mRoots").toList()
        for (viewRoot in roots) {
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
                viewRoot, "ensureTouchMode",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
            )
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
                viewRoot, "windowFocusChanged",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, roots.last() === viewRoot),
            )
        }
        rule.waitForIdle()
    }

    private fun picture() = rule.onNodeWithContentDescription("Asha Rao, @asha", substring = true)

    private fun sheetOpen() = rule.onAllNodes(androidx.compose.ui.test.hasTestTag(TV_ACCOUNT_SHEET_TAG)).fetchSemanticsNodes().isNotEmpty()

    /** Up to the picture, OK, down to Add account, OK: what the viewer did on the Mi TV. */
    private fun pressAddOnTheSheet() {
        rule.runOnIdle { homeFocus.requestFocus() }
        rule.waitForIdle()
        repeat(2) {
            rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionLeft) }
            rule.waitForIdle()
        }
        picture().assertIsFocused()
        picture().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertTrue("OK on the picture opens the sheet", sheetOpen())
        remoteWindows()
        val row = rule.onNodeWithTag("$TV_ACCOUNT_ROW_TAG-switch", useUnmergedTree = true)
        row.performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        rule.onNode(hasText(L.profilesAdd) and hasClickAction()).assertExists()
        row.performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        remoteWindows()
    }

    @Test
    fun `TV, one profile, Add account shows its line over Home while the open profile's work stops, then opens the new profile on its sign in`() {
        val k = ProfileKit("Asha Rao").also { kit = it }
        // The open profile's work is slow to stop (a sync), as it can be on a TV stick.
        val hold = CompletableDeferred<Unit>()
        k.platform.hold = hold
        val flow = k.flow()
        showTv(flow)
        pressAddOnTheSheet()
        waitFor("the sheet to close") { !sheetOpen() }
        // Never a silent close: the line over the shell says what it waits for, with Cancel.
        waitFor("the wait's line over Home") { exists(L.profilesWaiting(what = "a sync")) }
        rule.onNode(hasText(L.commonCancel) and hasClickAction()).assertExists()
        // A second press while it runs adds nothing twice.
        rule.runOnIdle { flow.add() }
        hold.complete(Unit)
        waitFor("the new profile to open") { k.book.list.value.profiles.size == 2 && !flow.adding }
        val list = k.book.list.value
        assertNotEquals("the new profile is the open one", k.ids[0], list.activeId)
        assertTrue("it opens on its sign in", list.active?.adding == true && list.active?.signedIn == false)
        rule.waitForIdle()
        assertFalse("the line goes once it is open", exists(L.profilesWaiting(what = "a sync")))
        assertEquals("no notice", null, flow.notice)
    }

    @Test
    fun `TV, Cancel on the wait keeps the open profile and puts the remote back on the picture`() {
        val k = ProfileKit("Asha Rao").also { kit = it }
        val hold = CompletableDeferred<Unit>()
        k.platform.hold = hold
        val flow = k.flow()
        showTv(flow)
        pressAddOnTheSheet()
        waitFor("the wait's line over Home") { exists(L.profilesWaiting(what = "a sync")) }
        waitFor("Cancel to have the remote") {
            runCatching { rule.onNode(hasText(L.commonCancel) and hasClickAction()).assertIsFocused() }.isSuccess
        }
        rule.onNode(hasText(L.commonCancel) and hasClickAction()).performKeyInput { pressKey(Key.Enter) }
        waitFor("the line to go") { !flow.adding && !exists(L.profilesWaiting(what = "a sync")) }
        assertEquals("nothing added", 1, k.book.list.value.profiles.size)
        waitFor("the remote back on the picture") { runCatching { picture().assertIsFocused() }.isSuccess }
    }

    @Test
    fun `TV, no room for a new profile says so with Try again, and OK puts the remote back on the picture`() {
        val k = ProfileKit("Asha Rao").also { kit = it }
        k.base.free = 1L
        val flow = k.flow()
        showTv(flow)
        pressAddOnTheSheet()
        waitFor("the no space notice") { exists(L.profilesNoSpaceTitle) }
        rule.onNode(hasText(L.commonTryAgain) and hasClickAction()).assertExists()
        assertEquals(1, k.book.list.value.profiles.size)
        // The notice has the remote (Try again first); Down to OK, OK.
        waitFor("Try again to have the remote") {
            runCatching { rule.onNode(hasText(L.commonTryAgain) and hasClickAction()).assertIsFocused() }.isSuccess
        }
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNode(hasText(L.commonOk) and hasClickAction()).assertIsFocused()
        rule.onNode(isFocused()).performKeyInput { pressKey(Key.Enter) }
        waitFor("the notice to close") { flow.notice == null }
        waitFor("the remote back on the picture") { runCatching { picture().assertIsFocused() }.isSuccess }
    }

    @Test
    fun `TV, a client that cannot be reopened says the add failed, and Try again adds`() {
        val k = ProfileKit("Asha Rao").also { kit = it }
        k.host.failReopen = true
        val flow = k.flow()
        showTv(flow)
        pressAddOnTheSheet()
        waitFor("the add failed notice") { exists(L.profilesAddFailed) }
        assertEquals(1, k.book.list.value.profiles.size)
        k.host.failReopen = false
        rule.onNode(hasText(L.commonTryAgain) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        waitFor("the new profile to open") { k.book.list.value.profiles.size == 2 && !flow.adding }
        assertEquals(null, flow.notice)
    }

    // ---- the flow itself, the same for every place it is pressed ------------------------------

    @Test
    fun `work cut short by anything but the viewer's Cancel shows the add failed notice, never a silent end`() {
        val k = ProfileKit("Asha Rao").also { kit = it }
        val hold = CompletableDeferred<Unit>()
        k.platform.hold = hold
        val flow = k.flow()
        showTv(flow)
        rule.runOnIdle { flow.add() }
        waitFor("the wait") { flow.waitCancellable }
        // A timeout inside the work, say: a cancellation the viewer did not ask for.
        hold.completeExceptionally(CancellationException("cut short"))
        waitFor("the add failed notice") { exists(L.profilesAddFailed) }
        waitFor("the line to go") { !flow.adding }
        assertEquals(1, k.book.list.value.profiles.size)
        assertTrue("Try again is offered", flow.notice?.retry != null)
    }

    @Test
    fun `pressed before the app lock was read, Add shows its wait with no pad, then goes on without a PIN nobody set`() {
        val k = ProfileKit("Asha Rao").also { kit = it }
        val lock = MutableSharedFlow<AppLockSettings?>()
        k.platform.readable = false
        k.platform.lockFlow = lock
        val flow = k.flow()
        showTv(flow)
        rule.runOnIdle { flow.add() }
        rule.waitForIdle()
        // Fail closed: no PIN pad until the lock is known, but the press is answered by its wait.
        assertFalse(exists(L.lockEnterPin))
        assertTrue("the wait is drawn, never nothing", exists(L.profilesAddingNow))
        assertEquals(1, k.book.list.value.profiles.size)
        rule.runOnIdle { kotlinx.coroutines.runBlocking { lock.emit(AppLockSettings()) } }
        waitFor("the new profile to open") { k.book.list.value.profiles.size == 2 && !flow.adding }
        assertFalse("no PIN asked that was never set", exists(L.lockEnterPin))
    }

    @Test
    fun `pressed before the app lock was read, with a PIN set the pad shows once it is read`() {
        val k = ProfileKit("Asha Rao").also { kit = it }
        val lock = MutableSharedFlow<AppLockSettings?>()
        k.platform.readable = false
        k.platform.lockFlow = lock
        val flow = k.flow()
        showTv(flow)
        rule.runOnIdle { flow.add() }
        rule.waitForIdle()
        assertFalse(exists(L.lockEnterPin))
        rule.runOnIdle { kotlinx.coroutines.runBlocking { lock.emit(AppLockSettings(pinSet = true)) } }
        waitFor("the app lock's pad") { exists(L.lockEnterPin) }
        assertEquals("nothing added before the PIN", 1, k.book.list.value.profiles.size)
    }

    @Test
    fun `Add while a sign out runs says another change is under way, never nothing`() {
        val k = ProfileKit("Asha Rao").also { kit = it }
        val hold = CompletableDeferred<Unit>()
        k.platform.hold = hold
        val flow = k.flow()
        showTv(flow)
        rule.runOnIdle { flow.signOutOpen(deleteDownloads = false) }
        waitFor("the sign out to run") { flow.status != null }
        rule.runOnIdle { flow.add() }
        waitFor("the busy notice") { exists(L.profilesAddBusy) }
        hold.complete(Unit)
    }

    // ---- Settings' row and the phone: the same flow, pressed from inside the shell ------------

    @Test
    @Config(qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `phone, Settings' Add account with one profile shows the line over Settings, then opens the new profile`() {
        val k = ProfileKit("Asha Rao").also { kit = it }
        val hold = CompletableDeferred<Unit>()
        k.platform.hold = hold
        val flow = k.flow()
        rule.setContent {
            TMPlayerTheme {
                ProfilesGate(flow, signInStep = false) {
                    // Settings' row, as SettingsScreen wires it.
                    Column(Modifier.fillMaxSize().background(Tone.background)) {
                        Button(onClick = { flow.add() }) { Text(SETTINGS_ADD) }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText(SETTINGS_ADD).performSemanticsAction(SemanticsActions.OnClick)
        waitFor("the wait's line over Settings") { exists(L.profilesWaiting(what = "a sync")) }
        hold.complete(Unit)
        waitFor("the new profile to open") { k.book.list.value.profiles.size == 2 && !flow.adding }
        assertTrue(k.book.list.value.active?.adding == true)
    }

    private companion object {
        const val HOME = "Home page"
        const val SETTINGS_ADD = "Settings: Add account"
    }
}
