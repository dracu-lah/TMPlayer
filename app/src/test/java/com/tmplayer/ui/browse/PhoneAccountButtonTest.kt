package com.tmplayer.ui.browse

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.tmplayer.data.Account
import com.tmplayer.data.FormFactor
import com.tmplayer.data.HintIds
import com.tmplayer.data.Profile
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.WatchedStore
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.settings.SignOutHost
import com.tmplayer.ui.settings.SignOutState
import com.tmplayer.ui.settings.rememberSignOutState
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

/**
 * The phone's and tablet's account picture (WP-103), where the gear was: what it draws in each case
 * (Telegram's photo, the saved photo, the letter, the glyph, never an empty disc), what TalkBack
 * hears, the account menu it opens (whose account it is, Add account or Switch profile, Settings,
 * Refresh where the page has one, Sign out through the shell's host), a profile switch redrawing
 * the picture, the keyboard (Tab, Enter, Down, Escape), the app PIN before Sign out and Add account,
 * and the one-time hint for an install that had the gear.
 *
 * Left to the device: TalkBack's own reading of the click label on the POCO, VoiceOver on an iPhone.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
class PhoneAccountButtonTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private companion object {
        const val PIN = "1234"
    }

    private val calls = mutableListOf<String>()
    private var surfaceHigh = Color.Unspecified
    private var ring = Color.Unspecified
    private var ground = Color.Unspecified
    private val start = FocusRequester()
    private val settings get() = SettingsStore(RuntimeEnvironment.getApplication())
    private val asha = Account(name = "Asha Rao", username = "asha", miniThumbnail = null, photoFileId = 0)

    private var kit: com.tmplayer.ui.profiles.ProfileKit? = null
    private val prompt = mutableStateOf(false)

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
        kit?.close()
    }

    private val red = 0xFFD02020.toInt()

    private fun pictureBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(red)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun savedPhoto(): String =
        File.createTempFile("saved-photo", ".png").also { it.deleteOnExit(); it.writeBytes(pictureBytes()) }.path

    private lateinit var signOut: SignOutState

    private fun show(
        account: Account? = asha,
        profile: MutableState<Profile?> = mutableStateOf(null),
        update: String? = null,
        several: Boolean = false,
        refresh: Boolean = false,
        keyboard: Boolean = false,
        pinGate: Boolean = false,
        hint: Boolean = false,
        shown: MutableState<Boolean> = mutableStateOf(true),
        /** The real profile flow, drawn by its gate as in the app; null records the press instead. */
        flow: com.tmplayer.ui.lock.ProfileFlow? = null,
        promptUp: Boolean = false,
        /** Starred, watching and watched, the counts the tiles carry. */
        counts: Triple<Int, Int, Int> = Triple(0, 0, 0),
        prompt: MutableState<Boolean> = this.prompt,
    ): TouchChrome {
        FormFactor.override(false)
        Motion.reduced = true
        val chrome = TouchChrome()
        val app = RuntimeEnvironment.getApplication()
        rule.setContent {
            if (keyboard) LocalInputModeManager.current.requestInputMode(InputMode.Keyboard)
            signOut = rememberSignOutState()
            val state = signOut
            val nav = TouchNav(
                account = account, favoriteCount = counts.first, watchingCount = counts.second, watchedCount = counts.third, downloadCount = 0, updateVersion = update,
                onHome = {}, onChats = {}, onSearch = {}, onSettings = { calls += "settings" },
                onFavourites = { calls += "favourites" }, onDownloads = {}, onHistory = { calls += "history:$it" }, onUpdate = {},
                onSwitchProfile = if (several) ({ calls += "switch" }) else null,
                // As the shell wires it (MainActivity): Add account through the profile flow, which asks the PIN.
                onSwitchOrAdd = if (flow != null) ({ switchOrAdd(flow, several) }) else ({ calls += "picker" }),
                onSignOut = { calls += "signOut"; state.request() },
                signOutPhase = state.phase,
                profile = profile.value,
                promptUp = promptUp || prompt.value,
            )
            TMPlayerTheme {
                surfaceHigh = Tone.surfaceHigh
                ring = FloatingTone.menu
                ground = Tone.background
                Box(Modifier.fillMaxSize().background(Tone.background)) {
                  com.tmplayer.ui.lock.ProfilesGate(flow, signInStep = false) {
                    TouchNavHost(nav, chrome, "page") {
                        CompositionLocalProvider(LocalTouchPageKey provides "page") {
                            TouchPage(TouchPageSpec(current = if (shown.value) TouchDestination.Home else TouchDestination.Chats, refresh = if (refresh) ({ calls += "refresh" }) else null))
                            Box(Modifier.fillMaxSize()) {
                                // Something before the picture for Tab to start from.
                                Box(Modifier.size(10.dp).focusRequester(start).focusable().testTag("start"))
                            }
                        }
                    }
                  }
                }
                SignOutHost(
                    state, name = account?.name ?: profile.value?.shownName, settings = SettingsStore(app),
                    watchedStore = WatchedStore(app), onLoggedOut = {},
                    settingsUnlocked = !pinGate, severalProfiles = several,
                )
            }
        }
        rule.waitForIdle()
        return chrome
    }

    private fun button(name: String) = rule.onNodeWithContentDescription(name)

    private fun openMenu(name: String = L.navAccountNamed("Asha Rao")) {
        button(name).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
    }

    private fun row(label: String) = rule.onNode(hasText(label) and hasClickAction())

    private fun exists(text: String) =
        rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private val density get() = rule.activity.resources.displayMetrics.density

    private fun close(a: Int, b: Int, tolerance: Int = 12): Boolean =
        abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) <= tolerance &&
            abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) <= tolerance &&
            abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)) <= tolerance

    /** The 40 dp picture's centre, and a pixel left of where a letter is drawn. */
    private fun pixels(bitmap: Bitmap): Pair<Int, Int> {
        val b = rule.onNodeWithTag(ACCOUNT_PICTURE_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val cx = b.center.x
        val cy = b.center.y
        return bitmap.getPixel(cx.toInt(), cy.toInt()) to bitmap.getPixel((cx - 15f * density).toInt(), cy.toInt())
    }

    private fun waitFor(want: (Bitmap) -> Boolean): Bitmap {
        var bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        var tries = 0
        while (!want(bitmap) && tries++ < 100) {
            Thread.sleep(50)
            rule.waitForIdle()
            bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        }
        return bitmap
    }

    // (a) What it draws ---------------------------------------------------------------------

    @Test
    fun `Telegram's photo is drawn when the account has one`() {
        show(account = asha.copy(miniThumbnail = pictureBytes()))
        val bitmap = waitFor { close(pixels(it).first, red) }
        assertTrue("the centre is Telegram's photo", close(pixels(bitmap).first, red))
    }

    @Test
    fun `the profile's saved photo is drawn while Telegram has not answered`() {
        show(account = null, profile = mutableStateOf(Profile(id = "a", folder = null, name = "Maya Lee", photo = savedPhoto())))
        val bitmap = waitFor { close(pixels(it).first, red) }
        assertTrue("the centre is the saved photo", close(pixels(bitmap).first, red))
        button(L.navAccountNamed("Maya Lee")).assertExists()
    }

    @Test
    fun `a name alone draws its letter on the profile colour, never the bare disc, also when the photo file is gone`() {
        show(account = null, profile = mutableStateOf(Profile(id = "a", folder = null, name = "Maya Lee", photo = "/gone/photo.png")))
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val (_, side) = pixels(bitmap)
        assertNotEquals("a coloured disc, not surfaceHigh", surfaceHigh.toArgb(), side)
        assertFalse("not the bar's own colour either", close(side, ring.toArgb(), 4))
    }

    @Test
    fun `nothing known draws the person glyph and says Account`() {
        show(account = null)
        button(L.navAccount).assertExists()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val b = rule.onNodeWithTag(ACCOUNT_PICTURE_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        // The glyph is drawn: some pixels of the disc are not its ground.
        var drawn = 0
        for (x in b.left.toInt() until b.right.toInt() step 2) {
            for (y in b.top.toInt() until b.bottom.toInt() step 2) {
                if (!close(bitmap.getPixel(x, y), surfaceHigh.toArgb(), 20) && !close(bitmap.getPixel(x, y), ground.toArgb(), 20)) drawn++
            }
        }
        assertTrue("the glyph is drawn, not an empty disc ($drawn)", drawn > 10)
    }

    // (b) What TalkBack hears ---------------------------------------------------------------

    @Test
    fun `the picture says whose account it is and what a tap does, and that an update waits`() {
        show(update = "1.30.0")
        val node = button("${L.navAccountNamed("Asha Rao")}, ${L.navUpdateWaiting}").fetchSemanticsNode()
        assertEquals(L.navAccountMenu, node.config.getOrNull(SemanticsActions.OnClick)?.label)
        rule.onNodeWithTag(ACCOUNT_DOT_TAG, useUnmergedTree = true).assertExists()
        // No gear and no "You" anywhere.
        assertTrue(rule.onAllNodesWithContentDescription(L.navYou).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithContentDescription(L.navSettings).fetchSemanticsNodes().isEmpty())
    }

    // (c) The menu ---------------------------------------------------------------------------

    @Test
    fun `the menu is a header, three tiles, two rows and the version, with no strip of numbers and no Sign out`() {
        val chrome = show(update = "1.30.0")
        openMenu(L.navAccountNamed("Asha Rao") + ", " + L.navUpdateWaiting)
        assertTrue(chrome.youOpen)
        assertTrue(exists("Asha Rao"))
        assertTrue(exists("@asha"))
        assertTrue(exists(L.profilesAdd))
        assertFalse(exists(L.profilesSwitchOrAdd))
        for (tile in listOf("favourites", "history", "watch-later")) {
            rule.onNodeWithTag("${com.tmplayer.ui.components.LIBRARY_TILE_TAG}-$tile", useUnmergedTree = true).assertExists()
        }
        // The old block of three bare numbers is gone, and so are the rows the tiles replaced.
        assertFalse(exists(L.profileHeading))
        assertFalse(exists(L.profileStarred))
        assertFalse("no Sign out here, it lives in Settings, Account", exists(L.settingsSignOutConfirm))
        assertFalse("no Refresh row where the page has none", exists(L.commonRefresh))
        // The version, compact: the tile and "v..." (the amber update button is VersionMark's own, tested there).
        rule.onNodeWithTag(com.tmplayer.ui.components.VERSION_MARK_TAG, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(com.tmplayer.ui.components.VERSION_MARK_ICON_TAG, useUnmergedTree = true).assertExists()
        row(L.navSettings).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(listOf("settings"), calls)
        assertFalse(chrome.youOpen)
    }

    @Test
    fun `the counts ride on the tiles they belong to, and a tile with none shows none`() {
        show(counts = Triple(7, 3, 12))
        openMenu()
        assertTrue(exists(L.navTileStarred("7")))
        assertTrue(exists(L.navTileWatching("3")))
        assertTrue(exists(L.navTileWatched("12")))
        rule.onNodeWithTag("${com.tmplayer.ui.components.LIBRARY_TILE_TAG}-watch-later", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `the tiles open Favourites, History and Watch later`() {
        show()
        openMenu()
        rule.onNodeWithTag("${com.tmplayer.ui.components.LIBRARY_TILE_TAG}-favourites", useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        openMenu()
        rule.onNodeWithTag("${com.tmplayer.ui.components.LIBRARY_TILE_TAG}-history", useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        openMenu()
        rule.onNodeWithTag("${com.tmplayer.ui.components.LIBRARY_TILE_TAG}-watch-later", useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(listOf("favourites", "history:Continue", "history:WatchLater"), calls)
    }

    @Test
    fun `two profiles read Switch or add account, and Refresh is the header's small button where the page has one`() {
        show(several = true, refresh = true)
        openMenu()
        assertTrue(exists(L.profilesSwitchOrAdd))
        assertFalse(exists(L.profilesAdd))
        assertFalse("no Refresh row, a header button", rule.onAllNodes(hasText(L.commonRefresh) and hasClickAction()).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithTag(REFRESH_BUTTON_TAG, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        openMenu()
        rule.onNodeWithTag(SWITCH_BUTTON_TAG, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        openMenu()
        row(L.profilesSwitchOrAdd).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        // The switcher asks the picker; the row is the shell's switch or add.
        assertEquals(listOf("refresh", "switch", "picker"), calls)
    }

    @Test
    fun `with one profile the header has no switcher, and without a refresh no Refresh button`() {
        show()
        openMenu()
        assertTrue(rule.onAllNodesWithTag(SWITCH_BUTTON_TAG, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithTag(REFRESH_BUTTON_TAG, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `Back closes the sheet before it leaves the page`() {
        val chrome = show()
        openMenu()
        assertTrue(chrome.youOpen)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertFalse(chrome.youOpen)
    }

    @Test
    fun `an app lock or a PIN pad coming up closes the sheet`() {
        val chrome = show()
        openMenu()
        assertTrue(chrome.youOpen)
        rule.runOnUiThread { prompt.value = true }
        rule.waitForIdle()
        assertFalse("a sheet never stays over the lock", chrome.youOpen)
    }

    @Test
    fun `Add account with no PIN set adds the profile through the profile flow, no PIN asked`() {
        val k = com.tmplayer.ui.profiles.ProfileKit("Asha Rao").also { kit = it }
        show(flow = k.flow())
        openMenu()
        row(L.profilesAdd).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.waitUntil(15_000) { k.book.list.value.profiles.size == 2 }
        assertFalse(exists(L.lockEnterPin))
    }

    @Test
    fun `Add account with one profile shows its line over the page while the open profile's work stops, then opens the new profile`() {
        val k = com.tmplayer.ui.profiles.ProfileKit("Asha Rao").also { kit = it }
        val hold = kotlinx.coroutines.CompletableDeferred<Unit>()
        k.platform.hold = hold
        val flow = k.flow()
        show(flow = flow)
        openMenu()
        row(L.profilesAdd).performSemanticsAction(SemanticsActions.OnClick)
        // Never a sheet that closes on nothing (user, 2026-11-25).
        rule.waitUntil(15_000) { exists(L.profilesWaiting(what = "a sync")) }
        hold.complete(Unit)
        rule.waitUntil(15_000) { k.book.list.value.profiles.size == 2 && !flow.adding }
        assertTrue(k.book.list.value.active?.adding == true)
    }

    // (d) A switch ---------------------------------------------------------------------------

    @Test
    fun `a switch to another profile redraws the picture and the name`() {
        val profile = mutableStateOf<Profile?>(Profile(id = "a", folder = null, name = "Asha Rao"))
        show(account = null, profile = profile)
        button(L.navAccountNamed("Asha Rao")).assertExists()
        rule.runOnUiThread { profile.value = Profile(id = "b", folder = "b", name = "Zed Okafor") }
        rule.waitForIdle()
        button(L.navAccountNamed("Zed Okafor")).assertExists()
        assertTrue(rule.onAllNodesWithContentDescription(L.navAccountNamed("Asha Rao")).fetchSemanticsNodes().isEmpty())
        openMenu(L.navAccountNamed("Zed Okafor"))
        assertTrue(exists("Zed Okafor"))
    }

    // (e) The keyboard -----------------------------------------------------------------------

    @Test
    fun `the keyboard reaches the picture, Enter opens the menu, Back closes it and gives the picture its focus back`() {
        val chrome = show(keyboard = true)
        rule.runOnIdle { chrome.accountFocus.requestFocus() }
        rule.waitForIdle()
        button(L.navAccountNamed("Asha Rao")).assertIsFocused()
        button(L.navAccountNamed("Asha Rao")).performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertTrue(chrome.youOpen)
        // The sheet is a window of its own, whose focus a test window does not grant: the first tile
        // taking the focus, and Escape in it, are the device's to check. Here the tiles are there, and Back closes it.
        rule.onNodeWithTag("${com.tmplayer.ui.components.LIBRARY_TILE_TAG}-favourites", useUnmergedTree = true).assertExists()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertFalse(chrome.youOpen)
        button(L.navAccountNamed("Asha Rao")).assertIsFocused()
    }

    // (f) The app PIN ------------------------------------------------------------------------

    /** How many PIN pads are up: each draws its title once. */
    private fun pads(): Int = listOf(L.lockEnterPin, L.lockEnterForSignOut, L.lockEnterForSettings)
        .sumOf { rule.onAllNodesWithText(it, useUnmergedTree = true).fetchSemanticsNodes().size }

    @Test
    fun `with an app PIN set, Add account asks the PIN exactly once, Cancel adds nothing and the right PIN adds`() {
        val k = com.tmplayer.ui.profiles.ProfileKit("Asha Rao").also { kit = it }
        k.platform.appPin = PIN
        // Settings shut, as on Home: the host's gate would ask too if it were in the way.
        runBlocking { settings.setPin(PIN) }
        show(flow = k.flow(), pinGate = true)
        openMenu()
        row(L.profilesAdd).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.waitUntil(5_000) { pads() > 0 }
        assertEquals("one PIN pad, not two", 1, pads())
        row(L.commonCancel).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(0, pads())
        assertEquals(1, k.book.list.value.profiles.size)
        openMenu()
        row(L.profilesAdd).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.waitUntil(5_000) { pads() > 0 }
        assertEquals("one PIN pad, not two", 1, pads())
        for (d in PIN) row(d.toString()).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.waitUntil(15_000) { k.book.list.value.profiles.size == 2 }
        rule.waitForIdle()
        assertEquals("no second PIN after the first", 0, pads())
    }

    // (g) The one-time hint ------------------------------------------------------------------

    @Test
    fun `an install that had the gear is told once where it went`() {
        runBlocking { settings.markTourDone() }
        val shown = mutableStateOf(true)
        show(hint = true, shown = shown)
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(ACCOUNT_HINT_TAG, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(exists(L.navAccountMovedHint))
        assertEquals(1, runBlocking { settings.hintCount(HintIds.NAV_ACCOUNT_MOVED) })
        // Home again later: not a second time.
        rule.runOnUiThread { shown.value = false }
        rule.waitForIdle()
        rule.runOnUiThread { shown.value = true }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithTag(ACCOUNT_HINT_TAG, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertEquals(1, runBlocking { settings.hintCount(HintIds.NAV_ACCOUNT_MOVED) })
    }

    @Test
    fun `the hint waits while a question, a PIN pad or the app lock is up`() {
        runBlocking { settings.markTourDone() }
        show(hint = true, promptUp = true)
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithTag(ACCOUNT_HINT_TAG, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertEquals("not counted while it could not show", 0, runBlocking { settings.hintCount(HintIds.NAV_ACCOUNT_MOVED) })
    }

    @Test
    fun `a fresh install never sees the hint`() {
        runBlocking {
            settings.armSetup()
            settings.markSetupDone()
            settings.markTourDone()
        }
        show(hint = true)
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithTag(ACCOUNT_HINT_TAG, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertEquals(1, runBlocking { settings.hintCount(HintIds.NAV_ACCOUNT_MOVED) })
    }
}
