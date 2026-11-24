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
import com.tmplayer.ui.components.VERSION_FOOTER_TAG
import com.tmplayer.ui.settings.SignOutHost
import com.tmplayer.ui.settings.rememberSignOutState
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
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
                onSwitchOrAdd = {}, onSignOut = { signOut.request() },
            )
            TMPlayerTheme {
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                    TouchNavHost(nav, chrome, "page") {
                        CompositionLocalProvider(LocalTouchPageKey provides "page") {
                            TouchPage(TouchPageSpec(current = TouchDestination.Home))
                            Box(Modifier.fillMaxSize()) { AccountButton(Modifier.align(Alignment.TopEnd)) }
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
    fun `the menu ends with the version, the build and the state, and a tap copies the support line`() {
        open()
        val footer = rule.onNodeWithTag(VERSION_FOOTER_TAG)
        footer.assertExists()
        rule.onNodeWithTextExists(L.aboutVersionBuild(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toString()) + ", " + L.updateStateUpToDate)
        // It is the last thing in the card: below Sign out.
        val signOut = rule.onNode(hasText(L.settingsSignOutConfirm) and hasClickAction()).fetchSemanticsNode().boundsInRoot
        assertTrue(footer.fetchSemanticsNode().boundsInRoot.top >= signOut.bottom - 1f)
        footer.performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        val expected = VersionLine.copyText(
            VersionFacts.info(RuntimeEnvironment.getApplication()).version,
            VersionFacts.info(RuntimeEnvironment.getApplication()).build,
            VersionFacts.line(RuntimeEnvironment.getApplication()),
        )
        assertEquals(expected, clipboardText())
        assertTrue(expected.startsWith("TMPlayer ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) Android "))
        // No account data in the text.
        assertFalse(expected.contains("Asha", ignoreCase = true))
        assertFalse(expected.contains("asha"))
        // The confirmation is said once, in a polite live region.
        val copied = rule.onNodeWithTag(VERSION_FOOTER_TAG)
        assertEquals(LiveRegionMode.Polite, copied.fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion))
        rule.onNodeWithTextExists(L.aboutVersionCopied)
        // A second tap inside the two seconds copies again; the words stay the same.
        copied.performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onNodeWithTextExists(L.aboutVersionCopied)
        assertEquals(expected, clipboardText())
    }

    @Test
    fun `a build with no update check says updates come from where it was installed`() {
        Updates.enabled = false
        open()
        rule.onNodeWithTextExists(
            L.aboutVersionBuild(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toString()) + ", " + L.updateStateOff,
        )
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
                onSwitchOrAdd = {}, onSignOut = { signOut.request() },
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
    fun `Down from the last row reaches the footer, OK copies it and says so, Up returns`() {
        open()
        val footer = rule.onNodeWithTag(VERSION_FOOTER_TAG)
        footer.assertExists()
        // The remote starts on the header's button; Down walks the list to its last row.
        // Favourites, History, Watched, Watch later, Settings: five Downs from the header's button.
        repeat(5) {
            rule.onNode(hasText(L.profilesAdd) and hasClickAction()).performKeyInput { pressKey(Key.DirectionDown) }
            rule.waitForIdle()
        }
        settingsRow().assertIsFocused()
        settingsRow().performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        footer.assertIsFocused()
        // OK copies, and the same node now carries the confirmation in a polite live region.
        footer.performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        rule.onNodeWithTag(VERSION_FOOTER_TAG).assertIsFocused()
        assertEquals(
            LiveRegionMode.Polite,
            rule.onNodeWithTag(VERSION_FOOTER_TAG).fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion),
        )
        assertTrue(rule.onAllNodes(hasText(L.aboutVersionCopied)).fetchSemanticsNodes().isNotEmpty())
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        assertNotNull(text)
        assertTrue(text!!.startsWith("TMPlayer ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) Android "))
        assertFalse(text.contains("Asha"))
        // Up goes back to the last row.
        rule.onNodeWithTag(VERSION_FOOTER_TAG).performKeyInput { pressKey(Key.DirectionUp) }
        rule.waitForIdle()
        settingsRow().assertIsFocused()
        // Down twice: the footer, then on past it (to Close), never stuck.
        settingsRow().performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithTag(VERSION_FOOTER_TAG).performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithTag(VERSION_FOOTER_TAG).assertIsNotFocused()
    }
}
