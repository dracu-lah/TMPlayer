package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertTextEquals
import com.tmplayer.data.Account
import com.tmplayer.data.FormFactor
import com.tmplayer.data.Profile
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The television's top bar: Downloads is a place of its own after Chats, its name written, with the
 * number running (R2, PL-16). Watch later left the bar (WP-14): the account menu and History keep it.
 * Downloads is a focus target in the bar's order, after Chats, and OK opens its page from the bar's
 * own beforeProfile. The arrows themselves (Right from Chats onto Downloads, then the
 * Choose chats button) are the desktop's big-screen bar tests, ChooseChatsButtonTest and
 * BigScreenKeysTest, where key traversal runs: a bar's items take no focus in this harness. Down into
 * the page and Back from the player are the device leftovers.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class TvTopBarQuickEntriesTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    private val calls = mutableListOf<String>()

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
    }

    private fun open(
        running: Int = 0,
        downloadsShown: Boolean = false,
        account: Account? = null,
        profile: Profile? = null,
        update: String? = null,
        profileFocus: FocusRequester? = null,
        currentFocus: FocusRequester = FocusRequester(),
        remote: Boolean = false,
    ) {
        FormFactor.override(true)
        Motion.reduced = true
        val nav = TouchNav(
            account = account, favoriteCount = 0, downloadCount = running, updateVersion = update,
            onHome = {}, onChats = {}, onSearch = {}, onSettings = { calls += "settings" },
            onFavourites = {}, onDownloads = { calls += "downloads" },
            onHistory = { calls += "history:$it" }, onUpdate = {},
            profile = profile,
        )
        rule.setContent {
            TMPlayerTheme {
                Column {
                    TvTopBar(
                        nav = nav, current = if (downloadsShown) null else TvBarTab.Chats, inProfile = downloadsShown, searchOpen = false,
                        onHome = {}, onChats = {}, onSearch = {}, onRefresh = null,
                        currentFocus = currentFocus, contentFocus = FocusRequester(), overArt = false,
                        beforeProfile = { calls += "before" },
                        downloadsShown = downloadsShown,
                        profileFocus = profileFocus,
                    )
                }
            }
        }
        if (remote) {
            // A window the remote works in: out of touch mode and holding focus, as on a television.
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
        rule.waitForIdle()
    }

    private fun downloads() = rule.onNodeWithText(L.navDownloads)

    @Test
    fun `Downloads has its name written after Chats, and nothing follows it but the bar's own buttons`() {
        open()
        val chats = rule.onNodeWithText(L.browseTabChats).fetchSemanticsNode().boundsInRoot
        val downloads = downloads().fetchSemanticsNode().boundsInRoot
        assertTrue("Downloads after Chats", downloads.left >= chats.right)
        // The same row, so Left and Right move across and Down is the bar's own (into the page).
        assertEquals(chats.top, downloads.top, 1f)
        // Downloads is a tab like Home and Chats: named, not an icon waiting for the remote.
        rule.onNodeWithText(L.navDownloads).assertExists()
    }

    @Test
    fun `Downloads takes the remote like a tab and is pressable`() {
        open()
        val focusable = rule.onAllNodes(isFocusable()).fetchSemanticsNodes()
        val node = downloads().fetchSemanticsNode()
        assertTrue("a focus target", focusable.any { it.id == node.id })
        assertTrue("pressable", node.config.getOrNull(SemanticsActions.OnClick) != null)
    }

    @Test
    fun `OK on Downloads goes to its page, after the bar's own beforeProfile`() {
        open()
        downloads().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("before", "downloads"), calls)
    }

    @Test
    fun `Watch later is no tab in the bar any more (WP-14), the account menu keeps it`() {
        // The bar holds Search, Home, Chats and Downloads, then the picture's own button: no tab
        // is named Watch later, with its icon, its word or its description.
        open()
        rule.onAllNodesWithText(L.browseTabWatchLater).assertCountEquals(0)
        rule.onAllNodesWithContentDescription(L.browseTabWatchLater).assertCountEquals(0)
    }

    @Test
    fun `the account menu still lists Watch later, one OK on the picture away`() {
        open(account = asha)
        picture().performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText(L.browseTabWatchLater).assertExists()
        rule.onNodeWithText(L.browseTabWatchLater).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("before", "history:${HistoryTab.WatchLater}"), calls.takeLast(2))
    }

    @Test
    fun `the number running is on Downloads and read out with its name, and nothing at zero`() {
        open(running = 3)
        rule.onNodeWithTag("tv-bar-badge", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("3", useUnmergedTree = true).assertExists()
        rule.onNode(androidx.compose.ui.test.hasContentDescription("${L.navDownloads}, 3")).assertExists()
    }

    @Test
    fun `no badge when nothing is running, and the page shown marks its tab`() {
        open(running = 0, downloadsShown = true)
        rule.onAllNodesWithTag("tv-bar-badge", useUnmergedTree = true).assertCountEquals(0)
        downloads().assertIsSelected()
    }

    // WP-01: the account's button --------------------------------------------------------------

    private val asha = Account(name = "Asha Rao", username = "asha", miniThumbnail = null, photoFileId = 0)

    private fun picture() = rule.onNodeWithContentDescription("Asha Rao, @asha")

    @Test
    fun `Back from Settings lands on the picture, the bar's profileFocus being attached to it`() {
        // The picture has its own requester on a page, and the one the browser hands in must be on
        // it too: a local of the same name once hid it, so Back found nothing to focus.
        val asked = FocusRequester()
        open(account = asha, profileFocus = asked, remote = true)
        picture().performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText(L.navSettings).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("before", "settings"), calls)
        // What BrowseScreen does for RailReturn.Profile.
        rule.runOnIdle { asked.requestFocus() }
        picture().assertIsFocused()
    }

    @Test
    fun `Back from Settings over Downloads lands on the picture through the bar's own entry`() {
        val entry = FocusRequester()
        open(account = asha, downloadsShown = true, currentFocus = entry, remote = true)
        rule.runOnIdle { entry.requestFocus() }
        picture().assertIsFocused()
    }

    @Test
    fun `the picture says the name and username, and the update after them while one waits`() {
        open(account = asha)
        picture().assertExists()
        rule.onNodeWithContentDescription("Asha Rao, @asha, ${L.navUpdateWaiting}").assertDoesNotExist()
    }

    @Test
    fun `an update waiting is said in the description, and the dot stays a picture`() {
        open(account = asha, update = "9.9.9")
        rule.onNodeWithContentDescription("Asha Rao, @asha, ${L.navUpdateWaiting}").assertExists()
        val dot = rule.onNodeWithTag("tv-bar-update-dot", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue("no words of its own", dot.config.getOrNull(SemanticsProperties.ContentDescription) == null)
    }

    @Test
    fun `with nothing loaded and no profile the button says Account and has no chip`() {
        open(account = null, profile = null)
        rule.onNodeWithContentDescription(L.navAccount).assertExists()
        rule.onAllNodesWithTag("tv-bar-first-name", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `the profile names the button while Telegram has not answered`() {
        open(account = null, profile = Profile(id = "a", folder = null, name = "Maya Lee"))
        rule.onNodeWithContentDescription("Maya Lee").assertExists()
        rule.onAllNodesWithTag("tv-bar-first-name", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `Telegram's name and username win over the profile's once the account is there`() {
        open(account = asha, profile = Profile(id = "a", folder = null, name = "Maya Lee"))
        picture().assertExists()
        rule.onAllNodesWithTag("tv-bar-first-name", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `the button reads as exactly the name and its role, with no stray letter or first name`() {
        // A name and no photo: the letter is drawn on the disc, the first name beside it, and
        // neither is read.
        open(account = asha, profile = Profile(id = "a", folder = null, name = "Asha Rao"))
        val node = picture().fetchSemanticsNode()
        assertEquals(listOf("Asha Rao, @asha"), node.config.getOrNull(SemanticsProperties.ContentDescription))
        assertEquals("no text merged in from the letter or the chip", null, node.config.getOrNull(SemanticsProperties.Text))
        assertEquals(androidx.compose.ui.semantics.Role.Button, node.config.getOrNull(SemanticsProperties.Role))
    }

    @Test
    fun `OK on the picture is a button that opens the account menu, said after Double tap to`() {
        open(account = asha)
        val node = picture().fetchSemanticsNode()
        assertEquals(androidx.compose.ui.semantics.Role.Button, node.config.getOrNull(SemanticsProperties.Role))
        assertEquals(L.navAccountMenu, node.config.getOrNull(SemanticsActions.OnClick)?.label)
        assertEquals("open the account menu", L.navAccountMenu)
    }

    @Test
    fun `the chip is the first word of the name, whatever the spacing`() {
        assertEquals("Asha", FirstName.of("Asha Rao"))
        assertEquals("Asha", FirstName.of("  Asha   Rao "))
        assertEquals("Asha", FirstName.of("Asha"))
        assertEquals(null, FirstName.of(""))
        assertEquals(null, FirstName.of("   "))
        assertEquals(null, FirstName.of(null))
    }

    @Test
    fun `the description needs a name for the username, and the fallback has none`() {
        assertEquals("Account", profileDescription(null, "asha", "Account", null))
        assertEquals("Account, an update is ready", profileDescription("", null, "Account", "an update is ready"))
        assertEquals("Asha, @asha", profileDescription("Asha", "asha", "Account", null))
        assertEquals("Asha", profileDescription("Asha", "  ", "Account", null))
        assertEquals("Asha, @asha, an update is ready", profileDescription("Asha", "asha", "Account", "an update is ready"))
    }
}
