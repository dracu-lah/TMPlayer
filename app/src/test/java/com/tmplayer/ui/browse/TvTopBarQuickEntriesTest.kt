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
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.tmplayer.data.FormFactor
import com.tmplayer.i18n.L
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
 * number running (R2, PL-16), and Watch later the one quick entry left (an icon that names itself
 * under the remote). Both take the remote in the bar's order (Right from Chats reaches Downloads,
 * Right again Watch later, Left walks back), and OK opens their page from the bar's own
 * beforeProfile. Down into the page and Back from the player are the device leftovers.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class TvTopBarQuickEntriesTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val calls = mutableListOf<String>()

    /** The bar's own item for the page shown (Chats here), which the bar sends the remote to on entering it. */
    private val onChats = FocusRequester()

    /** The window's focus manager: a D-pad arrow is `moveFocus` in that direction, what the key event runs. */
    private var focus: androidx.compose.ui.focus.FocusManager? = null

    private fun press(direction: androidx.compose.ui.focus.FocusDirection) {
        rule.runOnIdle { assertTrue("a neighbour to move to", focus!!.moveFocus(direction)) }
        rule.waitForIdle()
    }

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
    }

    private fun open(running: Int = 0, downloadsShown: Boolean = false) {
        FormFactor.override(true)
        Motion.reduced = true
        val nav = TouchNav(
            account = null, favoriteCount = 0, downloadCount = running, updateVersion = null,
            onHome = {}, onChats = {}, onSearch = {}, onSettings = {},
            onFavourites = {}, onDownloads = { calls += "downloads" },
            onHistory = { calls += "history:$it" }, onUpdate = {},
        )
        rule.setContent {
            TMPlayerTheme {
                focus = androidx.compose.ui.platform.LocalFocusManager.current
                Column {
                    TvTopBar(
                        nav = nav, current = if (downloadsShown) null else TvBarTab.Chats, inProfile = downloadsShown, searchOpen = false,
                        onHome = {}, onChats = {}, onSearch = {}, onRefresh = null,
                        currentFocus = onChats, contentFocus = FocusRequester(), overArt = false,
                        beforeProfile = { calls += "before" },
                        downloadsShown = downloadsShown,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun downloads() = rule.onNodeWithText(L.navDownloads)
    private fun later() = rule.onNodeWithContentDescription(L.browseTabWatchLater)

    @Test
    fun `Downloads has its name written after Chats, and Watch later is an icon after it`() {
        open()
        val chats = rule.onNodeWithText(L.browseTabChats).fetchSemanticsNode().boundsInRoot
        val downloads = downloads().fetchSemanticsNode().boundsInRoot
        val later = later().fetchSemanticsNode().boundsInRoot
        assertTrue("Downloads after Chats", downloads.left >= chats.right)
        assertTrue("Watch later after Downloads", later.left >= downloads.right)
        // The same row, so Left and Right move across and Down is the bar's own (into the page).
        assertEquals(chats.top, downloads.top, 1f)
        assertEquals(chats.top, later.top, 1f)
        // Downloads is a tab like Home and Chats: named, not an icon waiting for the remote.
        rule.onNodeWithText(L.navDownloads).assertExists()
    }

    @Test
    fun `both take the remote like a tab and are pressable`() {
        open()
        val focusable = rule.onAllNodes(isFocusable()).fetchSemanticsNodes()
        for (entry in listOf(downloads(), later())) {
            val node = entry.fetchSemanticsNode()
            assertTrue("a focus target", focusable.any { it.id == node.id })
            assertTrue("pressable", node.config.getOrNull(SemanticsActions.OnClick) != null)
        }
    }

    @Test
    fun `the remote walks from Chats to Downloads to Watch later and back`() {
        open()
        val chats = rule.onNodeWithText(L.browseTabChats)
        chats.requestFocus()
        rule.waitForIdle()
        chats.assertIsFocused()
        press(androidx.compose.ui.focus.FocusDirection.Right)
        downloads().assertIsFocused()
        press(androidx.compose.ui.focus.FocusDirection.Right)
        later().assertIsFocused()
        press(androidx.compose.ui.focus.FocusDirection.Left)
        downloads().assertIsFocused()
        // OK opens Downloads, from the bar's own beforeProfile.
        downloads().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.waitForIdle()
        assertEquals(listOf("before", "downloads"), calls)
    }

    @Test
    fun `OK on each goes to its page, after the bar's own beforeProfile`() {
        open()
        downloads().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("before", "downloads"), calls)
        calls.clear()
        later().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("before", "history:${HistoryTab.WatchLater}"), calls)
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

    @Test
    fun `Watch later is only an icon until the remote is on it, and says its name to a screen reader`() {
        open()
        rule.onNodeWithText(L.browseTabWatchLater).assertDoesNotExist()
        assertEquals(
            listOf(L.browseTabWatchLater),
            later().fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription),
        )
    }
}
