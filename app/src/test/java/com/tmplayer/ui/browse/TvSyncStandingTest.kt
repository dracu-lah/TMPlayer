package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.tmplayer.data.FormFactor
import com.tmplayer.data.GlobalLine
import com.tmplayer.data.SyncStatus
import com.tmplayer.data.WorkStatus
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.components.ConnectionNotice
import com.tmplayer.ui.components.PageWork
import com.tmplayer.ui.components.TvStatusRules
import com.tmplayer.ui.components.TvSyncStandingEffect
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The sync status that stands (stopped, needs you) is not a pill for good on a television (user, 2026-11-25: "Sync stopped
 * continuously showing on all places"): it is in the bar for [TvStatusRules.STANDING_MS] after the app opens, when its state
 * changes and when Settings is opened, and only on Home; then it is a dot on the avatar and a row in the account sheet.
 * Checked at 960 x 540 and 1920 x 1080.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-mdpi")
class TvSyncStandingTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

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

    private val stopped = SyncStatus(SyncStatus.Kind.Failed, at = 1_800_000_000_000L, detail = "No answer.", failures = 3)

    private fun bar(current: TvBarTab?, searchOpen: Boolean = false, downloadsShown: Boolean = false, note: String? = L.stateSyncStoppedTv(name = L.syncplaceSwitch)) {
        FormFactor.override(true)
        Motion.reduced = true
        TvBarSignals.statusSync.value = stopped
        TvBarSignals.statusLine.value = GlobalLine.SyncNeedsYou(SyncStatus.Kind.Failed)
        val nav = TouchNav(
            account = null, favoriteCount = 0, downloadCount = 0, updateVersion = null,
            onHome = {}, onChats = {}, onSearch = {}, onSettings = {},
            onFavourites = {}, onDownloads = {}, onHistory = {}, onUpdate = {},
            profile = null, syncNote = note, onSyncNote = {},
        )
        rule.setContent {
            TMPlayerTheme {
                Column(Modifier.fillMaxSize().background(Tone.background)) {
                    TvTopBar(
                        nav = nav, current = current, inProfile = false, searchOpen = searchOpen,
                        onHome = {}, onChats = {}, onSearch = {}, onRefresh = null,
                        currentFocus = FocusRequester(), contentFocus = FocusRequester(), overArt = false,
                        downloadsShown = downloadsShown,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun pill() = rule.onAllNodes(hasAnyAncestor(hasTestTag("tv-bar-status")) and hasText(L.stateSyncStoppedShortTv), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun dot() = rule.onAllNodes(hasTestTag("tv-bar-update-dot"), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun standingOn(on: Boolean) {
        TvBarSignals.syncStanding.value = on
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
    }

    private fun home() {
        bar(TvBarTab.Home)
        standingOn(true)
        assertEquals("standing: the pill is in Home's bar", 1, pill())
        assertEquals("standing: no dot yet, the pill says it", 0, dot())
        standingOn(false)
        assertEquals("folded: no pill", 0, pill())
        assertEquals("folded: the dot is on the avatar", 1, dot())
    }

    private fun neverElsewhere() {
        val places = listOf<Pair<String, () -> Unit>>(
            "Chats" to { bar(TvBarTab.Chats) },
            "Search" to { bar(TvBarTab.Home, searchOpen = true) },
            "Downloads" to { bar(null, downloadsShown = true) },
            "History or Favourites" to { bar(null) },
        )
        // One composition per test: each place is checked in a run of its own below (see the tests), here the first.
        val (name, set) = places.first { it.first == place }
        set()
        standingOn(true)
        assertEquals("$name: no standing sync pill even while it stands", 0, pill())
    }

    private var place = "Chats"

    private fun sheetRow() {
        FormFactor.override(true)
        Motion.reduced = true
        var opened = 0
        val sentence = L.stateSyncStoppedTv(name = L.syncplaceSwitch)
        rule.setContent {
            TMPlayerTheme {
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(Tone.background)) {
                    SheetPanel(
                        width = tvSheetWidth(maxWidth), first = FocusRequester(), account = null, profile = null, name = "Asha Rao",
                        username = "asha", favouriteCount = 0, watchingCount = 0, watchedCount = 0, several = false,
                        updateVersion = null, onFavourites = {}, onHistory = {}, onWatchLater = {}, onSettings = {},
                        onSwitchOrAdd = null, onRefresh = null, onUpdate = {}, onAbout = {}, onClose = {}, title = "Account",
                        syncNote = sentence, onSyncNote = { opened++ },
                    )
                }
            }
        }
        rule.waitForIdle()
        val row = rule.onNodeWithTag("$TV_ACCOUNT_ROW_TAG-sync")
        row.assertExists()
        assertTrue("the row says the sentence", rule.onAllNodes(hasText(sentence), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        val rowBounds = row.fetchSemanticsNode().boundsInRoot
        val settingsBounds = rule.onNodeWithTag("$TV_ACCOUNT_ROW_TAG-settings").fetchSemanticsNode().boundsInRoot
        assertTrue("the row follows Settings", rowBounds.top >= settingsBounds.bottom - 1f)
        assertEquals("the row is one 56 dp row", 56f * rule.activity.resources.displayMetrics.density, rowBounds.height, 1.5f)
        row.performClick()
        rule.waitForIdle()
        assertEquals("OK on the row opens the sync setting", 1, opened)
    }

    @Test
    fun `at 960 x 540 the standing sync is in Home's bar then folds into the avatar's dot`() = home()

    @Test
    fun `at 960 x 540 the standing sync never shows on Chats`() { place = "Chats"; neverElsewhere() }

    @Test
    fun `at 960 x 540 the standing sync never shows with Search open`() { place = "Search"; neverElsewhere() }

    @Test
    fun `at 960 x 540 the standing sync never shows on Downloads`() { place = "Downloads"; neverElsewhere() }

    @Test
    fun `at 960 x 540 the standing sync never shows on History or Favourites`() { place = "History or Favourites"; neverElsewhere() }

    @Test
    fun `at 960 x 540 the account sheet has the sync row after Settings and it opens the setting`() = sheetRow()

    @Test
    fun `the pill folds after 8 s, comes back on a state change or a visit to Settings`() {
        val standing = MutableStateFlow(false)
        var key by mutableStateOf<Any?>("stopped")
        var visits by mutableStateOf(0)
        rule.mainClock.autoAdvance = false
        rule.setContent { TvSyncStandingEffect(key, visits, tv = true, standing = standing) }
        rule.mainClock.advanceTimeBy(100)
        assertTrue("on when the app opens", standing.value)
        rule.mainClock.advanceTimeBy(TvStatusRules.STANDING_MS - 500)
        assertTrue("still on just before the time", standing.value)
        rule.mainClock.advanceTimeBy(1_000)
        assertFalse("folded after about 8 s", standing.value)
        rule.runOnUiThread { key = "needs you" }; org.robolectric.shadows.ShadowLooper.idleMainLooper()
        rule.mainClock.advanceTimeBy(100)
        assertTrue("back on when the sync's state changes", standing.value)
        rule.mainClock.advanceTimeBy(TvStatusRules.STANDING_MS + 100)
        assertFalse(standing.value)
        rule.runOnUiThread { visits = 1 }; org.robolectric.shadows.ShadowLooper.idleMainLooper()
        rule.mainClock.advanceTimeBy(100)
        assertTrue("back on when Settings is opened", standing.value)
        rule.mainClock.advanceTimeBy(TvStatusRules.STANDING_MS + 100)
        assertFalse(standing.value)
        rule.runOnUiThread { key = null; visits = 2 }; org.robolectric.shadows.ShadowLooper.idleMainLooper()
        rule.mainClock.advanceTimeBy(100)
        assertFalse("nothing stands: nothing shows", standing.value)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `at 1920 x 1080 the standing sync is in Home's bar then folds into the avatar's dot`() = home()

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `at 1920 x 1080 the standing sync never shows on Chats`() { place = "Chats"; neverElsewhere() }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `at 1920 x 1080 the standing sync never shows on Downloads`() { place = "Downloads"; neverElsewhere() }

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
    fun `at 1920 x 1080 the account sheet has the sync row after Settings and it opens the setting`() = sheetRow()
}
