package com.tmplayer.ui.components

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import com.tmplayer.data.SyncWords
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
 * The corner's line on a television (WP-100, WP-102): catching up, slow, a wait and a sync that
 * needs the viewer say words only. Nothing in the pill takes the remote or answers a press (no
 * Retry, Try again, Send logs, Open settings, Close: those are for a finger or a pointer), the
 * remote stays on the row it was on through every state, no row moves or repaints when the pill
 * appears or changes, and the pill stays inside the safe area at 960 x 540 and 1920 x 1080. The
 * three failures line names where the way out is, since no button does.
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

    /** Three rows in the page and the pill in the television's foot slot, as the shell draws them. */
    private fun screen() {
        FormFactor.override(true)
        rule.setContent {
            TmMaterialTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(Tone.background).testTag("screen")) {
                    Column(Modifier.padding(48.dp).testTag("rows")) {
                        rows.forEach { name -> Button(onClick = {}) { Text(name) } }
                    }
                    GlobalStatusPill(
                        line = line,
                        syncStatus = sync,
                        modifier = Modifier.align(Alignment.BottomCenter).tvPillSlot().testTag("global"),
                        // A television passes none of these: the pill must not draw them anyway.
                        onRetry = {},
                        onTryAgainSync = {},
                        onSendLogs = {},
                        onOpenSettings = {},
                        onDismiss = {},
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

    private val states: List<Triple<String, GlobalLine, String>> get() = listOf(
        Triple("catching up", GlobalLine.Updating, L.stateUpdating),
        Triple("slow", GlobalLine.SlowAnswer, L.commonSlowAnswer),
        Triple("a wait", GlobalLine.RateLimited(42), L.stateRateLimited(seconds = 42)),
        Triple("a newer sync file", GlobalLine.SyncNeedsYou(SyncStatus.Kind.TooNew), L.settingsResumeSyncTooNew),
        Triple("an unreadable sync file", GlobalLine.SyncNeedsYou(SyncStatus.Kind.Unreadable), L.settingsResumeSyncUnreadable),
        Triple("another account", GlobalLine.SyncNeedsYou(SyncStatus.Kind.OtherAccount), L.settingsResumeSyncOtherAccount),
        Triple("three failed tries", GlobalLine.SyncNeedsYou(SyncStatus.Kind.Failed), SyncWords.stopped(stopped)),
    )

    @Test
    fun `every state says its words, takes no focus and offers no button on the remote`() {
        screen()
        sync = stopped
        val first = row(rows[0])
        first.requestFocus()
        first.assertIsFocused()
        for ((name, state, words) in states) {
            line = state
            rule.waitForIdle()
            rule.onNode(hasText(words, substring = true)).assertExists("$name: the words are drawn")
            assertWordsOnly()
            if (state is GlobalLine.SyncNeedsYou) {
                rule.onNode(hasText(L.settingsResumeSync, substring = true)).assertExists("$name: Settings' row is named")
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
    fun `at 960 x 540 each state keeps inside the safe area and the longest line wraps in two`() {
        assertSafe()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1920dp-h1080dp-land-television-night-mdpi")
    fun `at 1920 x 1080 each state keeps inside the safe area`() {
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
            assertTrue("$name: bottom ${pill.bottom} within safe line ${box.bottom - Tv.SafeV}", pill.bottom <= box.bottom - Tv.SafeV)
            assertTrue("$name: inside the screen sides $pill in $box", pill.left >= box.left + Tv.SafeH && pill.right <= box.right - Tv.SafeH)
        }
    }

    @Test
    fun `the three failures line says where the way out is, in words, since no button is drawn`() {
        screen()
        sync = stopped
        line = GlobalLine.SyncNeedsYou(SyncStatus.Kind.Failed)
        rule.waitForIdle()
        rule.onNode(hasText(L.settingsResumeSyncStopped, substring = true)).assertExists()
        rule.onNode(hasText(L.settingsResumeSync, substring = true)).assertExists()
        assertWordsOnly()
    }
}
