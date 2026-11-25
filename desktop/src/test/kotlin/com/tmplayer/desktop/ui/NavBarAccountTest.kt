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
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.onRoot
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
    fun `the cell says whose account it is, and draws no name on the bar`() = runComposeUiTest {
        window()
        cell(L.navAccountNamed("Asha Rao")).assertExists()
        // A long name has no place on the bar (user, 2026-11-25): the picture alone, the name as its label.
        assertFalse("no first name under the picture", exists("Asha"))
        assertFalse("no whole name either", exists("Asha Rao"))
        assertFalse(exists(L.navYou))
        assertTrue(onAllNodes(hasContentDescription(L.navProfile)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a 60 character name keeps the bar's slots as wide as each other and draws no text for it`() = runComposeUiTest {
        val long = "Asha " + "Ramanathan".repeat(6).take(55)
        window(account = asha.copy(name = long))
        cell(L.navAccountNamed(long)).assertExists()
        assertFalse(exists(long))
        val places = listOf(L.browseTabHome, L.navChats, L.commonSearch, L.navDownloads, L.navAccountNamed(long))
        val widths = places.map { cell(it).fetchSemanticsNode().boundsInRoot.width }
        val heights = places.map { cell(it).fetchSemanticsNode().boundsInRoot.height }
        assertTrue("every slot is as wide as the others: $widths", widths.all { kotlin.math.abs(it - widths.first()) < 1f })
        assertTrue("and as high: $heights", heights.all { kotlin.math.abs(it - heights.first()) < 1f })
    }

    @Test
    fun `before Telegram answers the open profile's name is said, and with no name at all the plain word`() = runComposeUiTest {
        profiles("Maya Lee")
        window(account = null)
        cell(L.navAccountNamed("Maya Lee")).assertExists()
        assertFalse(exists("Maya"))
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
    fun `the menu is a header, three tiles, two rows and the version, with no Your viewing block and no Sign out`() = runComposeUiTest {
        profiles("Asha Rao")
        window()
        cell(L.navAccountNamed("Asha Rao")).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        assertTrue(shell.profileOpen)
        onNode(androidx.compose.ui.test.hasTestTag(NAV_MENU_HEAD_TAG), useUnmergedTree = true).assertExists()
        assertTrue(exists("@asha"))
        for (label in listOf(L.profilesAdd, L.navSettings, L.browseTabFavourites, L.browseTabHistory, L.browseTabWatchLater)) {
            row(label).assertExists()
        }
        onNode(androidx.compose.ui.test.hasTestTag(com.tmplayer.ui.components.LIBRARY_TILES_TAG), useUnmergedTree = true).assertExists()
        onNode(androidx.compose.ui.test.hasTestTag(com.tmplayer.ui.components.VERSION_MARK_TAG), useUnmergedTree = true).assertExists()
        // The compact row: one centred group, never the update source sentence (user, 2026-11-25).
        assertFalse(onAllNodes(hasText("Updates come from", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        val group = onNode(androidx.compose.ui.test.hasTestTag(com.tmplayer.ui.components.VERSION_MARK_TAG), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val holder = onNode(androidx.compose.ui.test.hasTestTag(com.tmplayer.ui.components.VERSION_MARK_ROW_TAG), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(holder.center.x, group.center.x, 1f)
        // The stray block of three bare numbers is gone, and so is Sign out (Settings, Account has it).
        assertFalse(exists(L.profileHeading))
        assertFalse(exists(L.profileStarred))
        assertFalse(exists(L.settingsSignOutConfirm))
        assertFalse(exists(L.profilesSwitchOrAdd))
        // Only a one profile device: no switcher button.
        assertTrue(onAllNodes(androidx.compose.ui.test.hasTestTag(NAV_MENU_SWITCH_TAG)).fetchSemanticsNodes().isEmpty())
        row(L.navSettings).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        assertEquals(Destination.Settings, shell.destination)
        assertFalse(shell.profileOpen)
    }

    @Test
    fun `two profiles read Switch or add account, and the header has the switcher`() = runComposeUiTest {
        profiles("Asha Rao", "Ben Ode")
        window()
        cell(L.navAccountNamed("Asha Rao")).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        row(L.profilesSwitchOrAdd).assertExists()
        assertFalse(exists(L.profilesAdd))
        onNode(androidx.compose.ui.test.hasTestTag(NAV_MENU_SWITCH_TAG)).assertExists()
    }

    @Test
    fun `Enter opens the menu on its first tile, Down walks it, Esc closes it onto the picture`() = runComposeUiTest {
        profiles("Asha Rao")
        window()
        cell(L.navAccountNamed("Asha Rao")).requestFocus()
        press(Key.Enter)
        assertTrue(shell.profileOpen)
        waitForIdle()
        val favourites = androidx.compose.ui.test.hasTestTag("${com.tmplayer.ui.components.LIBRARY_TILE_TAG}-favourites")
        onNode(favourites, useUnmergedTree = true).assertIsFocused()
        press(Key.DirectionDown)
        onNode(androidx.compose.ui.test.hasTestTag("${com.tmplayer.ui.components.LIBRARY_TILE_TAG}-history"), useUnmergedTree = true).assertIsFocused()
        press(Key.Escape)
        assertFalse(shell.profileOpen)
        cell(L.navAccountNamed("Asha Rao")).assertIsFocused()
    }

    private fun ComposeUiTest.shot(name: String) {
        val image = onRoot().captureToImage().toAwtImage()
        val file = java.io.File("../build/device-test/review/$name").absoluteFile
        file.parentFile?.mkdirs()
        javax.imageio.ImageIO.write(image, "png", file)
        assertTrue(file.length() > 0)
    }

    @Test
    fun `the popover and the bar with a 60 character name, as drawn`() = runComposeUiTest {
        profiles("Asha Rao", "Ben Ode")
        shell.refreshAction = {}
        val long = "Asha " + "Ramanathan".repeat(6).take(55)
        window(account = asha.copy(name = long), update = NavUpdate("Update", "1.30.0"))
        shot("desktop-bar-long-name.png")
        cell("${L.navAccountNamed(long)}, ${L.navUpdateWaiting}").performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        // Inside the window on both sides, and clear of the picture it opened from.
        val menu = onNode(androidx.compose.ui.test.hasTestTag(NAV_MENU_TAG), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val picture = cell("${L.navAccountNamed(long)}, ${L.navUpdateWaiting}").fetchSemanticsNode().boundsInRoot
        assertTrue("inside the window: $menu", menu.left >= 0f && menu.right <= 1280f * 1f + 1f && menu.top >= 0f)
        assertTrue("clear of the picture", menu.bottom <= picture.top + 1f || menu.top >= picture.bottom - 1f)
        shot("desktop-account-popover.png")
    }

    @Test
    fun `Refresh is the header's own small button where the page has one, with no row for it`() = runComposeUiTest {
        profiles("Asha Rao")
        var refreshed = 0
        shell.refreshAction = { refreshed++ }
        window()
        cell(L.navAccountNamed("Asha Rao")).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        assertTrue(onAllNodes(hasText(L.commonRefresh) and hasClickAction()).fetchSemanticsNodes().isEmpty())
        onNode(androidx.compose.ui.test.hasTestTag(NAV_MENU_REFRESH_TAG)).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        assertEquals(1, refreshed)
        assertFalse(shell.profileOpen)
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
