package com.tmplayer.ui.browse

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormFactor
import com.tmplayer.data.KeepNextPlanner
import com.tmplayer.data.KeepNextRunner
import com.tmplayer.data.KeepNextShow
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SeriesShelf
import com.tmplayer.data.ShelfEntry
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.downloads.KeptForYou
import com.tmplayer.ui.theme.TmMaterialTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Smart downloads' switch on a show's page and the Downloads row (WP-84), on a phone: the switch
 * is there when the platform passes one, flips with a press, is left out for a chat that restricts
 * saving, and is drawn off for a show watched to its end; the row says what each show keeps or why
 * not, with the numbers.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-notnight-xhdpi")
class KeepNextSwitchTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
    }

    private var nextId = 1L

    private fun video(name: String, saved: Boolean): MediaItem {
        val id = nextId++
        return MediaItem(
            chatId = 7, messageId = id, fileId = id.toInt(), title = name, sizeBytes = 400_000_000,
            durationSec = 2600, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null,
            date = (1000 - id).toInt(), fileName = name, canBeSaved = saved,
        )
    }

    private fun show(saved: Boolean = true) = SeriesShelf.arrange(
        (1..4).map { video("Harbour Notes S01E0$it.mp4", saved) },
    ).filterIsInstance<ShelfEntry.Show>().single().series

    private val button = hasClickAction() and (hasContentDescription(L.downloadsKeepNext(count = KeepNextPlanner.DEFAULT_COUNT)) or hasText(L.downloadsKeepNextShort))

    private fun page(saved: Boolean = true, finished: (MediaItem) -> Boolean = { false }, keepNext: KeepNextSwitch?) {
        FormFactor.override(false)
        val series = show(saved)
        rule.setContent {
            TmMaterialTheme(dark = true) {
                SeriesPanel(
                    series = series,
                    watch = SeriesWatch(point = { null }, finished = finished),
                    onPlay = {},
                    keepNext = keepNext,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                )
            }
        }
        settle()
    }

    /** The page holds its first frame for the metadata's answer, and the status line waits its second: as SeriesPhoneScrollTest waits. */
    private fun settle() = repeat(4) {
        Thread.sleep(250)
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
    }

    @Test
    fun `a press turns it on and the page passes it on`() {
        val asked = mutableListOf<Boolean>()
        page(keepNext = KeepNextSwitch(on = false) { asked += it })
        rule.onNode(button, useUnmergedTree = false).assertIsEnabled().performClick()
        assertEquals(listOf(true), asked)
    }

    @Test
    fun `on, a press turns it off even for a show watched to its end`() {
        val asked = mutableListOf<Boolean>()
        page(finished = { true }, keepNext = KeepNextSwitch(on = true) { asked += it })
        rule.onNode(button).assertIsEnabled().performClick()
        assertEquals(listOf(false), asked)
    }

    @Test
    fun `a show watched to its end draws it off`() {
        page(finished = { true }, keepNext = KeepNextSwitch(on = false) {})
        rule.onNode(button).assertIsNotEnabled()
    }

    @Test
    fun `a chat that restricts saving and a platform without the runner have no switch`() {
        page(saved = false, keepNext = KeepNextSwitch(on = false) {})
        assertEquals(0, rule.onAllNodes(button).fetchSemanticsNodes().size)
    }

    @Test
    fun `no switch passed is no button`() {
        page(keepNext = null)
        assertEquals(0, rule.onAllNodes(button).fetchSemanticsNodes().size)
    }

    @Test
    fun `the Downloads row names the show and the room the next one needs`() {
        val item = video("Harbour Notes S01E02.mp4", true)
        val plan = KeepNextPlanner.Plan(
            skipped = listOf(KeepNextPlanner.Skip(item, KeepNextPlanner.Reason.NoSpace, neededBytes = 1L shl 30, freeBytes = 1L shl 28)),
        )
        val report = KeepNextRunner.Report(KeepNextShow(7, "harbour notes"), "Harbour Notes", plan, queued = 0)
        val wifi = report.copy(show = KeepNextShow(8, "other"), title = "Other", plan = KeepNextPlanner.Plan(skipped = listOf(KeepNextPlanner.Skip(item, KeepNextPlanner.Reason.WaitingForWifi))))
        rule.setContent { TmMaterialTheme(dark = true) { KeptForYou(listOf(report, wifi), running = true) } }
        // The status line waits before it speaks: automatic work shows from 3 s (WP-91's ladder).
        settle()
        settle()
        rule.onNodeWithText(L.downloadsKeptForYou(show = "Harbour Notes"), substring = true).assertExists()
        rule.onNodeWithText(KeepNextRunner.detail(report) { com.tmplayer.i18n.Translator.messages.formatter.bytes(it) }, substring = true).assertExists()
        rule.onNodeWithText(L.downloadsKeptWaitingWifi, substring = true).assertExists()
        rule.onNodeWithText(L.downloadsKeepNextChecking, substring = true, useUnmergedTree = true).assertExists()
    }
}
