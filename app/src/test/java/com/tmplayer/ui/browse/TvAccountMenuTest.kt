package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.tmplayer.data.Account
import com.tmplayer.data.BackupSend
import com.tmplayer.data.BackupSender
import com.tmplayer.data.FormFactor
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.SignOutBackup
import com.tmplayer.data.WatchedStore
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.components.holdable
import com.tmplayer.ui.settings.SignOutHost
import com.tmplayer.ui.settings.rememberSignOutState
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The television's account menu (WP-02), driven by the remote's keys: Menu from the bar and OK on the
 * picture open it with the first header button focused, Right reaches Sign out, its question names
 * the account and starts on Cancel, and a backup started from the menu finishes after the menu has
 * closed because the shell's host owns the job. Menu on a tile stays that tile's options. The phone's
 * popover carries the same two rows.
 *
 * The menu and the questions are windows of their own; the keys are sent to the node that has the
 * remote in each. Left to the device: Fire TV's and Mi's real Menu key, and the Google TV remote
 * (no Menu key: OK on the picture).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class TvAccountMenuTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private companion object {
        const val PIN = "1234"
    }

    private val calls = mutableListOf<String>()

    /** The app lock shut over the app ("ask when the app opens"). */
    private val appLocked = androidx.compose.runtime.mutableStateOf(false)
    private val homeFocus = FocusRequester()
    private val asha = Account(name = "Asha Rao", username = "asha", miniThumbnail = null, photoFileId = 0)

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
        SignOutBackup.sender = com.tmplayer.data.SavedMessagesBackup
    }

    private fun open(several: Boolean = false, tiles: Boolean = false, pin: Boolean = false) {
        FormFactor.override(true)
        Motion.reduced = true
        val settings = SettingsStore(RuntimeEnvironment.getApplication())
        val watched = WatchedStore(RuntimeEnvironment.getApplication())
        // An app lock PIN with Settings' own gate shut: the state of the bar on any page but Settings.
        if (pin) kotlinx.coroutines.runBlocking { settings.setPin(PIN) }
        rule.setContent {
            // A clickable only takes focus in keyboard mode, which a remote puts the window in.
            LocalInputModeManager.current.requestInputMode(InputMode.Keyboard)
            val signOut = rememberSignOutState()
            val nav = TouchNav(
                account = asha, favoriteCount = 0, downloadCount = 0, updateVersion = null,
                onHome = {}, onChats = {}, onSearch = {}, onSettings = { calls += "settings" },
                onFavourites = {}, onDownloads = {}, onHistory = {}, onUpdate = {},
                onSwitchProfile = if (several) ({ calls += "switch" }) else null,
                onSwitchOrAdd = { calls += if (several) "picker" else "add" },
                onSignOut = { signOut.request() },
                signOutPhase = signOut.phase,
            )
            TMPlayerTheme {
                Column(Modifier.fillMaxSize().background(Tone.background)) {
                    TvTopBar(
                        nav = nav, current = TvBarTab.Home, inProfile = false, searchOpen = false,
                        onHome = {}, onChats = {}, onSearch = {}, onRefresh = null,
                        currentFocus = homeFocus, contentFocus = FocusRequester(), overArt = false,
                    )
                    // The page under the bar: two tiles that own Menu, and a plain focusable that owns nothing.
                    Row {
                        if (tiles) {
                            for (name in listOf("a", "b")) {
                                val source = remember { MutableInteractionSource() }
                                Box(
                                    Modifier
                                        .size(120.dp)
                                        .testTag(name)
                                        .holdable(source, onClick = { calls += "click:$name" }, onHold = { calls += "options:$name" }),
                                )
                            }
                        }
                        Box(Modifier.size(120.dp).testTag("bare").focusable())
                    }
                }
                SignOutHost(
                    signOut, name = "Asha Rao", settings = settings, watchedStore = watched, onLoggedOut = { calls += "signedOut" },
                    settingsUnlocked = !pin, severalProfiles = several, appLocked = appLocked.value,
                )
            }
        }
        remoteWindow()
        rule.waitForIdle()
    }

    private fun remoteWindow() {
        val viewRoot = rule.activity.window.decorView.parent
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "ensureTouchMode",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "windowFocusChanged",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
        )
    }

    private fun button(label: String) = rule.onNode(hasText(label) and hasClickAction())

    /** A TV button answers the remote's OK, which is its click action; a touch injection does not reach a TV button. */
    private fun androidx.compose.ui.test.SemanticsNodeInteraction.press() {
        performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
    }

    /** The remote on the account's picture the way a viewer gets there: Left from the tab it starts on, past Search. */
    private fun pictureOnBar(): androidx.compose.ui.test.SemanticsNodeInteraction {
        rule.runOnIdle { homeFocus.requestFocus() }
        rule.waitForIdle()
        repeat(2) {
            rule.onNode(androidx.compose.ui.test.isFocused()).performKeyInput { pressKey(Key.DirectionLeft) }
            rule.waitForIdle()
        }
        return rule.onNodeWithContentDescription("Asha Rao, @asha", substring = true)
    }

    private fun menuOpen() = rule.onAllNodes(hasText(L.settingsSignOutConfirm) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `Menu in the bar opens the menu on the first header button, Right reaches Sign out, OK asks with Cancel focused, Cancel gives Sign out back`() {
        open(several = true)
        pictureOnBar().performKeyInput { pressKey(Key.Menu) }
        rule.waitForIdle()
        button(L.profilesSwitchProfile).assertIsFocused()
        button(L.profilesSwitchProfile).performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        button(L.settingsSignOutConfirm).assertIsFocused()
        button(L.settingsSignOutConfirm).performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        rule.onNodeWithTextExists(L.settingsSignOutTitleNamed("Asha Rao"))
        button(L.commonCancel).assertIsFocused()
        // Cancel (what Back does): the question goes and the remote is on Sign out again.
        button(L.commonCancel).press()
        rule.waitForIdle()
        assertTrue("the menu stays under the question", menuOpen())
        button(L.settingsSignOutConfirm).assertIsFocused()
    }

    @Test
    fun `with one profile the first button reads Add account and opens the picker`() {
        open(several = false)
        pictureOnBar().performKeyInput { pressKey(Key.Menu) }
        rule.waitForIdle()
        button(L.profilesAdd).assertIsFocused()
        rule.onAllNodes(hasText(L.profilesSwitchProfile)).assertCountIs(0)
        button(L.profilesAdd).performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(listOf("add"), calls)
        assertFalse("the menu closes behind the picker", menuOpen())
    }

    @Test
    fun `with two profiles it reads Switch profile and the question names the account and says the other profiles stay`() {
        open(several = true)
        pictureOnBar().performKeyInput { pressKey(Key.Menu) }
        rule.waitForIdle()
        button(L.profilesSwitchProfile).assertIsFocused()
        button(L.settingsSignOutConfirm).press()
        rule.waitForIdle()
        rule.onNodeWithTextExists(L.settingsSignOutTitleNamed("Asha Rao"))
        rule.onNodeWithTextExists(L.profilesSignOutBody(name = "Asha Rao"))
        // With other profiles the words say they stay, not that the sign in screen comes back.
        assertTrue(rule.onAllNodesWithText(L.settingsSignOutMessage).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTextExists(L.signoutBackupTick)
    }

    @Test
    fun `OK on the picture opens the menu`() {
        open(several = true)
        pictureOnBar().assertIsFocused()
        rule.onNodeWithContentDescription("Asha Rao, @asha", substring = true).performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertTrue("a short OK", menuOpen())
        button(L.profilesSwitchProfile).assertIsFocused()
    }

    @Test
    fun `a long OK on the picture opens the same menu`() {
        open(several = true)
        pictureOnBar().performKeyInput { pressKey(Key.Enter, 1_500) }
        rule.waitForIdle()
        assertTrue("a long OK", menuOpen())
        button(L.profilesSwitchProfile).assertIsFocused()
    }

    @Test
    fun `Menu on a focused tile is that tile's options and never the account menu`() {
        open(several = true, tiles = true)
        rule.onNodeWithTag("a").requestFocus()
        rule.onNodeWithTag("a").performKeyInput { pressKey(Key.Menu) }
        rule.waitForIdle()
        assertEquals(listOf("options:a"), calls)
        assertFalse(menuOpen())
    }

    @Test
    fun `Menu where nothing claims it reaches the menu through the bar, and with no bar it is refused`() {
        // No bar on screen: the activity falls back to what Menu did before.
        assertFalse(TvBarSignals.askAccountMenu())
        open(several = true)
        rule.onNodeWithTag("bare").requestFocus()
        rule.onNodeWithTag("bare").performKeyInput { pressKey(Key.Menu) }
        rule.waitForIdle()
        assertFalse("a place with no item action does not open it by itself", menuOpen())
        // What the activity's onKeyUp sends for a Menu nobody took.
        assertTrue(TvBarSignals.askAccountMenu())
        rule.waitForIdle()
        assertTrue(menuOpen())
        button(L.profilesSwitchProfile).assertIsFocused()
        // The remote was on the page and is still there once the menu goes (the menu is a window of its own).
        rule.onNodeWithTag("bare").assertIsFocused()
    }

    @Test
    fun `a backup started from the menu finishes after the menu has closed`() {
        val gate = CompletableDeferred<Unit>()
        SignOutBackup.sender = BackupSender { _, _ ->
            gate.await()
            BackupSend.Failed("Telegram said no")
        }
        open(several = true)
        pictureOnBar().performKeyInput { pressKey(Key.Menu) }
        rule.waitForIdle()
        button(L.settingsSignOutConfirm).press()
        rule.waitForIdle()
        // Confirm in the question (the one with the title naming the account): the backup starts.
        rule.onAllNodes(hasText(L.settingsSignOutConfirm) and hasClickAction()).fetchSemanticsNodes().size.let { assertEquals(2, it) }
        rule.onAllNodes(hasText(L.settingsSignOutConfirm) and hasClickAction())[1].press()
        rule.waitUntil(10_000) { rule.onAllNodes(hasText(L.signoutBackupAnyway)).fetchSemanticsNodes().isNotEmpty() }
        assertFalse("the menu is gone while the backup runs", menuOpen())
        gate.complete(Unit)
        rule.waitUntil(10_000) { rule.onAllNodes(hasText(L.signoutBackupFailedTitle)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTextExists(L.signoutBackupFailedMessage("Telegram said no"))
    }

    private fun phone(several: Boolean, pin: Boolean = false): TouchChrome {
        FormFactor.override(false)
        val chrome = TouchChrome()
        rule.setContent {
            val signOut = rememberSignOutState()
            val nav = TouchNav(
                account = asha, favoriteCount = 0, downloadCount = 0, updateVersion = null,
                onHome = {}, onChats = {}, onSearch = {}, onSettings = {},
                onFavourites = {}, onDownloads = {}, onHistory = {}, onUpdate = {},
                onSwitchProfile = if (several) ({ calls += "switch" }) else null,
                onSwitchOrAdd = { calls += "picker" },
                onSignOut = { calls += "signOut"; signOut.request() },
            )
            TMPlayerTheme {
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                    TouchNavHost(nav, chrome, "page") {
                        androidx.compose.runtime.CompositionLocalProvider(LocalTouchPageKey provides "page") {
                            TouchPage(TouchPageSpec(current = TouchDestination.Home))
                            Box(Modifier.fillMaxSize()) { AccountButton(Modifier.align(androidx.compose.ui.Alignment.TopEnd)) }
                        }
                    }
                }
                SignOutHost(
                    signOut, name = "Asha Rao", settings = SettingsStore(RuntimeEnvironment.getApplication()),
                    watchedStore = WatchedStore(RuntimeEnvironment.getApplication()), onLoggedOut = {},
                    settingsUnlocked = !pin, severalProfiles = several,
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription(L.navAccountNamed("Asha Rao")).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.waitForIdle()
        return chrome
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `the phone's popover ends with Add account and Sign out for one profile, and Switch profile for two, through the same host`() {
        phone(several = false)
        rule.onNodeWithTextExists(L.profilesAdd)
        rule.onAllNodes(hasText(L.profilesSwitchProfile)).assertCountIs(0)
        rule.onNodeWithTextExists(L.settingsSignOutConfirm)
        rule.onNode(hasText(L.settingsSignOutConfirm) and hasClickAction()).press()
        rule.waitForIdle()
        assertEquals(listOf("signOut"), calls)
        // The popover closed and the question is the host's, naming the account.
        rule.onNodeWithTextExists(L.settingsSignOutTitleNamed("Asha Rao"))
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `the phone's popover says Switch profile with two profiles and Add account only with one`() {
        phone(several = true)
        rule.onNodeWithTextExists(L.profilesSwitchProfile)
        rule.onAllNodes(hasText(L.profilesAdd)).assertCountIs(0)
        rule.onNode(hasText(L.profilesSwitchProfile) and hasClickAction()).press()
        rule.waitForIdle()
        assertEquals(listOf("switch"), calls)
    }

    private fun typePin(digits: String) {
        for (d in digits) rule.onNode(hasText(d.toString()) and hasClickAction()).press()
        rule.waitForIdle()
    }

    private fun waitForText(text: String) {
        rule.waitUntil(10_000) { rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `with a PIN set and Settings shut, Sign out from the menu asks the PIN first, Cancel and a wrong PIN sign nothing out and the right one asks`() {
        open(several = true, pin = true)
        rule.waitForIdle()
        // Menu from the bar, then the header button: the way a viewer reaches it with no Settings visit.
        pictureOnBar().performKeyInput { pressKey(Key.Menu) }
        rule.waitForIdle()
        button(L.settingsSignOutConfirm).press()
        rule.waitForIdle()
        rule.onNodeWithTextExists(L.lockEnterForSignOut)
        assertTrue("no question before the PIN", rule.onAllNodesWithText(L.settingsSignOutTitleNamed("Asha Rao")).fetchSemanticsNodes().isEmpty())
        // A wrong PIN: still the pad, still no question.
        typePin("0000")
        rule.onNodeWithTextExists(L.lockEnterForSignOut)
        assertTrue(rule.onAllNodesWithText(L.settingsSignOutTitleNamed("Asha Rao")).fetchSemanticsNodes().isEmpty())
        // Cancel: the pad goes, nothing is asked, nothing is signed out.
        rule.onNode(hasText(L.commonCancel) and hasClickAction()).press()
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText(L.lockEnterForSignOut).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithText(L.settingsSignOutTitleNamed("Asha Rao")).fetchSemanticsNodes().isEmpty())
        assertFalse(calls.contains("signedOut"))
        // Again, with the right PIN: now the question.
        button(L.settingsSignOutConfirm).press()
        rule.waitForIdle()
        typePin(PIN)
        waitForText(L.settingsSignOutTitleNamed("Asha Rao"))
    }

    @Test
    fun `a question waiting is dropped when the app locks, nothing is drawn over the lock, and after unlocking it asks the PIN again`() {
        open(several = true, pin = true)
        rule.waitForIdle()
        pictureOnBar().performKeyInput { pressKey(Key.Menu) }
        rule.waitForIdle()
        button(L.settingsSignOutConfirm).press()
        rule.waitForIdle()
        typePin(PIN)
        waitForText(L.settingsSignOutTitleNamed("Asha Rao"))
        // The app locks (leaving it): the question and the PIN given for it go, and nothing is drawn.
        rule.runOnUiThread { appLocked.value = true }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText(L.settingsSignOutTitleNamed("Asha Rao")).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithText(L.lockEnterForSignOut).fetchSemanticsNodes().isEmpty())
        // Unlocked again: no question is waiting and the PIN given before is not remembered.
        rule.runOnUiThread { appLocked.value = false }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText(L.settingsSignOutTitleNamed("Asha Rao")).fetchSemanticsNodes().isEmpty())
        button(L.settingsSignOutConfirm).press()
        rule.waitForIdle()
        rule.onNodeWithTextExists(L.lockEnterForSignOut)
        assertTrue(rule.onAllNodesWithText(L.settingsSignOutTitleNamed("Asha Rao")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `the phone's popover Sign out asks the PIN first when one is set and Settings is shut`() {
        kotlinx.coroutines.runBlocking { SettingsStore(RuntimeEnvironment.getApplication()).setPin(PIN) }
        phone(several = false, pin = true)
        rule.onNode(hasText(L.settingsSignOutConfirm) and hasClickAction()).press()
        rule.waitForIdle()
        rule.onNodeWithTextExists(L.lockEnterForSignOut)
        assertTrue(rule.onAllNodesWithText(L.settingsSignOutTitleNamed("Asha Rao")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `two quick OKs on Sign out start one backup, and Cancel during Saving stops it for good`() {
        val gate = CompletableDeferred<Unit>()
        var sent = 0
        SignOutBackup.sender = BackupSender { _, _ ->
            sent++
            gate.await()
            BackupSend.Failed("Telegram said no")
        }
        open(several = true)
        pictureOnBar().performKeyInput { pressKey(Key.Menu) }
        rule.waitForIdle()
        button(L.settingsSignOutConfirm).press()
        rule.waitForIdle()
        val confirm = rule.onAllNodes(hasText(L.settingsSignOutConfirm) and hasClickAction())[1]
        // The same press twice before a frame passes.
        confirm.press()
        runCatching { confirm.press() }
        waitForText(L.signoutBackupAnyway)
        rule.waitForIdle()
        assertEquals("one backup, not two", 1, sent)
        // Cancel while it saves: nothing goes on, even when the sender answers afterwards.
        rule.onNode(hasText(L.commonCancel) and hasClickAction()).press()
        rule.waitForIdle()
        gate.complete(Unit)
        Thread.sleep(500)
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText(L.signoutBackupFailedTitle).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithText(L.signoutBackupAnyway).fetchSemanticsNodes().isEmpty())
        assertFalse(calls.contains("signedOut"))
        assertEquals(1, sent)
    }

    @Test
    fun `a second sign out request while a question is up is ignored`() {
        val state = com.tmplayer.ui.settings.SignOutState()
        assertTrue(state.request())
        assertFalse(state.request())
        assertEquals(com.tmplayer.ui.settings.SignOutPhase.Asking, state.phase)
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onNodeWithTextExists(text: String) {
        assertTrue("'$text' is on screen", onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty())
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountIs(n: Int) {
        assertEquals(n, fetchSemanticsNodes().size)
    }
}
