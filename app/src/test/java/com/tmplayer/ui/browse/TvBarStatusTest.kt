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
import com.tmplayer.data.WorkStatus
import com.tmplayer.ui.components.ConnectionNotice
import com.tmplayer.ui.components.PageWork
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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
        TvBarSignals.statusWork.value = PageWork(WorkStatus.Idle, false)
        TvBarSignals.statusConnection.value = ConnectionNotice.Hidden
        TvBarSignals.syncStanding.value = false
    }

    private val stopped = SyncStatus(SyncStatus.Kind.Failed, at = 1_800_000_000_000L, detail = "Telegram did not answer. Try again in a moment.", failures = 3)

    /** One thing the slot can say: what the shell and the page signal, and the short words it must show whole. */
    private class Case(
        val name: String,
        val words: String,
        val line: GlobalLine = GlobalLine.Idle,
        val work: PageWork = PageWork(WorkStatus.Idle, false),
        val connection: ConnectionNotice = ConnectionNotice.Hidden,
    )

    private fun cases(): List<Case> {
        val soon = System.currentTimeMillis()
        return listOf(
            Case("catching up", L.stateUpdatingTv, line = GlobalLine.Updating),
            Case("slow", L.stateSlowTv, line = GlobalLine.SlowAnswer),
            Case("a wait in seconds", "Retry in 42s", line = GlobalLine.RateLimited(42)),
            Case("the longest wait in seconds", "Retry in 119s", line = GlobalLine.RateLimited(119)),
            Case("a wait in minutes", "Retry in 5m", line = GlobalLine.RateLimited(300)),
            Case("the longest wait in minutes", "Retry in 99m", line = GlobalLine.RateLimited(5_939)),
            Case("a wait in hours", "Retry in 3h", line = GlobalLine.RateLimited(10_800)),
            Case("the longest wait in hours", "Retry in 99h", line = GlobalLine.RateLimited(99 * 3600)),
            Case("a very long wait", L.stateRateLimitedLaterTv, line = GlobalLine.RateLimited(Int.MAX_VALUE)),
            Case("a page's wait", "Retry in 30s", work = PageWork(WorkStatus.RateLimited(soon + 30_000), false)),
            Case("a newer sync file", L.stateSyncNeedsYouShortTv, line = GlobalLine.SyncNeedsYou(SyncStatus.Kind.TooNew)),
            Case("an unreadable sync file", L.stateSyncNeedsYouShortTv, line = GlobalLine.SyncNeedsYou(SyncStatus.Kind.Unreadable)),
            Case("another account", L.stateSyncNeedsYouShortTv, line = GlobalLine.SyncNeedsYou(SyncStatus.Kind.OtherAccount)),
            Case("three failed tries", L.stateSyncStoppedShortTv, line = GlobalLine.SyncNeedsYou(SyncStatus.Kind.Failed)),
            Case("reconnecting", L.stateReconnectingTv, connection = ConnectionNotice.Reconnecting),
            Case("connecting", L.stateConnectingTv, connection = ConnectionNotice.Connecting),
            Case("a page updating", L.stateUpdatingTv, work = PageWork(WorkStatus.Syncing(), true)),
            Case("a page loading", L.stateLoadingTv, work = PageWork(WorkStatus.Loading(), false)),
            Case("a page up to date", L.stateUpdated, work = PageWork(WorkStatus.Updated(soon), true)),
            Case("a refresh that failed", L.stateFailedTv, work = PageWork(WorkStatus.Failed("Telegram did not answer in time."), true)),
        )
    }

    private fun send(c: Case) {
        TvBarSignals.statusLine.value = c.line
        TvBarSignals.statusWork.value = c.work
        TvBarSignals.statusConnection.value = c.connection
        // Past the ladder's first second, where the words of a page's work are due.
        rule.mainClock.advanceTimeBy(1_500)
        rule.waitForIdle()
    }

    private fun bar() {
        FormFactor.override(true)
        Motion.reduced = true
        // A standing sync status is drawn on Home only, and only while it stands (its first seconds): the bar is Home's here.
        TvBarSignals.syncStanding.value = true
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
                        nav = nav, current = TvBarTab.Home, inProfile = false, searchOpen = false,
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

    private fun inStatus(matcher: SemanticsMatcher) = matcher and hasAnyAncestor(hasTestTag("tv-bar-status"))

    private fun said(text: String) = rule.onAllNodes(inStatus(hasText(text)), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun check(fontScale: Float) {
        bar()
        val density = rule.activity.resources.displayMetrics.density
        TvBarSignals.statusSync.value = stopped
        rule.waitForIdle()
        val tabsBefore = listOf(L.commonSearch, L.browseTabHome, L.browseTabChats, L.navDownloads).map { rule.onNodeWithText(it).fetchSemanticsNode().boundsInRoot }
        for (c in cases()) {
            send(c)
            val status = bounds("tv-bar-status")
            val content = bounds("content")
            val keyboard = bounds("keyboard")
            val card = bounds("empty-card")
            assertTrue("${c.name}: drawn ($status)", status.width > 0f && status.height > 0f)
            assertTrue("${c.name}: above the page (status bottom ${status.bottom}, page top ${content.top})", status.bottom <= content.top + 0.5f)
            assertTrue("${c.name}: not over the keyboard's keys", !status.overlaps(keyboard))
            assertTrue("${c.name}: not over the empty card", !status.overlaps(card))
            assertTrue("${c.name}: one line, ${status.height / density} dp", status.height <= 48f * density + 1f)
            assertTrue("${c.name}: at most 560 dp wide, ${status.width / density}", status.width <= 560f * density + 1f)
            // Between the last tab (Downloads) and the app's mark: never over a tab.
            val downloads = rule.onNodeWithText(L.navDownloads).fetchSemanticsNode().boundsInRoot
            assertTrue("${c.name}: after the last tab", status.left >= downloads.right - 0.5f)
            val root = rule.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot
            assertTrue("${c.name}: inside the screen", status.right <= root.right)
            // The words fit the slot WHOLE at font 1.0 and 1.3: no ellipsis, no cut sentence (the pill's own fallback
            // for a smaller room is the first word, which TvStatusFallbackTest in GlobalStatusTvFocusTest covers).
            val whole = said(c.words) > 0
            val firstOnly = said(c.words.substringBefore(' ')) > 0
            assertTrue("${c.name}: '${c.words}' fits the slot whole at font $fontScale (first word only: $firstOnly)", whole)
            assertEquals("${c.name}: no ellipsis", 0, said("\u2026"))
            // The tabs stand where they did at rest.
            val tabsNow = listOf(L.commonSearch, L.browseTabHome, L.browseTabChats, L.navDownloads).map { rule.onNodeWithText(it).fetchSemanticsNode().boundsInRoot }
            assertEquals("${c.name}: no tab moved", tabsBefore, tabsNow)
        }
        // Nothing to say: nothing drawn, nothing taking room.
        send(Case("idle", ""))
        assertTrue("idle draws nothing", rule.onAllNodesWithTagCount("tv-bar-status") == 0 || bounds("tv-bar-status").width <= 1f)
    }

    /** One slot, one status: every signal at once shows only the highest in the order needs you, sync stopped, offline, connection, wait, updating. */
    private fun priority() {
        bar()
        TvBarSignals.statusSync.value = stopped
        send(
            Case(
                "all at once", "",
                line = GlobalLine.SyncNeedsYou(SyncStatus.Kind.Failed),
                work = PageWork(WorkStatus.Syncing(), true),
                connection = ConnectionNotice.Reconnecting,
            ),
        )
        assertEquals("sync stopped is the one status", 1, said(L.stateSyncStoppedShortTv))
        assertEquals("nothing else is said", 0, said(L.stateReconnectingTv) + said(L.stateUpdatingTv))
        send(Case("connection and work", "", connection = ConnectionNotice.Reconnecting, work = PageWork(WorkStatus.Syncing(), true)))
        assertEquals(1, said(L.stateReconnectingTv))
        assertEquals(0, said(L.stateUpdatingTv))
        send(Case("a wait and a catch up", "", line = GlobalLine.Updating, work = PageWork(WorkStatus.RateLimited(System.currentTimeMillis() + 30_000), false)))
        assertEquals(0, said(L.stateUpdatingTv))
        // Offline is the offline card's to say: the slot draws nothing and holds every lower status back.
        send(Case("offline", "", connection = ConnectionNotice.Offline, line = GlobalLine.Updating, work = PageWork(WorkStatus.Syncing(), true)))
        assertTrue("offline: the slot is empty", rule.onAllNodesWithTagCount("tv-bar-status") == 0 || bounds("tv-bar-status").width <= 1f)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun Rect.overlaps(other: Rect) = left < other.right && other.left < right && top < other.bottom && other.top < bottom

    @Test
    fun `at 960 x 540 every status stands whole in the bar, over no page, key or tab`() = check(1.0f)

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-mdpi", fontScale = 1.3f)
    fun `at 960 x 540 and font 1_3 every status stands whole`() = check(1.3f)

    @Test
    @Config(sdk = [35], qualifiers = "w1920dp-h1080dp-land-television-night-mdpi")
    fun `at 1920 x 1080 every status stands whole in the bar, over no page, key or tab`() = check(1.0f)

    @Test
    @Config(sdk = [35], qualifiers = "w1920dp-h1080dp-land-television-night-mdpi", fontScale = 1.3f)
    fun `at 1920 x 1080 and font 1_3 every status stands whole`() = check(1.3f)

    @Test
    fun `one status at a time by priority, and offline holds the lower ones back`() = priority()
}
