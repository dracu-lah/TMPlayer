package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormFactor
import com.tmplayer.data.GlobalLine
import com.tmplayer.data.SyncStatus
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.components.TvKeyboard
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
 * The television's status line lives in the top bar's free space (user, 2026-11-25, found on the Mi TV):
 * between the last tab and the app's mark, one line, never over the page, its keys (the search keyboard's
 * Space, backspace and Search) or its empty-state card, and it never moves a tab. Checked at 960 x 540 and
 * 1920 x 1080 for every kind it can say, with the real bar over the real TV keyboard.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-mdpi")
class TvBarStatusTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
        TvBarSignals.statusLine.value = GlobalLine.Idle
        TvBarSignals.statusSync.value = null
    }

    private val stopped = SyncStatus(SyncStatus.Kind.Failed, at = 1_800_000_000_000L, detail = "Telegram did not answer. Try again in a moment.", failures = 3)

    private val states: List<Pair<String, GlobalLine>> = listOf(
        "catching up" to GlobalLine.Updating,
        "slow" to GlobalLine.SlowAnswer,
        "a wait" to GlobalLine.RateLimited(42),
        "a newer sync file" to GlobalLine.SyncNeedsYou(SyncStatus.Kind.TooNew),
        "three failed tries" to GlobalLine.SyncNeedsYou(SyncStatus.Kind.Failed),
    )

    private fun bar() {
        FormFactor.override(true)
        Motion.reduced = true
        val nav = TouchNav(
            account = null, favoriteCount = 0, downloadCount = 0, updateVersion = null,
            onHome = {}, onChats = {}, onSearch = {}, onSettings = {},
            onFavourites = {}, onDownloads = {}, onHistory = {}, onUpdate = {},
            profile = null,
        )
        rule.setContent {
            TMPlayerTheme {
                Column(Modifier.fillMaxSize()) {
                    TvTopBar(
                        nav = nav, current = TvBarTab.Chats, inProfile = false, searchOpen = true,
                        onHome = {}, onChats = {}, onSearch = {}, onRefresh = null,
                        currentFocus = FocusRequester(), contentFocus = FocusRequester(), overArt = false,
                    )
                    // The search page's content under the bar: the keyboard on the left, the empty card beside it.
                    Row(Modifier.fillMaxSize().padding(start = TV_START).testTag("content")) {
                        TvKeyboard(text = "", onText = {}, onSearch = {}, modifier = Modifier.width(288.dp).testTag("keyboard"))
                        Box(Modifier.fillMaxSize().padding(start = 24.dp).testTag("empty-card"))
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun check() {
        bar()
        val density = rule.activity.resources.displayMetrics.density
        TvBarSignals.statusSync.value = stopped
        TvBarSignals.statusLine.value = GlobalLine.Idle
        rule.waitForIdle()
        val tabsBefore = listOf(L.commonSearch, L.browseTabHome, L.browseTabChats, L.navDownloads).map { rule.onNodeWithText(it).fetchSemanticsNode().boundsInRoot }
        for ((name, line) in states) {
            TvBarSignals.statusLine.value = line
            rule.waitForIdle()
            val status = bounds("tv-bar-status")
            val content = bounds("content")
            val keyboard = bounds("keyboard")
            val card = bounds("empty-card")
            assertTrue("$name: drawn ($status)", status.width > 0f && status.height > 0f)
            assertTrue("$name: above the page (status bottom ${status.bottom}, page top ${content.top})", status.bottom <= content.top + 0.5f)
            assertTrue("$name: not over the keyboard's keys", !status.overlaps(keyboard))
            assertTrue("$name: not over the empty card", !status.overlaps(card))
            assertTrue("$name: one line, ${status.height / density} dp", status.height <= 48f * density + 1f)
            assertTrue("$name: at most 560 dp wide, ${status.width / density}", status.width <= 560f * density + 1f)
            // Between the last tab (Downloads) and the app's mark: never over a tab.
            val downloads = rule.onNodeWithText(L.navDownloads).fetchSemanticsNode().boundsInRoot
            assertTrue("$name: after the last tab", status.left >= downloads.right - 0.5f)
            val root = rule.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot
            assertTrue("$name: inside the screen", status.right <= root.right)
            // The tabs stand where they did at rest.
            val tabsNow = listOf(L.commonSearch, L.browseTabHome, L.browseTabChats, L.navDownloads).map { rule.onNodeWithText(it).fetchSemanticsNode().boundsInRoot }
            assertEquals("$name: no tab moved", tabsBefore, tabsNow)
        }
        // Nothing to say: nothing drawn, nothing taking room.
        TvBarSignals.statusLine.value = GlobalLine.Idle
        rule.waitForIdle()
        assertTrue("idle draws nothing", rule.onAllNodesWithTagCount("tv-bar-status") == 0 || bounds("tv-bar-status").width <= 1f)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun Rect.overlaps(other: Rect) = left < other.right && other.left < right && top < other.bottom && other.top < bottom

    @Test
    fun `at 960 x 540 the status stands in the bar, over no page, key or tab`() = check()

    @Test
    @Config(sdk = [35], qualifiers = "w1920dp-h1080dp-land-television-night-mdpi")
    fun `at 1920 x 1080 the status stands in the bar, over no page, key or tab`() = check()
}
