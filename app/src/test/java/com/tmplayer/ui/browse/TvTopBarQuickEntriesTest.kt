package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocusable
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
 * The television's top bar quick entries for Downloads and Watch later (PL-16, 2026-11-15): they
 * stand after Chats in the bar's order (so Right from Chats reaches them and Left walks back, the
 * order the focus search follows), take the remote like the tabs, name themselves only under the
 * remote and open their page from OK. The remote's own movement through the bar (Left, Right, Down
 * into the page, Back) is the device leftover: a bar's items take no focus in this harness.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class TvTopBarQuickEntriesTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val calls = mutableListOf<String>()

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
    }

    private fun open() {
        FormFactor.override(true)
        Motion.reduced = true
        val nav = TouchNav(
            account = null, favoriteCount = 0, downloadCount = 0, updateVersion = null,
            onHome = {}, onChats = {}, onSearch = {}, onSettings = {},
            onFavourites = {}, onDownloads = { calls += "downloads" },
            onHistory = { calls += "history:$it" }, onUpdate = {},
        )
        rule.setContent {
            TMPlayerTheme {
                Column {
                    TvTopBar(
                        nav = nav, current = TvBarTab.Chats, inProfile = false, searchOpen = false,
                        onHome = {}, onChats = {}, onSearch = {}, onRefresh = null,
                        currentFocus = FocusRequester(), contentFocus = FocusRequester(), overArt = false,
                        beforeProfile = { calls += "before" },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun downloads() = rule.onNodeWithContentDescription(L.navDownloads)
    private fun later() = rule.onNodeWithContentDescription(L.browseTabWatchLater)

    @Test
    fun `both entries stand after Chats in the focus order, the way Right from Chats runs`() {
        open()
        val chats = rule.onNodeWithText(L.browseTabChats).fetchSemanticsNode().boundsInRoot
        val downloads = downloads().fetchSemanticsNode().boundsInRoot
        val later = later().fetchSemanticsNode().boundsInRoot
        assertTrue("Downloads after Chats", downloads.left >= chats.right)
        assertTrue("Watch later after Downloads", later.left >= downloads.right)
        // The same row, so Left and Right move across and Down is the bar's own (into the page).
        assertEquals(chats.top, downloads.top, 1f)
        assertEquals(chats.top, later.top, 1f)
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
    fun `OK on each goes to its page, after the bar's own beforeProfile`() {
        open()
        downloads().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("before", "downloads"), calls)
        calls.clear()
        later().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("before", "history:${HistoryTab.WatchLater}"), calls)
    }

    @Test
    fun `an entry is only an icon until the remote is on it, and says its name to a screen reader`() {
        open()
        rule.onNodeWithText(L.navDownloads).assertDoesNotExist()
        rule.onNodeWithText(L.browseTabWatchLater).assertDoesNotExist()
        assertEquals(
            listOf(L.navDownloads),
            downloads().fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription),
        )
    }
}
