package com.tmplayer.ui.components

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FormFactor
import com.tmplayer.data.GlobalLine
import com.tmplayer.data.SyncStatus
import com.tmplayer.i18n.L
import com.tmplayer.ui.DrainMainLooperRule
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
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
 * The status line on a television (WP-100, WP-102): catching up, slow, a wait and a sync that
 * needs the viewer say words only, on ONE line (the top bar gives it its free space). Nothing in the
 * pill takes the remote or answers a press (no Retry, Try again, Send logs, Open settings, Close:
 * those are for a finger or a pointer), the remote stays on the row it was on through every state,
 * no row moves or repaints when the pill appears or changes, and the pill stays inside the safe area
 * at 960 x 540 and 1920 x 1080. The sync lines name the Settings row by the string that titles it,
 * since no button points to it, and keep the long sentence as the screen reader's description. Where
 * the bar puts it, and that it covers no page or key, is [com.tmplayer.ui.browse.TvBarStatusTest].
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-night-mdpi")
class GlobalStatusTvFocusTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A state write left on the looper must not silence Compose for the next test (HX92). */
    @get:Rule
    val drain = DrainMainLooperRule()

    @After
    fun tearDown() {
        FormFactor.override(false)
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
        rule.waitForIdle()
    }

    private val stopped = SyncStatus(SyncStatus.Kind.Failed, at = 1_800_000_000_000L, detail = "Telegram said no.", failures = 3)

    private var line by mutableStateOf<GlobalLine>(GlobalLine.Idle)
    private var sync by mutableStateOf<SyncStatus?>(null)

    private val rows = listOf("Row one", "Row two", "Row three")

    /** Three rows in the page and the pill in the bar's corner (the bar's own layout is TvBarStatusTest's). */
    private fun screen() {
        FormFactor.override(true)
        rule.setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(Tone.background).testTag("screen")) {
                    Column(Modifier.padding(48.dp).testTag("rows")) {
                        rows.forEach { name -> Button(onClick = {}) { Text(name) } }
                    }
                    TvStatusPill(
                        input = TvStatusInput(line = line, sync = sync),
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = Tv.SafeV, end = Tv.SafeH).testTag("global"),
                    )
                }
            }
        }
        remoteWindow()
    }

    private fun row(name: String) = rule.onNode(hasText(name) and hasClickAction())

    private fun hasFocusedNode() = SemanticsMatcher("is focused") { it.config.getOrNull(SemanticsProperties.Focused) == true }

    private fun assertWordsOnly() {
        for (label in listOf(L.commonRetry, L.commonTryAgain, L.logsSend, L.notifyOpenSettings, L.commonClose)) {
            rule.onAllNodes(hasText(label) and hasClickAction()).assertCountEquals(0)
        }
        // Only the three rows take a click, so only they can take the remote.
        rule.onAllNodes(hasClickAction()).assertCountEquals(rows.size)
    }

    private val rowName get() = L.syncplaceSwitch

    /** Each state with its short TV words (the whole text of the pill) and the long sentence a screen reader gets. */
    private val states: List<Triple<String, GlobalLine, Pair<String, String>>> get() = listOf(
        Triple("catching up", GlobalLine.Updating, L.stateUpdatingTv to L.stateUpdating),
        Triple("slow", GlobalLine.SlowAnswer, L.stateSlowTv to L.commonSlowAnswer),
        Triple("a wait", GlobalLine.RateLimited(42), "Retry in 42s" to L.stateRateLimited(seconds = 42)),
        Triple("a newer sync file", GlobalLine.SyncNeedsYou(SyncStatus.Kind.TooNew), "Sync needs you" to L.stateSyncNeedsYouTv(name = rowName)),
        Triple("an unreadable sync file", GlobalLine.SyncNeedsYou(SyncStatus.Kind.Unreadable), "Sync needs you" to L.stateSyncNeedsYouTv(name = rowName)),
        Triple("another account", GlobalLine.SyncNeedsYou(SyncStatus.Kind.OtherAccount), "Sync needs you" to L.stateSyncNeedsYouTv(name = rowName)),
        Triple("three failed tries", GlobalLine.SyncNeedsYou(SyncStatus.Kind.Failed), "Sync stopped" to L.stateSyncStoppedTv(name = rowName)),
    )

    @Test
    fun `every state says its words, takes no focus and offers no button on the remote`() {
        screen()
        sync = stopped
        val first = row(rows[0])
        first.requestFocus()
        first.assertIsFocused()
        for ((name, state, said) in states) {
            line = state
            rule.waitForIdle()
            rule.onNode(hasText(said.first)).assertExists("$name: the short words are drawn whole")
            assertWordsOnly()
            rule.onNode(hasContentDescription(said.second)).assertExists("$name: the screen reader gets the whole sentence")
            if (state is GlobalLine.SyncNeedsYou) {
                assertTrue("$name: Settings' row is named in the description", rowName in said.second)
            }
            first.assertIsFocused()
        }
        line = GlobalLine.Idle
        rule.waitForIdle()
        first.assertIsFocused()
    }

    @Test
    fun `the remote walks the rows only, with the pill up in every state, and OK on a row is not taken by it`() {
        screen()
        sync = stopped
        for ((name, state, _) in states) {
            line = state
            rule.waitForIdle()
            row(rows[0]).requestFocus()
            row(rows[0]).assertIsFocused()
            row(rows[0]).performKeyInput { pressKey(Key.DirectionDown) }
            rule.waitForIdle()
            row(rows[1]).assertIsFocused()
            row(rows[1]).performKeyInput { pressKey(Key.DirectionDown) }
            rule.waitForIdle()
            row(rows[2]).assertIsFocused()
            // Down past the last row finds nothing: the pill below is not a place to go.
            row(rows[2]).performKeyInput { pressKey(Key.DirectionDown) }
            rule.waitForIdle()
            row(rows[2]).assertIsFocused()
            row(rows[2]).performKeyInput { pressKey(Key.DirectionRight) }
            rule.waitForIdle()
            row(rows[2]).assertIsFocused()
            row(rows[2]).performKeyInput { pressKey(Key.DirectionUp) }
            rule.waitForIdle()
            row(rows[1]).assertIsFocused()
            assertTrue("$name: one node holds the remote", rule.onAllNodes(hasFocusedNode()).fetchSemanticsNodes().size == 1)
        }
    }

    @Test
    fun `the pill appearing, changing and going moves no row and repaints none`() {
        screen()
        row(rows[1]).requestFocus()
        fun frame(): Bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val rowBoxes = rows.map { row(it).getBoundsInRoot() }
        val density = rule.activity.resources.displayMetrics.density
        fun region(bitmap: Bitmap, i: Int): IntArray {
            val box = rowBoxes[i]
            val x = (box.left.value * density).toInt()
            val y = (box.top.value * density).toInt()
            val w = ((box.right - box.left).value * density).toInt().coerceAtLeast(1)
            val h = ((box.bottom - box.top).value * density).toInt().coerceAtLeast(1)
            return IntArray(w * h).also { bitmap.getPixels(it, 0, w, x, y, w, h) }
        }
        val rest = frame()
        val restPixels = rows.indices.map { region(rest, it) }
        sync = stopped
        for ((name, state, _) in states) {
            line = state
            rule.waitForIdle()
            rows.forEachIndexed { i, r ->
                assertEquals("$name: $r keeps its place", rowBoxes[i], row(r).getBoundsInRoot())
            }
            val shown = frame()
            rows.indices.forEach { i ->
                assertTrue("$name: row $i is drawn as before", restPixels[i].contentEquals(region(shown, i)))
            }
        }
        line = GlobalLine.SyncNeedsYou(SyncStatus.Kind.Failed)
        rule.waitForIdle()
        val file = File("../build/device-test/review/global-status-tv.png").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { frame().compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }

    @Test
    fun `at 960 x 540 each state keeps inside the safe area on one line`() {
        assertSafe()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1920dp-h1080dp-land-television-night-mdpi")
    fun `at 1920 x 1080 each state keeps inside the safe area on one line`() {
        assertSafe()
    }

    private fun assertSafe() {
        screen()
        sync = stopped.copy(detail = "Telegram said no, and then said it again at length so that the reason is long enough to wrap.")
        val box = rule.onNodeWithTag("screen").getBoundsInRoot()
        for ((name, state, _) in states) {
            line = state
            rule.waitForIdle()
            val pill = rule.onNodeWithTag("global").getBoundsInRoot()
            assertTrue("$name: top ${pill.top} below the safe line ${box.top + Tv.SafeV}", pill.top >= box.top + Tv.SafeV)
            assertTrue("$name: inside the screen sides $pill in $box", pill.left >= box.left + Tv.SafeH && pill.right <= box.right - Tv.SafeH)
            assertTrue("$name: one line, ${pill.bottom - pill.top} tall", pill.bottom - pill.top <= 48.dp)
            assertTrue("$name: at most 560 dp wide, ${pill.right - pill.left}", pill.right - pill.left <= 560.dp)
        }
    }

    @Test
    fun `the three failures line says Sync stopped, and where the way out is for a screen reader, since no button is drawn`() {
        screen()
        sync = stopped
        line = GlobalLine.SyncNeedsYou(SyncStatus.Kind.Failed)
        rule.waitForIdle()
        rule.onNode(hasText("Sync stopped")).assertExists()
        rule.onNode(hasContentDescription(L.stateSyncStoppedTv(name = rowName))).assertExists()
        assertWordsOnly()
    }

    @Test
    fun `in a narrow slot the pill shows the first word and never an ellipsis, and the screen reader still gets all of it`() {
        FormFactor.override(true)
        sync = stopped
        line = GlobalLine.SyncNeedsYou(SyncStatus.Kind.Failed)
        rule.setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.width(120.dp).testTag("slot")) {
                    TvStatusPill(TvStatusInput(line = line, sync = sync), modifier = Modifier.testTag("global"))
                }
            }
        }
        rule.waitForIdle()
        val pill = rule.onNodeWithTag("global").getBoundsInRoot()
        assertTrue("one line in a narrow slot, ${pill.bottom - pill.top} tall", pill.bottom - pill.top <= 48.dp)
        assertTrue("never wider than the slot, ${pill.right - pill.left}", pill.right - pill.left <= 120.dp)
        rule.onNode(hasText("Sync")).assertExists()
        rule.onAllNodes(hasText("\u2026", substring = true)).assertCountEquals(0)
        rule.onNode(hasContentDescription(L.stateSyncStoppedTv(name = rowName))).assertExists()
    }
}
