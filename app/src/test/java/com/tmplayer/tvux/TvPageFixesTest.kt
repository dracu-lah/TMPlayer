package com.tmplayer.tvux

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import com.tmplayer.data.FormFactor
import com.tmplayer.data.GlobalLine
import com.tmplayer.data.SyncStatus
import com.tmplayer.data.WorkStatus
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.browse.TvBarSignals
import com.tmplayer.ui.components.COMPACT_EMPTY_TAG
import com.tmplayer.ui.components.ConnectionNotice
import com.tmplayer.ui.components.PageWork
import com.tmplayer.ui.downloads.DownloadsScreen
import com.tmplayer.ui.downloads.STORAGE_CARD_TAG
import com.tmplayer.ui.settings.FIND_BUTTON_TAG
import com.tmplayer.ui.settings.SETTINGS_CRUMB_TAG
import com.tmplayer.ui.settings.SETTINGS_HEADER_TAG
import com.tmplayer.ui.settings.SETTINGS_STATUS_TAG
import com.tmplayer.ui.settings.SettingsPage
import com.tmplayer.ui.settings.SettingsScreen
import com.tmplayer.ui.settings.railTag
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * What the Mi TV showed on 2026-11-25 (TV fix): the Find a setting pill is 48 dp high with its label on the middle line and
 * level with the header's title row; no status pill at the foot of Settings (the header says it, and a standing sync only for
 * its first seconds); the rail's "Appearance and language" wraps to two lines whole; the Downloads page brings its first item
 * back whole after Up and fits the storage card, the sentence and both buttons on one screen. At 1920 x 1080 and 960 x 540.
 * Pictures in `design/tv-pages/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-xhdpi")
class TvPageFixesTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val drain = DrainMainLooperRule()

    private val density get() = rule.activity.resources.displayMetrics.density

    @After
    fun tearDown() {
        FormFactor.override(false)
        Motion.reduced = false
        TvBarSignals.statusLine.value = GlobalLine.Idle
        TvBarSignals.statusSync.value = null
        TvBarSignals.statusWork.value = PageWork(WorkStatus.Idle, false)
        TvBarSignals.statusConnection.value = ConnectionNotice.Hidden
        TvBarSignals.syncStanding.value = false
        com.tmplayer.ui.settings.SettingsRail.last = null
    }

    private fun show(content: @Composable () -> Unit): TvUxChecklist {
        FormFactor.override(true)
        Motion.reduced = true
        val ux = TvUxChecklist(rule, "fixes")
        rule.mainClock.autoAdvance = false
        rule.setContent { TMPlayerTheme { Box(Modifier.fillMaxSize().background(Tone.background)) { content() } } }
        repeat(6) { Thread.sleep(100); ux.settle(300) }
        ux.windowForRemote()
        repeat(10) { if (ux.focused().isEmpty()) { Thread.sleep(50); ux.settle(300) } }
        return ux
    }

    private fun picture(name: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val dpi = if (density >= 2f) "1080" else "540"
        val file = File("../design/tv-pages/$name-$dpi.png").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun settings(page: SettingsPage? = null) = show {
        SettingsScreen(chats = emptyList(), onLoggedOut = {}, initialPage = page, accountFolders = emptyList())
    }

    // ----- 2: Find a setting ---------------------------------------------------------------------------------------

    private fun findPill() {
        val ux = settings()
        val find = bounds(FIND_BUTTON_TAG)
        val header = bounds(SETTINGS_HEADER_TAG)
        val crumb = bounds(SETTINGS_CRUMB_TAG)
        assertEquals("48 dp high, like the other TV pills", 48f * density, find.height, 1.5f)
        assertEquals("level with the header's title row", header.center.y, find.center.y, 2f)
        assertEquals("level with the breadcrumb", crumb.center.y, find.center.y, 2f)
        ux.labelsCentred()
        val off = ux.findings.filter { it.rule == "labelCentre" }
        assertTrue("every pill's words are on its middle line: $off", off.isEmpty())
        picture("settings-find")
    }

    // ----- 3a: no pill at the foot, the header says the status ---------------------------------------------------------

    private fun statusInHeader() {
        TvBarSignals.statusSync.value = SyncStatus(SyncStatus.Kind.Failed, at = 1_800_000_000_000L, failures = 3)
        TvBarSignals.statusLine.value = GlobalLine.SyncNeedsYou(SyncStatus.Kind.Failed)
        TvBarSignals.syncStanding.value = true
        val ux = settings(SettingsPage.Playback)
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val said = rule.onAllNodes(androidx.compose.ui.test.hasText(L.stateSyncStoppedShortTv), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("one status pill, in the header", 1, said.size)
        val pill = bounds(SETTINGS_STATUS_TAG)
        val find = bounds(FIND_BUTTON_TAG)
        val header = bounds(SETTINGS_HEADER_TAG)
        assertTrue("right of Find a setting", pill.left >= find.right - 1f)
        assertTrue("not at the foot: pill bottom ${pill.bottom} of ${root.bottom}", pill.bottom <= header.bottom + 2f && said.single().boundsInRoot.bottom < root.height / 2)
        assertEquals("on the title row's middle line", header.center.y, pill.center.y, 2f)
        picture("settings-status-header")
        // After its first seconds the sync folds away on any page but the one of the sync switch.
        TvBarSignals.syncStanding.value = false
        ux.settle(1000); ux.settle(1000)
        assertEquals("folded on Playback: no pill anywhere", 0, rule.onAllNodes(androidx.compose.ui.test.hasText(L.stateSyncStoppedShortTv), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    private fun syncStaysOnItsPage() {
        TvBarSignals.statusSync.value = SyncStatus(SyncStatus.Kind.Failed, at = 1_800_000_000_000L, failures = 3)
        TvBarSignals.statusLine.value = GlobalLine.SyncNeedsYou(SyncStatus.Kind.Failed)
        TvBarSignals.syncStanding.value = false
        settings(SettingsPage.Accounts)
        assertEquals("a normal status line on the sync page, even once folded elsewhere", 1, rule.onAllNodes(androidx.compose.ui.test.hasText(L.stateSyncStoppedShortTv), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    private fun noFootPill() {
        // A transient status in the header too, never at the foot of the page.
        TvBarSignals.statusLine.value = GlobalLine.Updating
        settings()
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        for (n in rule.onAllNodes(androidx.compose.ui.test.hasText(L.stateUpdatingTv), useUnmergedTree = true).fetchSemanticsNodes()) {
            assertTrue("the status is in the header, not at the foot (y ${n.boundsInRoot.center.y} of ${root.height})", n.boundsInRoot.center.y < root.height / 4)
        }
    }

    // ----- 3b: the rail wraps -------------------------------------------------------------------------------------------

    private fun railWraps() {
        settings()
        val node = rule.onAllNodes(hasAnyAncestor(hasTestTag(railTag(SettingsPage.Appearance))) and androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes().first()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val layout = layouts.first()
        assertTrue("the label is long enough to need two lines: ${layout.layoutInput.text}", layout.layoutInput.text.text.length > 16)
        assertTrue("two lines, not an ellipsis: ${layout.lineCount} lines", layout.lineCount <= 2 && !layout.hasVisualOverflow)
        val item = bounds(railTag(SettingsPage.Appearance))
        assertEquals("the item stays 48 dp", 48f * density, item.height, 1.5f)
        picture("settings-rail")
    }

    // ----- 3c: the page fades at its foot ---------------------------------------------------------------------------

    private fun pageFade() {
        val ux = settings(SettingsPage.Library)
        picture("settings-page")
        // The list reaches the strip, and the 24 dp above it melt into the ground (the bottom row is not drawn hard against it).
        val pane = bounds("settings-pane")
        val list = rule.onAllNodes(hasAnyAncestor(hasTestTag("settings-pane")) and androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).fetchSemanticsNodes().first().boundsInRoot
        assertTrue("the list reaches down to the strip", list.bottom >= pane.bottom - 80f * density)
        ux.labelsCentred()
    }

    // ----- 1: Downloads --------------------------------------------------------------------------------------------------

    private fun nodeNamed(ux: TvUxChecklist, name: String): SemanticsNode? = ux.nodes().firstOrNull { ux.nameOf(it).startsWith(name) && it.config.contains(SemanticsProperties.Focused) }

    private fun downloads() {
        val ux = show { DownloadsScreen(onPlay = {}, onBack = {}, onBrowse = {}, onDownloadSeason = {}) }
        val chip = nodeNamed(ux, L.downloadsOnThisDevice)
        assertTrue("the On this device tab is there", chip != null)
        ux.focus(chip!!)
        ux.key(android.view.KeyEvent.KEYCODE_DPAD_CENTER, 500)
        // The disk is measured on another thread: the card and the sentence come a moment later.
        repeat(20) {
            if (rule.onAllNodes(hasTestTag(COMPACT_EMPTY_TAG)).fetchSemanticsNodes().isEmpty()) { Thread.sleep(100); ux.settle(300) }
        }
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val tabs = ux.nodes().first { ux.nameOf(it).startsWith(L.downloadsOnThisDevice) && it.config.contains(SemanticsProperties.Focused) }.boundsInRoot
        fun card() = bounds(STORAGE_CARD_TAG)
        fun empty() = bounds(COMPACT_EMPTY_TAG)
        assertTrue("on entry the card is whole under the tabs: card ${card().top} tabs ${tabs.bottom}", card().top >= tabs.bottom - 1f)
        assertTrue("the sentence and both buttons fit the screen whole: row bottom ${empty().bottom} of ${root.bottom}", empty().bottom <= root.bottom - 1f)
        val goToChats = ux.nodes().first { ux.nameOf(it).startsWith(L.browseGoToChats) }.boundsInRoot
        val season = ux.nodes().first { ux.nameOf(it).startsWith(L.downloadsEmptySeason) }.boundsInRoot
        assertTrue("Go to Chats whole", goToChats.bottom <= root.bottom && goToChats.top >= card().bottom - 1f)
        assertTrue("Download a season whole", season.bottom <= root.bottom && season.right <= root.right)
        ux.labelsCentred()
        assertTrue("pills centred: ${ux.findings}", ux.findings.none { it.rule == "labelCentre" })
        picture("downloads-on-this-device")

        // Down onto the buttons and back Up to the tabs: the card and the first item stand whole again.
        repeat(3) { ux.key(android.view.KeyEvent.KEYCODE_DPAD_DOWN, 300) }
        repeat(4) { ux.key(android.view.KeyEvent.KEYCODE_DPAD_UP, 300) }
        val onTabs = ux.focused().firstOrNull()?.let { ux.nameOf(it) }.orEmpty()
        assertTrue("the remote is back on a tab, not '$onTabs'", onTabs.startsWith(L.downloadsOnThisDevice) || onTabs.startsWith(L.downloadsTabDownloading))
        val tabsNow = ux.focused().first().boundsInRoot
        assertTrue("after Up to the tabs the storage card is whole: card ${card().top} tabs ${tabsNow.bottom}", card().top >= tabsNow.bottom - 1f)
        picture("downloads-after-up")
    }

    /**
     * The real card (measured: the ring, the legend, Free up space) under the real header (the bar, the title and the tabs
     * take 189 dp), then the compact empty row: card, sentence and both buttons end inside the 27 dp safe area at the foot.
     */
    private fun realCardFits() {
        val mb = 1024L * 1024
        val gb = 1024 * mb
        val figures = com.tmplayer.data.StorageChart.Figures(
            downloads = 0, unfinished = 0, recent = 0, unmatched = 0, pictures = 40 * mb, deviceTotal = 4_860 * mb, deviceFree = 3_150 * mb,
        )
        val ux = show {
            androidx.compose.foundation.layout.Column(Modifier.padding(top = 189.dp, start = 32.dp, end = 32.dp).fillMaxSize()) {
                com.tmplayer.ui.downloads.StorageSummary(figures = figures, measured = true, nothingToFree = false, onFreeUp = {})
                com.tmplayer.ui.components.BigEmpty(
                    "Nothing downloaded yet. Downloads are kept in TMPlayer's Downloads folder until you delete them.",
                    icon = com.tmplayer.ui.icons.TmIcons.Download,
                    actionLabel = L.browseGoToChats, onAction = {},
                    secondLabel = L.downloadsEmptySeason, onSecondAction = {},
                    takeFocus = false, compact = true,
                )
            }
        }
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val safeFoot = root.bottom - 27f * density
        val card = bounds(STORAGE_CARD_TAG)
        val empty = bounds(COMPACT_EMPTY_TAG)
        val season = ux.nodes().first { ux.nameOf(it).startsWith(L.downloadsEmptySeason) }.boundsInRoot
        assertTrue("card ${card.height / density} dp then the row end inside the safe area: row bottom ${empty.bottom / density} dp, limit ${safeFoot / density}", empty.bottom <= safeFoot)
        assertTrue("both buttons whole", season.bottom <= safeFoot && season.right <= root.right)
        ux.labelsCentred()
        assertTrue("pills centred: ${ux.findings}", ux.findings.none { it.rule == "labelCentre" })
        picture("downloads-real-card")
    }

    /** The shared TV button: words on the middle line at a forced 48 or 64 dp, and a button with no forced height still wraps its content. */
    private fun buttonHeights() {
        val ux = show {
            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(48.dp)) {
                com.tmplayer.ui.components.TmButton(onClick = {}, modifier = Modifier.testTag("wraps")) { androidx.tv.material3.Text("Wraps") }
                com.tmplayer.ui.components.TmButton(onClick = {}, modifier = Modifier.height(48.dp).testTag("forced48")) { androidx.tv.material3.Text("Forced 48") }
                com.tmplayer.ui.components.TmButton(onClick = {}, modifier = Modifier.height(64.dp).testTag("forced64")) { androidx.tv.material3.Text("Forced 64") }
                com.tmplayer.ui.components.TmButton(onClick = {}, modifier = Modifier.height(48.dp).fillMaxWidth().testTag("wide")) { androidx.tv.material3.Text("Wide") }
            }
        }
        assertTrue("a button with no forced height wraps its content, not the screen: ${bounds("wraps").height / density} dp", bounds("wraps").height <= 56f * density)
        assertEquals(48f * density, bounds("forced48").height, 1.5f)
        assertEquals(64f * density, bounds("forced64").height, 1.5f)
        ux.labelsCentred()
        assertTrue("words on the middle line: ${ux.findings}", ux.findings.none { it.rule == "labelCentre" })
    }

    @Test
    fun `at 1920 x 1080 the TV button centres its words at a forced height and wraps when none is forced`() = buttonHeights()

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-mdpi")
    fun `at 960 x 540 the TV button centres its words at a forced height and wraps when none is forced`() = buttonHeights()

    @Test
    fun `at 1920 x 1080 the real storage card, the sentence and both buttons fit under the header`() = realCardFits()

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-mdpi")
    fun `at 960 x 540 the real storage card, the sentence and both buttons fit under the header`() = realCardFits()

    @Test
    fun `at 1920 x 1080 Find a setting is 48 dp, centred and level with the title`() = findPill()

    @Test
    fun `at 1920 x 1080 the status is in the header and never at the foot`() = statusInHeader()

    @Test
    fun `at 1920 x 1080 a standing sync stays as a line on the sync page`() = syncStaysOnItsPage()

    @Test
    fun `at 1920 x 1080 a transient status is in the header, not at the foot`() = noFootPill()

    @Test
    fun `at 1920 x 1080 the rail wraps Appearance and language to two lines`() = railWraps()

    @Test
    fun `at 1920 x 1080 the page fades at its foot and the pills are centred`() = pageFade()

    @Test
    fun `at 1920 x 1080 Downloads fits the card and the buttons and returns to its top`() = downloads()

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-mdpi")
    fun `at 960 x 540 Find a setting is 48 dp, centred and level with the title`() = findPill()

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-mdpi")
    fun `at 960 x 540 the status is in the header and never at the foot`() = statusInHeader()

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-mdpi")
    fun `at 960 x 540 the rail wraps Appearance and language to two lines`() = railWraps()

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-mdpi")
    fun `at 960 x 540 the page fades at its foot and the pills are centred`() = pageFade()

    @Test
    @Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-mdpi")
    fun `at 960 x 540 Downloads fits the card and the buttons and returns to its top`() = downloads()
}
