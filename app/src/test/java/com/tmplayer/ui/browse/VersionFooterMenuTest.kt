package com.tmplayer.ui.browse

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import com.tmplayer.BuildConfig
import com.tmplayer.data.Account
import com.tmplayer.data.FormFactor
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.Updates
import com.tmplayer.data.VersionFacts
import com.tmplayer.data.VersionLine
import com.tmplayer.data.WatchedStore
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.components.VERSION_MARK_TAG
import com.tmplayer.ui.settings.SignOutHost
import com.tmplayer.ui.settings.rememberSignOutState
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The version line in the account menu on the phone (WP-104): the footer is the menu's last line,
 * a tap copies "TMPlayer <version> (<build>) <device>" with no account data in it, and the
 * confirmation is a polite live region. The preview build's and a pre-release's suffixes are shown
 * as built.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
class VersionFooterPhoneMenuTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val drain = DrainMainLooperRule()

    private val asha = Account(name = "Asha Rao", username = "asha", miniThumbnail = null, photoFileId = 0)
    private var aboutOpened = 0

    @Before
    fun setUp() {
        Updates.enabled = true
        Updates.noteAnswered(1L)
    }

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
        Updates.enabled = true
    }

    private fun open() {
        FormFactor.override(false)
        Motion.reduced = true
        val chrome = TouchChrome()
        val app = RuntimeEnvironment.getApplication()
        rule.setContent {
            val signOut = rememberSignOutState()
            val nav = TouchNav(
                account = asha, favoriteCount = 0, downloadCount = 0, updateVersion = null,
                onHome = {}, onChats = {}, onSearch = {}, onSettings = {},
                onFavourites = {}, onDownloads = {}, onHistory = {}, onUpdate = {},
                onSwitchOrAdd = {}, onSignOut = { signOut.request() }, onAbout = { aboutOpened++ },
            )
            TMPlayerTheme {
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                    TouchNavHost(nav, chrome, "page") {
                        CompositionLocalProvider(LocalTouchPageKey provides "page") {
                            TouchPage(TouchPageSpec(current = TouchDestination.Home))
                            Box(Modifier.fillMaxSize())
                        }
                    }
                }
                SignOutHost(
                    signOut, name = "Asha Rao", settings = SettingsStore(app), watchedStore = WatchedStore(app),
                    onLoggedOut = {}, settingsUnlocked = true, severalProfiles = false,
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription(L.navAccountNamed("Asha Rao")).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
    }

    private fun clipboardText(): String? {
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return clipboard.primaryClip?.getItemAt(0)?.text?.toString()
    }

    @Test
    fun `the menu ends with the compact version, the app's tile and the number, and a tap goes to About`() {
        open()
        val mark = rule.onNodeWithTag(VERSION_MARK_TAG)
        mark.assertExists()
        rule.onNodeWithTag(com.tmplayer.ui.components.VERSION_MARK_ICON_TAG, useUnmergedTree = true).assertExists()
        val compact = VersionLine.compact(BuildConfig.VERSION_NAME)
        assertTrue("\"v1.28.7\"-style, no -test or build suffix: $compact", Regex("v\\d+\\.\\d+\\.\\d+").matches(compact))
        rule.onNodeWithTextExists(compact)
        assertTrue("no Up to date in the compact row", rule.onAllNodes(hasText(L.updateStateUpToDate, substring = true)).fetchSemanticsNodes().isEmpty())
        // The long sentence is gone from the menu: the build and the source are About's.
        assertFalse(rule.onAllNodes(hasText(BuildConfig.VERSION_CODE.toString(), substring = true)).fetchSemanticsNodes().isNotEmpty())
        // It is the last thing in the sheet: below Settings.
        val settings = rule.onNode(hasText(L.navSettings) and hasClickAction()).fetchSemanticsNode().boundsInRoot
        assertTrue(mark.fetchSemanticsNode().boundsInRoot.top >= settings.bottom - 1f)
        // A 48 dp target, and a screen reader hears the name, the number and the state.
        assertTrue(mark.fetchSemanticsNode().boundsInRoot.height >= 48f * rule.activity.resources.displayMetrics.density - 1f)
        assertEquals(
            "TMPlayer $compact",
            mark.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull(),
        )
        mark.performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(1, aboutOpened)
    }

    @Test
    fun `the compact row is one centred group with no Updates come from sentence`() {
        open()
        val mark = rule.onNodeWithTag(VERSION_MARK_TAG).fetchSemanticsNode().boundsInRoot
        val row = rule.onNodeWithTag(com.tmplayer.ui.components.VERSION_MARK_ROW_TAG).fetchSemanticsNode().boundsInRoot
        val density = rule.activity.resources.displayMetrics.density
        assertEquals("centre of the group is the row's centre", row.center.x, mark.center.x, density)
        assertTrue(rule.onAllNodes(hasText("Updates come from", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a build with no update check shows the number alone, never where updates come from`() {
        Updates.enabled = false
        open()
        // The compact row is icon and number on one line; the update source is About's sentence (user, 2026-11-25).
        rule.onNodeWithTextExists(VersionLine.compact(BuildConfig.VERSION_NAME))
        assertTrue(rule.onAllNodes(hasText(L.updateStateOff, substring = true)).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodes(hasText("Updates come from", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onNodeWithTextExists(text: String) {
        assertTrue("\"$text\" is on screen", onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty())
    }
}

/**
 * The version line in the television's account menu (WP-104): Down from the last row reaches the
 * footer, OK copies and the spoken confirmation follows, Up returns, and Down leaves it for Close.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class VersionFooterTvMenuTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val drain = DrainMainLooperRule()

    private var about = 0
    private val homeFocus = FocusRequester()
    private val asha = Account(name = "Asha Rao", username = "asha", miniThumbnail = null, photoFileId = 0)

    @Before
    fun setUp() {
        Updates.enabled = true
        Updates.noteAnswered(1L)
    }

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
    }

    private fun open() {
        FormFactor.override(true)
        Motion.reduced = true
        val app = RuntimeEnvironment.getApplication()
        rule.setContent {
            LocalInputModeManager.current.requestInputMode(InputMode.Keyboard)
            val signOut = rememberSignOutState()
            val nav = TouchNav(
                account = asha, favoriteCount = 0, downloadCount = 0, updateVersion = null,
                onHome = {}, onChats = {}, onSearch = {}, onSettings = {},
                onFavourites = {}, onDownloads = {}, onHistory = {}, onUpdate = {},
                onSwitchOrAdd = {}, onSignOut = { signOut.request() }, onAbout = { about++ },
            )
            TMPlayerTheme {
                Column(Modifier.fillMaxSize().background(Tone.background)) {
                    TvTopBar(
                        nav = nav, current = TvBarTab.Home, inProfile = false, searchOpen = false,
                        onHome = {}, onChats = {}, onSearch = {}, onRefresh = null,
                        currentFocus = homeFocus, contentFocus = FocusRequester(), overArt = false,
                    )
                }
                SignOutHost(
                    signOut, name = "Asha Rao", settings = SettingsStore(app), watchedStore = WatchedStore(app),
                    onLoggedOut = {}, settingsUnlocked = true, severalProfiles = false,
                )
            }
        }
        val viewRoot = rule.activity.window.decorView.parent
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "ensureTouchMode",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            viewRoot, "windowFocusChanged",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true),
        )
        rule.waitForIdle()
        rule.runOnIdle { homeFocus.requestFocus() }
        rule.waitForIdle()
        repeat(2) {
            rule.onNode(androidx.compose.ui.test.isFocused()).performKeyInput { pressKey(Key.DirectionLeft) }
            rule.waitForIdle()
        }
        rule.onNodeWithContentDescription("Asha Rao, @asha", substring = true).performKeyInput { pressKey(Key.Menu) }
        rule.waitForIdle()
        remoteMode()
    }

    /** Every window out of touch mode, the newest one holding focus, as under a remote: the menu is a window of its own. */
    private fun remoteMode() {
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

    private fun settingsRow() = rule.onNode(hasText(L.navSettings) and hasClickAction())

    @Test
    fun `Down from the last row reaches the version, the last stop, and OK opens About without copying`() {
        // The TV's account sheet (WP-02): its version is a row of its own, the last stop, and a press opens
        // About; the line to copy for support lives on About. TvAccountMenuTest walks the whole column.
        open()
        val footer = rule.onNodeWithTag(VERSION_MARK_TAG, useUnmergedTree = true)
        footer.assertExists()
        // The sheet opens on its first row: Favourites, History, Watch later, Settings, the account row, then the version.
        for (id in listOf("favourites", "history", "watch-later", "settings", "switch")) {
            rule.onNodeWithTag("tv-account-row-$id").performKeyInput { pressKey(Key.DirectionDown) }
            rule.waitForIdle()
        }
        footer.assertIsFocused()
        // Down at the last stop stays on it.
        footer.performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        footer.assertIsFocused()
        assertEquals(0, about)
        footer.performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals("OK on the version asks for About", 1, about)
        assertTrue("nothing said it was copied", rule.onAllNodes(hasText(L.aboutVersionCopied)).fetchSemanticsNodes().isEmpty())
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertNull("nothing was copied", clipboard.primaryClip)
    }
}
