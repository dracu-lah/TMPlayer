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
import androidx.compose.ui.test.assertIsNotFocused
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
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.tmplayer.ui.theme.Tv
import kotlin.math.abs
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
        com.tmplayer.data.SettingsUnlock.lock()
    }

    /**
     * [realGate]: the host reads Settings' gate as the shell has it ([com.tmplayer.data.SettingsUnlock]),
     * instead of being told it is shut whenever a PIN is set.
     */
    private fun open(
        several: Boolean = false,
        tiles: Boolean = false,
        pin: Boolean = false,
        realGate: Boolean = false,
        refresh: Boolean = false,
        counts: Triple<Int, Int, Int> = Triple(0, 0, 0),
    ) {
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
                account = asha, favoriteCount = counts.first, watchingCount = counts.second, watchedCount = counts.third, downloadCount = 0, updateVersion = null,
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
                        onHome = {}, onChats = {}, onSearch = {}, onRefresh = if (refresh) ({ calls += "refresh" }) else null,
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
                    // Settings, Account has the Sign out button (the sheet has none): this stands in for it,
                    // so the host's own flows (the PIN first, the question, the backup, the lock) stay tested.
                    androidx.compose.material3.Text(
                        L.settingsSignOutConfirm,
                        Modifier.clickable { signOut.request() }.testTag("settings-sign-out-standin"),
                    )
                }
                if (realGate) {
                    SignOutHost(
                        signOut, name = "Asha Rao", settings = settings, watchedStore = watched, onLoggedOut = { calls += "signedOut" },
                        severalProfiles = several, appLocked = appLocked.value,
                    )
                } else {
                    SignOutHost(
                        signOut, name = "Asha Rao", settings = settings, watchedStore = watched, onLoggedOut = { calls += "signedOut" },
                        settingsUnlocked = !pin, severalProfiles = several, appLocked = appLocked.value,
                    )
                }
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

    private fun menuOpen() = rule.onAllNodes(hasTestTag(TV_ACCOUNT_SHEET_TAG)).fetchSemanticsNodes().isNotEmpty()

    private fun row(id: String) = rule.onNodeWithTag("$TV_ACCOUNT_ROW_TAG-$id", useUnmergedTree = true)

    private fun openMenu() {
        pictureOnBar().performKeyInput { pressKey(Key.Menu) }
        rule.waitForIdle()
        settleSheet()
    }

    /**
     * The sheet is a window of its own: under a remote it is out of touch mode like every other window, and its
     * first row has the remote. (On the device it opens so; a test window opens in touch mode, where nothing takes focus.)
     */
    private fun settleSheet() {
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
        if (menuOpen()) {
            row("favourites").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
            rule.waitForIdle()
        }
    }

    @Test
    fun `Menu in the bar opens the sheet on Favourites, Down walks every row to the version and stops, Up walks back and stops, nothing wraps`() {
        open(several = true)
        openMenu()
        assertTrue(menuOpen())
        // The rows of the sheet in the order the remote walks them, then the version (an update row only when there is an update).
        val ids = listOf("favourites", "history", "watch-later", "settings", "switch")
        val version = rule.onNodeWithTag(com.tmplayer.ui.components.VERSION_MARK_TAG, useUnmergedTree = true)
        row("favourites").assertIsFocused()
        // Up at the top stops: the first row keeps the remote.
        row("favourites").performKeyInput { pressKey(Key.DirectionUp) }
        rule.waitForIdle()
        row("favourites").assertIsFocused()
        for ((a, b) in ids.zipWithNext()) {
            row(a).performKeyInput { pressKey(Key.DirectionDown) }
            rule.waitForIdle()
            row(b).assertIsFocused()
        }
        // One more Down: the version, the last stop; and one more again: nowhere, it does not wrap.
        row(ids.last()).performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        version.assertIsFocused()
        version.performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        assertTrue("still in the sheet", menuOpen())
        version.assertIsFocused()
        // Back up the whole column.
        version.performKeyInput { pressKey(Key.DirectionUp) }
        rule.waitForIdle()
        row(ids.last()).assertIsFocused()
        for ((a, b) in ids.zipWithNext().reversed()) {
            row(b).performKeyInput { pressKey(Key.DirectionUp) }
            rule.waitForIdle()
            row(a).assertIsFocused()
        }
    }

    @Test
    fun `with Refresh in the sheet the remote walks to it after Add account, then the version, and OK on it refreshes`() {
        open(several = false, refresh = true)
        openMenu()
        val ids = listOf("favourites", "history", "watch-later", "settings", "switch", "refresh")
        row("favourites").assertIsFocused()
        for ((a, b) in ids.zipWithNext()) {
            row(a).performKeyInput { pressKey(Key.DirectionDown) }
            rule.waitForIdle()
            row(b).assertIsFocused()
        }
        // One focus indicator: only Refresh holds the remote, no other row of the sheet.
        for (id in ids.dropLast(1)) row(id).assertIsNotFocused()
        // Refresh sits after the account row, below Settings in the second group.
        val settings = row("settings").fetchSemanticsNode().boundsInRoot
        val refresh = row("refresh").fetchSemanticsNode().boundsInRoot
        assertTrue("Refresh is below Settings", refresh.top > settings.bottom - 1f)
        row("refresh").performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertTrue("Refresh asked the page", "refresh" in calls)
    }

    @Test
    fun `the version row is one line, the number and a short state, never where updates come from`() {
        com.tmplayer.data.Updates.enabled = false
        try {
            open(several = false, refresh = true)
            openMenu()
            val density = rule.activity.resources.displayMetrics.density
            val mark = rule.onNodeWithTag(com.tmplayer.ui.components.VERSION_MARK_TAG, useUnmergedTree = true)
            val bounds = mark.fetchSemanticsNode().boundsInRoot
            // 48 dp row height with padding: one line of 14 sp text stays far under two lines (56 dp).
            assertTrue("one line, ${bounds.height / density} dp tall", bounds.height <= 56f * density + 1f)
            assertTrue("no update source sentence", rule.onAllNodes(hasText("Updates come from", substring = true)).fetchSemanticsNodes().isEmpty())
            assertTrue("no update source in the description", rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Updates come from", substring = true)).fetchSemanticsNodes().isEmpty())
            val sheet = rule.onNodeWithTag(TV_ACCOUNT_SHEET_TAG).fetchSemanticsNode().boundsInRoot
            assertTrue("the row fits the sheet's width", bounds.right <= sheet.right)
            // Icon and number are one group, centred in the row the sheet gives it (within 1 dp).
            val holder = rule.onNodeWithTag(com.tmplayer.ui.components.VERSION_MARK_ROW_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals("centred", holder.center.x, bounds.center.x, density)
        } finally {
            com.tmplayer.data.Updates.enabled = true
        }
    }

    @Test
    fun `no row is missing, Favourites, History, Watch later, Settings, the account row, and Refresh where the page has one`() {
        open(several = false, refresh = true)
        openMenu()
        for (id in listOf("favourites", "history", "watch-later", "settings", "switch", "refresh")) row(id).assertExists()
        // Sign out is in Settings, Account, as on the phone; no Sign out, no strip of numbers, no first-name chip.
        assertTrue(rule.onAllNodes(hasText(L.settingsSignOutConfirm) and hasClickAction() and hasAnyAncestor(hasTestTag(TV_ACCOUNT_SHEET_TAG))).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodes(hasText(L.profileHeading)).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag(TV_ACCOUNT_VERSION_TAG, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(com.tmplayer.ui.components.VERSION_MARK_TAG, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `Menu closes the sheet, and the remote is on the picture again (Back is the dialog's own, checked on the device)`() {
        open(several = true)
        openMenu()
        assertTrue(menuOpen())
        row("favourites").performKeyInput { pressKey(Key.Menu) }
        rule.waitForIdle()
        rule.waitUntil(5_000) { !menuOpen() }
        rule.onNodeWithContentDescription("Asha Rao, @asha", substring = true).assertIsFocused()
    }

    @Test
    fun `with one profile the row reads Add account and opens the picker`() {
        open(several = false)
        openMenu()
        rule.onAllNodes(hasText(L.profilesSwitchOrAdd)).assertCountIs(0)
        rule.onNodeWithText(L.profilesAdd).assertExists()
        row("switch").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(listOf("add"), calls)
        rule.waitUntil(5_000) { !menuOpen() }
    }

    @Test
    fun `with two profiles the row reads Switch or add account`() {
        open(several = true)
        openMenu()
        rule.onNodeWithText(L.profilesSwitchOrAdd).assertExists()
        rule.onAllNodes(hasText(L.profilesAdd)).assertCountIs(0)
        row("switch").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(listOf("switch"), calls)
    }

    @Test
    fun `the counts ride on the rows they belong to`() {
        open(several = false, counts = Triple(7, 3, 12))
        openMenu()
        rule.onNodeWithText(L.navTileStarred("7")).assertExists()
        rule.onNodeWithText(L.navTileWatching("3") + "  ·  " + L.navTileWatched("12")).assertExists()
    }

    @Test
    fun `OK on the picture opens the sheet`() {
        open(several = true)
        pictureOnBar().assertIsFocused()
        rule.onNodeWithContentDescription("Asha Rao, @asha", substring = true).performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertTrue("a short OK", menuOpen())
        settleSheet()
        row("favourites").assertIsFocused()
    }

    @Test
    fun `a long OK on the picture opens the same sheet`() {
        open(several = true)
        pictureOnBar().performKeyInput { pressKey(Key.Enter, 1_500) }
        rule.waitForIdle()
        assertTrue("a long OK", menuOpen())
        settleSheet()
        row("favourites").assertIsFocused()
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
        settleSheet()
        row("favourites").assertIsFocused()
    }

    /** The sheet as drawn: inside the safe area, rows of one height on one left edge, at 960 x 540 and 1920 x 1080. */
    private fun checkSheet(shot: String) {
        val density = rule.activity.resources.displayMetrics.density
        val root = rule.onAllNodes(androidx.compose.ui.test.isRoot())[0].fetchSemanticsNode().boundsInRoot
        val sheet = rule.onNodeWithTag(TV_ACCOUNT_SHEET_TAG).fetchSemanticsNode().boundsInRoot
        val widthDp = root.width / density
        assertEquals("a width from the screen's: ${sheet.width / density} dp", tvSheetWidth(androidx.compose.ui.unit.Dp(widthDp)).value, sheet.width / density, 1.5f)
        assertTrue("flush to the right edge", abs(sheet.right - root.right) < 1.5f)
        assertTrue("full height", sheet.height >= root.height - 1.5f)
        val ids = listOf("favourites", "history", "watch-later", "settings", "switch", "refresh")
        val rows = ids.map { row(it).fetchSemanticsNode().boundsInRoot }
        val safeTop = Tv.SafeV.value * density
        val safeBottom = root.bottom - Tv.SafeV.value * density
        val safeRight = root.right - Tv.SafeH.value * density
        for ((id, b) in ids.zip(rows)) {
            assertTrue("$id is inside the safe area ($b)", b.top >= safeTop - 1f && b.bottom <= safeBottom + 1f && b.right <= safeRight + 1f)
            assertEquals("$id is 56 dp high", TV_SHEET_ROW.value * density, b.height, 1.5f)
        }
        assertTrue("rows on one left edge: ${rows.map { it.left }}", rows.all { abs(it.left - rows.first().left) < 1f })
        assertTrue("rows one width: ${rows.map { it.width }}", rows.all { abs(it.width - rows.first().width) < 1f })
        val version = rule.onNodeWithTag(TV_ACCOUNT_VERSION_TAG, useUnmergedTree = true).fetchSemanticsNode().positionInRoot
        // At 540 dp the column scrolls, so the version (the last stop) is reached by the remote, not drawn in the safe area at rest.
        assertTrue("the version is on the rows left edge", kotlin.math.abs(version.x - rows.first().left) < 1.5f)
        // Frames: the page, and the sheet's own window.
        val all = rule.onAllNodes(androidx.compose.ui.test.isRoot())
        val count = all.fetchSemanticsNodes().size
        for ((i, name) in listOf(shot, shot.replace(".png", "-sheet.png")).withIndex()) {
            if (i == 1 && count < 2) break
            val bitmap = all[if (i == 0) 0 else count - 1].captureToImage().asAndroidBitmap()
            val file = java.io.File("../build/device-test/review/$name").absoluteFile
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /** The sheet drawn as it stands over the page (the window itself cannot be photographed in a test): the dim, then the panel. */
    private fun photographSheet(out: String, update: String? = "1.30.0") {
        FormFactor.override(true)
        Motion.reduced = true
        rule.setContent {
            TMPlayerTheme {
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(Tone.background)) {
                    val screen = maxWidth
                    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)))
                    Box(Modifier.align(androidx.compose.ui.Alignment.CenterEnd)) {
                        SheetPanel(
                            width = tvSheetWidth(screen), first = FocusRequester(), account = asha, profile = null, name = "Asha Rao",
                            username = "asha", favouriteCount = 7, watchingCount = 3, watchedCount = 12, several = true,
                            updateVersion = update, onFavourites = {}, onHistory = {}, onWatchLater = {}, onSettings = {},
                            onSwitchOrAdd = {}, onRefresh = {}, onUpdate = {}, onAbout = {}, onClose = {}, title = "Account, Asha Rao",
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val file = java.io.File("../build/device-test/review/$out").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-mdpi")
    fun `the sheet as drawn at 960 by 540`() = photographSheet("tv-account-sheet-drawn-960x540.png")

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `the sheet as drawn at 1920 by 1080`() = photographSheet("tv-account-sheet-drawn-1920x1080.png")

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-mdpi")
    fun `at 960 by 540 every row is inside the safe area, one height, one left edge`() {
        open(several = true, refresh = true, counts = Triple(7, 3, 12))
        openMenu()
        checkSheet("tv-account-sheet-960x540.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `at 1920 by 1080 every row is inside the safe area, one height, one left edge`() {
        open(several = true, refresh = true, counts = Triple(7, 3, 12))
        openMenu()
        checkSheet("tv-account-sheet-1920x1080.png")
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
                            Box(Modifier.fillMaxSize())
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
    fun `the phone's sheet has Add account for one profile and no Sign out, the row goes through the shell's picker`() {
        phone(several = false)
        rule.onNodeWithTextExists(L.profilesAdd)
        rule.onAllNodes(hasText(L.profilesSwitchProfile)).assertCountIs(0)
        // Sign out is in Settings, Account, behind the PIN: not in the sheet.
        rule.onAllNodes(hasText(L.settingsSignOutConfirm)).assertCountIs(0)
        rule.onNode(hasText(L.profilesAdd) and hasClickAction()).press()
        rule.waitForIdle()
        assertEquals(listOf("picker"), calls)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-night-xhdpi")
    fun `the phone's sheet says Switch or add account with two profiles, and its header has the switcher`() {
        phone(several = true)
        rule.onNodeWithTextExists(L.profilesSwitchOrAdd)
        rule.onAllNodes(hasText(L.profilesAdd)).assertCountIs(0)
        rule.onNodeWithTag(SWITCH_BUTTON_TAG, useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
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
    fun `Settings unlocked before the app left the screen counts for nothing, with ask on open off the bar's Sign out asks the PIN (SEC-03)`() {
        // The PIN given in Settings, then Settings left by the bar over Downloads: no visit to ask in.
        com.tmplayer.data.SettingsUnlock.unlock()
        open(several = true, pin = true, realGate = true)
        rule.waitForIdle()
        pictureOnBar().performKeyInput { pressKey(Key.Menu) }
        rule.waitForIdle()
        button(L.settingsSignOutConfirm).press()
        rule.waitForIdle()
        waitForText(L.settingsSignOutTitleNamed("Asha Rao"))
        // The phone left on the table: the app leaves the screen, "ask when the app opens" is off.
        rule.runOnUiThread { com.tmplayer.data.SettingsUnlock.appLeft() }
        rule.waitForIdle()
        assertTrue("the question went", rule.onAllNodesWithText(L.settingsSignOutTitleNamed("Asha Rao")).fetchSemanticsNodes().isEmpty())
        button(L.settingsSignOutConfirm).press()
        rule.waitForIdle()
        rule.onNodeWithTextExists(L.lockEnterForSignOut)
        assertTrue(rule.onAllNodesWithText(L.settingsSignOutTitleNamed("Asha Rao")).fetchSemanticsNodes().isEmpty())
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
