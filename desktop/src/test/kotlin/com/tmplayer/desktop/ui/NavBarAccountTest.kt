package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tmplayer.data.Account
import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.UpdateScheduler
import com.tmplayer.data.WatchedStore
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.DesktopWatchCache
import com.tmplayer.desktop.profiles.DesktopProfileKit
import com.tmplayer.desktop.profiles.DesktopProfiles
import com.tmplayer.i18n.L
import com.tmplayer.ui.components.LocalRemoteArrows
import com.tmplayer.ui.nav.LocalBackStack
import com.tmplayer.ui.theme.FocusVisibility
import com.tmplayer.ui.theme.TmMaterialTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * The window's account picture on the floating bar and its menu (WP-103): the phone's chain (the
 * profile's letter or photo while Telegram has not answered, the glyph only with no name), the
 * first name under it, "Account, <name>" for a screen reader, and the menu's head and rows (Add
 * account or Switch profile, Settings, the lists, Sign out), walked with Tab, Enter, Down and Esc.
 * With an app PIN set and Settings shut, Add account asks the PIN first.
 *
 * Left to the device: NVDA on the Windows VM reading the popover.
 */
@OptIn(ExperimentalTestApi::class)
class NavBarAccountTest {

    private val settings = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-nav-account").resolve(SettingsStore.FILE_NAME).toFile()),
    )
    private val dir = Files.createTempDirectory("tm-nav-account-extras").toFile()
    private val shell = ShellState(
        settings,
        object : DownloadRunner {
            override fun download(request: DownloadRequest) = Unit
            override fun cancel(fileId: Int) = Unit
            override fun pause(fileId: Int) = Unit
        },
        WatchedStore(WatchedStore.openDataStore(Files.createTempDirectory("tm-nav-account-watched").resolve(WatchedStore.FILE_NAME).toFile())),
        services = {
            DesktopExtras(
                prefs = DesktopPrefs(dir.resolve("desktop.properties")),
                updates = UpdateScheduler(settings.updatePrefs, check = { _, _ -> false }, onSkip = {}),
                watchCache = DesktopWatchCache(settings, { dir.resolve("cache") }),
            )
        },
    )
    private val kits = ArrayList<DesktopProfileKit>()
    private val asha = Account(name = "Asha Rao", username = "asha", miniThumbnail = null, photoFileId = 0)

    @Before
    fun clean() {
        FocusVisibility.visible = true
        BigScreen.override = null
        BigScreen.layout = com.tmplayer.desktop.DesktopLayout.Desktop
    }

    @After
    fun restore() {
        DesktopProfiles.replaceForTest(null)
        kits.forEach { it.close() }
        BigScreen.override = null
    }

    /** [names] profiles on the device, the first one open. */
    private fun profiles(vararg names: String, appPin: String? = null): DesktopProfileKit {
        val kit = DesktopProfileKit(*names).also { kits += it }
        kit.platform.appPin = appPin
        // The open profile already entered, as after its launch picker: the shell is on screen.
        DesktopProfiles.replaceForTest(kit.flow().also { it.close() })
        return kit
    }

    @Composable
    private fun Window(account: Account?, update: NavUpdate?) {
        TmMaterialTheme(dark = true) {
            val focus = LocalFocusManager.current
            SideEffect { shell.focusManager = focus }
            CompositionLocalProvider(LocalBackStack provides shell.backStack, LocalRemoteArrows provides false) {
                Box(Modifier.size(1280.dp, 800.dp).onPreviewKeyEvent(shell::onPreviewKey).onKeyEvent(shell::onKey)) {
                    // The profiles' own gate, as the shell draws it: Add account's PIN pad is its.
                    com.tmplayer.ui.lock.ProfilesGate(DesktopProfiles.flow(), signInStep = false) {
                        NavFrame(shell, update, account) {}
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.window(account: Account? = asha, update: NavUpdate? = null) {
        setContent { Window(account, update) }
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasContentDescription(L.browseTabHome) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.exists(text: String) = onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.cell(description: String) = onNode(hasContentDescription(description) and hasClickAction())

    private fun ComposeUiTest.row(label: String) = onNode(hasText(label) and hasClickAction())

    private fun ComposeUiTest.press(key: Key) {
        onNode(isFocused()).performKeyInput { pressKey(key) }
        waitForIdle()
    }

    @Test
    fun `the cell says whose account it is and shows the first name, not You`() = runComposeUiTest {
        window()
        cell(L.navAccountNamed("Asha Rao")).assertExists()
        assertTrue("the first name under the picture", exists("Asha"))
        assertFalse(exists(L.navYou))
        assertTrue(onAllNodes(hasContentDescription(L.navProfile)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `before Telegram answers the open profile's name is said, and with no name at all the plain word`() = runComposeUiTest {
        profiles("Maya Lee")
        window(account = null)
        cell(L.navAccountNamed("Maya Lee")).assertExists()
        assertTrue(exists("Maya"))
    }

    @Test
    fun `with no name known it says Account and draws the glyph`() = runComposeUiTest {
        window(account = null)
        cell(L.navAccount).assertExists()
        onNode(androidx.compose.ui.test.hasTestTag(NAV_PICTURE_TAG), useUnmergedTree = true).assertExists()
    }

    @Test
    fun `an update waiting is said after the name`() = runComposeUiTest {
        window(update = NavUpdate("Update", "1.30.0"))
        cell("${L.navAccountNamed("Asha Rao")}, ${L.navUpdateWaiting}").assertExists()
    }

    @Test
    fun `the menu's head names the account, one profile reads Add account, and the rows are there`() = runComposeUiTest {
        profiles("Asha Rao")
        window()
        cell(L.navAccountNamed("Asha Rao")).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        assertTrue(shell.profileOpen)
        onNode(androidx.compose.ui.test.hasTestTag(NAV_MENU_HEAD_TAG), useUnmergedTree = true).assertExists()
        assertTrue(exists("@asha"))
        for (label in listOf(L.profilesAdd, L.navSettings, L.browseTabFavourites, L.browseTabHistory, L.browseTabWatchLater, L.settingsSignOutConfirm)) {
            row(label).assertExists()
        }
        assertFalse(exists(L.profilesSwitchProfile))
        row(L.navSettings).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        assertEquals(Destination.Settings, shell.destination)
        assertFalse(shell.profileOpen)
    }

    @Test
    fun `two profiles read Switch profile`() = runComposeUiTest {
        profiles("Asha Rao", "Ben Ode")
        window()
        cell(L.navAccountNamed("Asha Rao")).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        row(L.profilesSwitchProfile).assertExists()
        assertFalse(exists(L.profilesAdd))
    }

    @Test
    fun `Enter opens the menu on its first row, Down walks it, Esc closes it onto the picture`() = runComposeUiTest {
        profiles("Asha Rao")
        window()
        cell(L.navAccountNamed("Asha Rao")).requestFocus()
        press(Key.Enter)
        assertTrue(shell.profileOpen)
        waitForIdle()
        row(L.profilesAdd).assertIsFocused()
        press(Key.DirectionDown)
        row(L.navSettings).assertIsFocused()
        press(Key.Escape)
        assertFalse(shell.profileOpen)
        cell(L.navAccountNamed("Asha Rao")).assertIsFocused()
    }

    @Test
    fun `Sign out from the menu asks the question naming the account`() = runComposeUiTest {
        profiles("Asha Rao")
        window()
        cell(L.navAccountNamed("Asha Rao")).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        row(L.settingsSignOutConfirm).performSemanticsAction(SemanticsActions.OnClick)
        waitUntil(timeoutMillis = 5_000) { exists(L.settingsSignOutTitleNamed("Asha Rao")) }
    }

    /** How many PIN pads are up: each draws its title once. */
    private fun ComposeUiTest.pads(): Int = listOf(L.lockEnterPin, L.lockEnterForSignOut, L.lockEnterForSettings)
        .sumOf { onAllNodes(hasText(it), useUnmergedTree = true).fetchSemanticsNodes().size }

    @Test
    fun `with an app PIN set and Settings shut, Add account asks the PIN exactly once and then adds`() = runComposeUiTest {
        runBlocking { settings.setPin("1234") }
        val kit = profiles("Asha Rao", appPin = "1234")
        window()
        cell(L.navAccountNamed("Asha Rao")).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        row(L.profilesAdd).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) { pads() > 0 }
        assertEquals("one PIN pad, not two", 1, pads())
        for (d in "1234") row(d.toString()).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        waitUntil(timeoutMillis = 15_000) { kit.book.list.value.profiles.size == 2 }
        waitForIdle()
        assertEquals("no second PIN after the first", 0, pads())
    }

    @Test
    fun `with no app PIN, Add account adds at once`() = runComposeUiTest {
        val kit = profiles("Asha Rao")
        window()
        cell(L.navAccountNamed("Asha Rao")).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        row(L.profilesAdd).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        waitUntil(timeoutMillis = 15_000) { kit.book.list.value.profiles.size == 2 }
        assertEquals(0, pads())
    }
}
