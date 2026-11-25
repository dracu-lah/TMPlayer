package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.tmplayer.data.ChatKind
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.FormFactor
import com.tmplayer.data.GlobalLine
import com.tmplayer.data.HomeRow
import com.tmplayer.data.MediaItem
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.ShelfEntry
import com.tmplayer.data.WorkStatus
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.components.ConnectionNotice
import com.tmplayer.ui.components.PageWork
import com.tmplayer.ui.components.UiState
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
 * The real Home on a television, its Continue watching cards low on the screen (Mi TV, 2026-11-25: the
 * Updating and connection pills sat over the second card's progress bar and the next card's title). Every
 * status the TV can say, in every state, is drawn in the top bar's one slot: its bounds are inside the bar,
 * never on a card, a title or the foot, and only one status is drawn at a time.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class TvStatusOverHomeTest {

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

    private fun media(id: Long, title: String) = MediaItem(
        chatId = -5L, messageId = id, fileId = id.toInt(), title = title, sizeBytes = 1_000_000_000, durationSec = 5_000,
        mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 1_000, fileName = "$title.mp4",
    )

    private val records = (1L..12L).map {
        ResumeRecord(
            chatId = -5L, messageId = it, fileId = it.toInt(), title = "Video $it", chatTitle = "Clips",
            sizeBytes = 900_000_000, durationSec = 6_000, positionMs = 600_000, durationMs = 6_000_000, updatedAt = it,
        )
    }

    private val rows = listOf(
        HomeRow.Continue(records),
        HomeRow.Chat(-5L, "Clips", (20L..32L).map { ShelfEntry.File(media(it, "Clip $it")) }, loaded = true),
    )

    private val nav = TouchNav(
        account = null, favoriteCount = 0, downloadCount = 0, updateVersion = null,
        onHome = {}, onChats = {}, onSearch = {}, onSettings = {},
        onFavourites = {}, onDownloads = {}, onHistory = {}, onUpdate = {},
        onSwitchOrAdd = {}, onSignOut = {},
    )

    private var homeStatus by androidx.compose.runtime.mutableStateOf<WorkStatus>(WorkStatus.Idle)

    private fun home() {
        FormFactor.override(true)
        Motion.reduced = true
        // The sync that stands is on show on Home for its first seconds (TvStatusRules.STANDING_MS).
        TvBarSignals.syncStanding.value = true
        rule.setContent {
            TMPlayerTheme {
                BrowseScreen(
                    state = UiState.Content(BrowseData((1L..3L).map { ChatSummary(id = -it, title = "Chat $it", miniThumbnail = null, photoFileId = 0, kind = ChatKind.Channel) })),
                    favorites = emptySet(),
                    continueWatching = records,
                    onRetry = {},
                    onOpenChat = {},
                    onResumeMedia = {},
                    onOpenSettings = {},
                    tvNav = nav,
                    picked = BrowseSection.of(BrowseTab.Home),
                    homeRows = rows,
                    homeWatch = SeriesWatch.None,
                    homeStatus = homeStatus,
                )
            }
        }
        repeat(8) { Thread.sleep(50); rule.waitForIdle() }
    }

    private fun Rect.overlaps(other: Rect) = left < other.right && other.left < right && top < other.bottom && other.top < bottom

    /** Every drawn card and title of the page under the bar: any node that carries text of a video or a row. */
    private fun pageBounds(): List<Pair<String, Rect>> =
        rule.onAllNodes(androidx.compose.ui.test.isRoot().not(), useUnmergedTree = true).fetchSemanticsNodes()
            .mapNotNull { node ->
                val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
                if (text.startsWith("Video ") || text.startsWith("Clip ") || text == "Clips") text to node.boundsInRoot else null
            }

    private fun assertOnlyInBar(label: String) {
        val screen = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val status = rule.onNodeWithTag("tv-bar-status", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val density = rule.activity.resources.displayMetrics.density
        assertTrue("$label: drawn ($status)", status.width > 0f && status.height > 0f)
        assertTrue("$label: in the top bar (bottom ${status.bottom / density} dp, bar ${TV_BAR_HEIGHT.value} dp)", status.bottom <= TV_BAR_HEIGHT.value * density + 1f)
        assertTrue("$label: not at the foot (top ${status.top / density} dp of ${screen.height / density})", status.top < screen.height / 2f)
        val page = pageBounds()
        assertTrue("$label: the page has cards to check against, got ${page.size}", page.isNotEmpty())
        // The cards that stand lower on the screen than the bar must exist, to prove the foot case is covered.
        assertTrue("$label: cards stand low on the screen", page.any { it.second.bottom > screen.height * 0.5f })
        for ((name, box) in page) {
            assertTrue("$label: the status $status is over '$name' $box", !status.overlaps(box))
        }
        // One status only: exactly one node holds the slot's words, and no other status node is drawn anywhere.
        assertEquals("$label: one slot", 1, rule.onAllNodes(hasTestTag("tv-bar-status"), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `a catch up, a wait, a stopped sync and a reconnect all stand in the bar, over no Continue watching card`() {
        home()
        val cases = listOf(
            "catching up" to { TvBarSignals.statusLine.value = GlobalLine.Updating },
            "slow" to { TvBarSignals.statusLine.value = GlobalLine.SlowAnswer },
            "a wait" to { TvBarSignals.statusLine.value = GlobalLine.RateLimited(42) },
            "sync stopped" to { TvBarSignals.statusLine.value = GlobalLine.SyncNeedsYou(com.tmplayer.data.SyncStatus.Kind.Failed) },
            "reconnecting" to {
                TvBarSignals.statusLine.value = GlobalLine.Idle
                TvBarSignals.statusConnection.value = ConnectionNotice.Reconnecting
            },
            "connecting" to { TvBarSignals.statusConnection.value = ConnectionNotice.Connecting },
        )
        for ((name, set) in cases) {
            TvBarSignals.statusLine.value = GlobalLine.Idle
            TvBarSignals.statusConnection.value = ConnectionNotice.Hidden
            set()
            rule.waitForIdle()
            assertOnlyInBar(name)
        }
    }

    @Test
    fun `the page's own Updating and wait, as Home reports them, stand in the bar and not at the foot`() {
        home()
        // Home's rows are being built again: the asked ladder is past its quiet 3 s.
        homeStatus = WorkStatus.RateLimited(System.currentTimeMillis() + 30_000)
        rule.waitForIdle()
        assertOnlyInBar("Home's wait")
        homeStatus = WorkStatus.Syncing()
        rule.mainClock.advanceTimeBy(4_000)
        rule.waitForIdle()
        assertOnlyInBar("Home's updating")
        // No node says Updating or a wait anywhere but in the one slot.
        val outside = rule.onAllNodes(
            androidx.compose.ui.test.hasText(L.stateUpdatingTv) and hasAnyAncestor(hasTestTag("tv-bar-status")).not(),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        assertEquals("nothing says Updating outside the slot", 0, outside.size)
    }
}
